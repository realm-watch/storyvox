package `in`.jphe.storyvox.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {

    /**
     * Slim chapter-info feed for FictionDetail and the public chapter list.
     *
     * Both consumers ([FictionRepositoryImpl.observeFiction] and
     * [ChapterRepositoryImpl.observeChaptersFor]) immediately map the rows
     * through [Chapter.toInfo], discarding the multi-MB `htmlBody` /
     * `plainBody` blobs. By projecting only the `ChapterInfo` columns at
     * the SQL level we avoid reading those text columns off disk on every
     * emission. Each emission already fires whenever any single chapter
     * row changes (downloadState flip during a queued download, body
     * write on completion, userMarkedRead toggle) — for a fiction with
     * 500+ chapters that's a lot of throwaway body bytes.
     */
    @Query(
        """
        SELECT id, sourceChapterId, `index`, title, publishedAt, wordCount
          FROM chapter
         WHERE fictionId = :fictionId
         ORDER BY `index` ASC
        """,
    )
    fun observeChapterInfosByFiction(fictionId: String): Flow<List<ChapterInfoRow>>

    @Query("SELECT * FROM chapter WHERE id = :id")
    fun observe(id: String): Flow<Chapter?>

    @Query("SELECT * FROM chapter WHERE id = :id")
    suspend fun get(id: String): Chapter?

    @Query("SELECT id, downloadState FROM chapter WHERE fictionId = :fictionId")
    fun observeDownloadStates(fictionId: String): Flow<List<ChapterDownloadStateRow>>

    /**
     * Issue #282 — observable per-fiction "which chapters are played"
     * projection, separate from [observeChapterInfosByFiction]. Reading
     * `userMarkedRead` would mean adding the column to [ChapterInfoRow]
     * (which mirrors the source-side [ChapterInfo] model and is a 1:1
     * mapper today) or denormalizing the chapter list. A slim sibling
     * flow keeps the source/UI contract intact: AppBindings combines
     * the chapter-list flow with this set to compute `isFinished`.
     *
     * Selecting only the rows where `userMarkedRead = 1` keeps the
     * emission small for users with mostly-unplayed long fictions; the
     * combine in AppBindings does a set-contains check per chapter.
     */
    @Query("SELECT id FROM chapter WHERE fictionId = :fictionId AND userMarkedRead = 1")
    fun observePlayedChapterIds(fictionId: String): Flow<List<String>>

    @Query(
        """
        SELECT * FROM chapter
         WHERE fictionId = :fictionId AND downloadState = 'NOT_DOWNLOADED'
         ORDER BY `index` ASC
        """,
    )
    suspend fun missingForFiction(fictionId: String): List<Chapter>

    @Query("SELECT MAX(`index`) FROM chapter WHERE fictionId = :fictionId")
    suspend fun maxIndex(fictionId: String): Int?

    /** Neighbour lookups used by Auto/Wear "next chapter" and auto-advance. */
    @Query(
        """
        SELECT id FROM chapter
         WHERE fictionId = (SELECT fictionId FROM chapter WHERE id = :currentId)
           AND `index` > (SELECT `index`     FROM chapter WHERE id = :currentId)
         ORDER BY `index` ASC LIMIT 1
        """,
    )
    suspend fun nextChapterId(currentId: String): String?

    @Query(
        """
        SELECT id FROM chapter
         WHERE fictionId = (SELECT fictionId FROM chapter WHERE id = :currentId)
           AND `index` < (SELECT `index`     FROM chapter WHERE id = :currentId)
         ORDER BY `index` DESC LIMIT 1
        """,
    )
    suspend fun previousChapterId(currentId: String): String?

    /** Joined projection used to build the playback layer's [PlaybackChapter]. */
    @Query(
        """
        SELECT c.id            AS id,
               c.fictionId     AS fictionId,
               COALESCE(c.plainBody, '') AS text,
               c.title         AS title,
               f.title         AS bookTitle,
               f.coverUrl      AS coverUrl
          FROM chapter c
          JOIN fiction f ON f.id = c.fictionId
         WHERE c.id = :id
        """,
    )
    suspend fun playbackChapter(id: String): PlaybackChapterRow?

    /** Unread-chapter feed for the Auto "Continue subscribed series" rail. */
    @Query(
        """
        SELECT c.id            AS chapterId,
               c.fictionId     AS fictionId,
               c.title         AS chapterTitle,
               f.title         AS bookTitle,
               f.coverUrl      AS coverUrl
          FROM chapter c
          JOIN fiction f ON f.id = c.fictionId
         WHERE f.inLibrary = 1
           AND c.userMarkedRead = 0
         ORDER BY c.publishedAt DESC, c.`index` DESC
         LIMIT :limit
        """,
    )
    suspend fun unreadChapters(limit: Int): List<UnreadChapterRow>

    @Upsert
    suspend fun upsert(chapter: Chapter)

    @Upsert
    suspend fun upsertAll(chapters: List<Chapter>)

    @Query(
        """
        UPDATE chapter
           SET downloadState = :state,
               lastDownloadAttemptAt = :now,
               lastDownloadError = :error
         WHERE id = :id
        """,
    )
    suspend fun setDownloadState(id: String, state: ChapterDownloadState, now: Long, error: String?)

    @Query(
        """
        UPDATE chapter
           SET htmlBody = :html,
               plainBody = :plain,
               bodyFetchedAt = :now,
               bodyChecksum = :checksum,
               downloadState = 'DOWNLOADED',
               lastDownloadAttemptAt = :now,
               lastDownloadError = NULL,
               notesAuthor = :notesAuthor,
               notesAuthorPosition = :notesAuthorPosition
         WHERE id = :id
        """,
    )
    suspend fun setBody(
        id: String,
        html: String,
        plain: String,
        checksum: String,
        notesAuthor: String?,
        notesAuthorPosition: String?,
        now: Long,
    )

    @Query(
        """
        UPDATE chapter SET userMarkedRead = :read,
                           firstReadAt = CASE WHEN :read = 1 AND firstReadAt IS NULL THEN :now ELSE firstReadAt END
         WHERE id = :id
        """,
    )
    suspend fun setRead(id: String, read: Boolean, now: Long)

    @Query(
        """
        UPDATE chapter SET htmlBody = NULL, plainBody = NULL,
                           bodyFetchedAt = NULL, bodyChecksum = NULL,
                           downloadState = 'NOT_DOWNLOADED'
         WHERE fictionId = :fictionId
           AND `index` < (
               SELECT MAX(`index`) - :keepLast
                 FROM chapter WHERE fictionId = :fictionId
           )
        """,
    )
    suspend fun trimDownloadedBodies(fictionId: String, keepLast: Int)
}

/** Light projection used by [ChapterDao.observeDownloadStates]. */
data class ChapterDownloadStateRow(
    val id: String,
    val downloadState: ChapterDownloadState,
)

/**
 * Slim chapter-info row backing [ChapterDao.observeChapterInfosByFiction].
 * Mirrors the [`in`.jphe.storyvox.data.source.model.ChapterInfo] field set
 * directly so the repository mapper is a 1:1 copy and no body-text columns
 * are ever read off disk for the FictionDetail / chapter-list feeds.
 */
data class ChapterInfoRow(
    val id: String,
    val sourceChapterId: String,
    val index: Int,
    val title: String,
    val publishedAt: Long?,
    val wordCount: Int?,
)

/** Joined chapter+fiction projection for the playback layer. */
data class PlaybackChapterRow(
    val id: String,
    val fictionId: String,
    val text: String,
    val title: String,
    val bookTitle: String,
    val coverUrl: String?,
)

/** Joined unread-chapter projection. */
data class UnreadChapterRow(
    val chapterId: String,
    val fictionId: String,
    val chapterTitle: String,
    val bookTitle: String,
    val coverUrl: String?,
)
