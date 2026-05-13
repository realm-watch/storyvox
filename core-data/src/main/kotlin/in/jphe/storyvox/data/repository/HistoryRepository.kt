package `in`.jphe.storyvox.data.repository

import `in`.jphe.storyvox.data.db.dao.ChapterHistoryDao
import `in`.jphe.storyvox.data.db.dao.ChapterHistoryRow
import `in`.jphe.storyvox.data.db.entity.ChapterHistory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #158 — reading-history surface.
 *
 * Two-method write API (`logOpen`, `markCompleted`) so the playback layer
 * doesn't have to know about Room entities or `openedAt` epoch semantics.
 * Read API exposes denormalized [HistoryEntry]s the UI can render directly
 * — same projection-layer pattern as [PlaybackPositionRepository]'s
 * `ContinueListeningEntry`.
 */
interface HistoryRepository {

    /** All history rows across every fiction, most-recent open first. */
    fun observeAll(): Flow<List<HistoryEntry>>

    /** Per-fiction history (deferred UI in #158 — wired here for the future FictionDetail surface). */
    fun observeForFiction(fictionId: String): Flow<List<HistoryEntry>>

    /**
     * Stamp a (fiction, chapter) open. Upserts so re-opens update
     * `openedAt` in place. Preserves any existing `completed` /
     * `fractionRead` so the end-of-chapter [markCompleted] write isn't
     * clobbered by a subsequent re-open before the user actually reads
     * the chapter again.
     */
    suspend fun logOpen(fictionId: String, chapterId: String)

    /**
     * End-of-chapter event. Sets `completed = true` and optionally a
     * `fractionRead` snapshot. Idempotent — calling twice doesn't double-
     * count anything.
     */
    suspend fun markCompleted(fictionId: String, chapterId: String, fraction: Float? = null)
}

/**
 * Public projection of one history row. Fiction/chapter title may be null
 * if the parent rows were cascade-deleted between the write and the read
 * (race window is tiny but FK CASCADE means it's possible). The UI is
 * expected to render a sigil-monogram fallback in that case — see
 * `fictionMonogram` in core-ui.
 */
data class HistoryEntry(
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

@Singleton
class HistoryRepositoryImpl @Inject constructor(
    private val dao: ChapterHistoryDao,
) : HistoryRepository {

    override fun observeAll(): Flow<List<HistoryEntry>> =
        dao.observeAll().map { rows -> rows.map(ChapterHistoryRow::toEntry) }

    override fun observeForFiction(fictionId: String): Flow<List<HistoryEntry>> =
        dao.observeForFiction(fictionId).map { rows -> rows.map(ChapterHistoryRow::toEntry) }

    override suspend fun logOpen(fictionId: String, chapterId: String) {
        // Read-then-upsert so we preserve the existing completed /
        // fractionRead — a fresh open after the user finished a chapter
        // should keep `completed = true`. The window between get + upsert
        // is single-coroutine-scoped (callers always invoke from the
        // playback Main-dispatcher coroutine), so racing writes from a
        // second coroutine aren't a concern in practice. If that
        // assumption ever breaks the right fix is a SQL `INSERT ...
        // ON CONFLICT DO UPDATE` with COALESCE — not adding a mutex.
        val existing = dao.get(fictionId, chapterId)
        dao.upsert(
            ChapterHistory(
                fictionId = fictionId,
                chapterId = chapterId,
                openedAt = System.currentTimeMillis(),
                completed = existing?.completed ?: false,
                fractionRead = existing?.fractionRead,
            ),
        )
    }

    override suspend fun markCompleted(
        fictionId: String,
        chapterId: String,
        fraction: Float?,
    ) {
        // No-op if there's no history row yet — the SQL UPDATE is a no-op
        // matching zero rows. That's the desired behaviour: a chapter the
        // user never opened via the player can't be "completed". The
        // playback layer always calls logOpen first inside loadAndPlay
        // (see EnginePlayer), so this should be unreachable in normal use,
        // but the guard keeps it idempotent.
        dao.markCompleted(fictionId, chapterId, fraction, System.currentTimeMillis())
    }
}

private fun ChapterHistoryRow.toEntry(): HistoryEntry = HistoryEntry(
    fictionId = fictionId,
    chapterId = chapterId,
    openedAt = openedAt,
    completed = completed,
    fractionRead = fractionRead,
    fictionTitle = fictionTitle,
    fictionAuthor = fictionAuthor,
    coverUrl = coverUrl,
    chapterTitle = chapterTitle,
    chapterIndex = chapterIndex,
)
