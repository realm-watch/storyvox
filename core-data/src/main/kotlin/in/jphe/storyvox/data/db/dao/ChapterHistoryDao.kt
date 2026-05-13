package `in`.jphe.storyvox.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import `in`.jphe.storyvox.data.db.entity.ChapterHistory
import kotlinx.coroutines.flow.Flow

/**
 * Issue #158 — reading-history DAO.
 *
 * The `observeAll` / `observeForFiction` feeds project a denormalized join
 * over `fiction` + `chapter` directly into [ChapterHistoryRow] so the UI
 * never has to fan out follow-up lookups (mirrors the
 * [PlaybackDao.observeMostRecentContinueListening] pattern in the same
 * package). We deliberately skip the multi-MB `chapter.plainBody` / `htmlBody`
 * columns — the History row only needs titles, the cover URL, and chapter
 * index, all small fields. Same disk-I/O reasoning as [ChapterDao]'s
 * `ChapterInfoRow` projection.
 *
 * No `LIMIT` on `observeAll` because the Library History tab is a
 * scrollable LazyColumn — Compose's lazy layout already windows the render.
 * If feeds get huge (10k+ rows) we can add `LIMIT` + paging later; for now
 * the simpler API beats premature pagination.
 */
@Dao
interface ChapterHistoryDao {

    /**
     * All history rows across every fiction, most-recent-open first. Powers
     * the Library "History" sub-tab. Joins back through `fiction` + `chapter`
     * to bring in display fields; if either parent row is gone the LEFT JOIN
     * still emits the row with nulls — but FK cascade should make that
     * unreachable in practice.
     */
    @Query(
        """
        SELECT
            h.fictionId     AS fictionId,
            h.chapterId     AS chapterId,
            h.openedAt      AS openedAt,
            h.completed     AS completed,
            h.fractionRead  AS fractionRead,
            f.title         AS fictionTitle,
            f.author        AS fictionAuthor,
            f.coverUrl      AS coverUrl,
            c.title         AS chapterTitle,
            c.`index`       AS chapterIndex
          FROM chapter_history h
          LEFT JOIN fiction f ON f.id = h.fictionId
          LEFT JOIN chapter c ON c.id = h.chapterId
         ORDER BY h.openedAt DESC
        """,
    )
    fun observeAll(): Flow<List<ChapterHistoryRow>>

    /**
     * Per-fiction history (deferred FictionDetail surface in #158 — wired
     * up here so the repo can expose it without another schema change
     * later). Same join shape as [observeAll] so the UI can share a row
     * composable.
     */
    @Query(
        """
        SELECT
            h.fictionId     AS fictionId,
            h.chapterId     AS chapterId,
            h.openedAt      AS openedAt,
            h.completed     AS completed,
            h.fractionRead  AS fractionRead,
            f.title         AS fictionTitle,
            f.author        AS fictionAuthor,
            f.coverUrl      AS coverUrl,
            c.title         AS chapterTitle,
            c.`index`       AS chapterIndex
          FROM chapter_history h
          LEFT JOIN fiction f ON f.id = h.fictionId
          LEFT JOIN chapter c ON c.id = h.chapterId
         WHERE h.fictionId = :fictionId
         ORDER BY h.openedAt DESC
        """,
    )
    fun observeForFiction(fictionId: String): Flow<List<ChapterHistoryRow>>

    /** One-row lookup used by the repository's `logOpen` to preserve `completed`/`fractionRead` on re-open. */
    @Query("SELECT * FROM chapter_history WHERE fictionId = :fictionId AND chapterId = :chapterId")
    suspend fun get(fictionId: String, chapterId: String): ChapterHistory?

    @Upsert
    suspend fun upsert(row: ChapterHistory)

    /**
     * Mark a chapter as completed. We use a direct UPDATE rather than a
     * read-modify-write so the end-of-chapter path (which races with the
     * `logOpen` upsert from `loadAndPlay`) stays atomic. The COALESCE on
     * `fractionRead` lets callers pass null to "leave the existing value
     * alone" — e.g. when end-of-chapter fires without a fresh measure.
     */
    @Query(
        """
        UPDATE chapter_history
           SET completed = 1,
               fractionRead = COALESCE(:fraction, fractionRead),
               openedAt = :now
         WHERE fictionId = :fictionId AND chapterId = :chapterId
        """,
    )
    suspend fun markCompleted(fictionId: String, chapterId: String, fraction: Float?, now: Long)
}

/**
 * Denormalized history row — fiction + chapter columns inlined so the
 * Library History list can render straight off a single SQL feed without
 * follow-up lookups. Mirrors [ContinueListeningRow]'s shape on the
 * playback DAO; we keep the column names un-prefixed here because the
 * row isn't reconstructing a public [FictionSummary] (that lives in the
 * repository projection layer).
 *
 * `fictionTitle` / `chapterTitle` / `coverUrl` may be null if the parent
 * rows got cascade-deleted between the history write and a UI emission —
 * the row composable should treat them as "(unknown)" / a fallback monogram
 * rather than crashing.
 */
data class ChapterHistoryRow(
    val fictionId: String,
    val chapterId: String,
    val openedAt: Long,
    val completed: Boolean,
    val fractionRead: Float?,
    val fictionTitle: String?,
    val fictionAuthor: String?,
    val coverUrl: String?,
    val chapterTitle: String?,
    val chapterIndex: Int?,
)
