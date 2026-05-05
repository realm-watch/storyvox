package `in`.jphe.storyvox.data.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Upsert
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.Fiction
import `in`.jphe.storyvox.data.db.entity.PlaybackPosition
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackDao {

    @Query("SELECT * FROM playback_position WHERE fictionId = :fictionId")
    fun observe(fictionId: String): Flow<PlaybackPosition?>

    /** One-shot lookup used by the playback layer's `load(fictionId)`. */
    @Query("SELECT * FROM playback_position WHERE fictionId = :fictionId")
    suspend fun get(fictionId: String): PlaybackPosition?

    @Upsert
    suspend fun upsert(position: PlaybackPosition)

    @Query("DELETE FROM playback_position WHERE fictionId = :fictionId")
    suspend fun delete(fictionId: String)

    /**
     * Denormalized "recently played" feed for the Auto/Wear menu — flattens
     * fiction title + cover + chapter title into one row so the playback
     * layer doesn't need follow-up queries while building Auto's browse tree.
     */
    @Query(
        """
        SELECT p.fictionId   AS fictionId,
               p.chapterId   AS chapterId,
               f.title       AS bookTitle,
               c.title       AS chapterTitle,
               f.coverUrl    AS coverUrl
          FROM playback_position p
          JOIN fiction f ON f.id = p.fictionId
          JOIN chapter c ON c.id = p.chapterId
         ORDER BY p.updatedAt DESC
         LIMIT :limit
        """,
    )
    suspend fun recent(limit: Int): List<RecentPlaybackRow>

    /**
     * Joined "Continue listening" projection — Aurora flows this directly into
     * the Library tile.
     *
     * The SELECT explicitly aliases every column with the `f_` / `c_` prefix
     * that [ContinueListeningRow] expects via `@Embedded(prefix = ...)`. Room
     * will not infer prefixes from `t.*`.
     */
    @Query(
        """
        SELECT
            f.id                  AS f_id,
            f.sourceId            AS f_sourceId,
            f.title               AS f_title,
            f.author              AS f_author,
            f.authorId            AS f_authorId,
            f.coverUrl            AS f_coverUrl,
            f.description         AS f_description,
            f.genres              AS f_genres,
            f.tags                AS f_tags,
            f.status              AS f_status,
            f.chapterCount        AS f_chapterCount,
            f.wordCount           AS f_wordCount,
            f.rating              AS f_rating,
            f.views               AS f_views,
            f.followers           AS f_followers,
            f.lastUpdatedAt       AS f_lastUpdatedAt,
            f.firstSeenAt         AS f_firstSeenAt,
            f.metadataFetchedAt   AS f_metadataFetchedAt,
            f.inLibrary           AS f_inLibrary,
            f.addedToLibraryAt    AS f_addedToLibraryAt,
            f.followedRemotely    AS f_followedRemotely,
            f.downloadMode        AS f_downloadMode,
            f.pinnedVoiceId       AS f_pinnedVoiceId,
            f.pinnedVoiceLocale   AS f_pinnedVoiceLocale,
            f.notesEverSeen       AS f_notesEverSeen,
            c.id                  AS c_id,
            c.fictionId           AS c_fictionId,
            c.sourceChapterId     AS c_sourceChapterId,
            c.`index`             AS c_index,
            c.title               AS c_title,
            c.publishedAt         AS c_publishedAt,
            c.wordCount           AS c_wordCount,
            c.htmlBody            AS c_htmlBody,
            c.plainBody           AS c_plainBody,
            c.bodyFetchedAt       AS c_bodyFetchedAt,
            c.bodyChecksum        AS c_bodyChecksum,
            c.downloadState       AS c_downloadState,
            c.lastDownloadAttemptAt AS c_lastDownloadAttemptAt,
            c.lastDownloadError   AS c_lastDownloadError,
            c.notesAuthor         AS c_notesAuthor,
            c.notesAuthorPosition AS c_notesAuthorPosition,
            c.userMarkedRead      AS c_userMarkedRead,
            c.firstReadAt         AS c_firstReadAt,
            p.charOffset          AS charOffset,
            p.playbackSpeed       AS playbackSpeed,
            p.updatedAt           AS updatedAt
          FROM playback_position p
          JOIN fiction f ON f.id = p.fictionId
          JOIN chapter c ON c.id = p.chapterId
         WHERE f.inLibrary = 1
         ORDER BY p.updatedAt DESC
        """,
    )
    fun observeContinueListening(): Flow<List<ContinueListeningRow>>
}

data class ContinueListeningRow(
    @Embedded(prefix = "f_") val fiction: Fiction,
    @Embedded(prefix = "c_") val chapter: Chapter,
    val charOffset: Int,
    val playbackSpeed: Float,
    val updatedAt: Long,
)

/** Denormalized row backing the playback layer's "recent" Auto rail. */
data class RecentPlaybackRow(
    val fictionId: String,
    val chapterId: String,
    val bookTitle: String,
    val chapterTitle: String,
    val coverUrl: String?,
)
