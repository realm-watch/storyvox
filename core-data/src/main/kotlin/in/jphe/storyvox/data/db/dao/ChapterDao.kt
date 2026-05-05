package `in`.jphe.storyvox.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {

    @Query("SELECT * FROM chapter WHERE fictionId = :fictionId ORDER BY `index` ASC")
    fun observeByFiction(fictionId: String): Flow<List<Chapter>>

    @Query("SELECT * FROM chapter WHERE id = :id")
    fun observe(id: String): Flow<Chapter?>

    @Query("SELECT * FROM chapter WHERE id = :id")
    suspend fun get(id: String): Chapter?

    @Query("SELECT id, downloadState FROM chapter WHERE fictionId = :fictionId")
    fun observeDownloadStates(fictionId: String): Flow<List<ChapterDownloadStateRow>>

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
