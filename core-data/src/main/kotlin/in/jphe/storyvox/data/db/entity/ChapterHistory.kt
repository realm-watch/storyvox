package `in`.jphe.storyvox.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Issue #158 — reading-history breadcrumb. One row per `(fictionId, chapterId)`
 * pair, upserted on every chapter open. Powers the Library "History" sub-tab
 * (chronological, most-recent-first, all fictions interleaved).
 *
 * Why composite PK (not append-only):
 *  - JP's product call: "forever retention" but a single row per chapter, so
 *    re-opening the same chapter twenty times doesn't blow up the table or the
 *    History list with duplicate "Ch. 1" entries. We update `openedAt` in
 *    place — the row is the *last-open* timestamp, not an audit log.
 *  - O(library) bound on storage instead of O(opens). For a long-running
 *    listener with 50 fictions × 500 chapters that's 25k rows worst case,
 *    well within Room's sweet spot.
 *
 * Index on [openedAt] makes the History feed query `ORDER BY openedAt DESC`
 * cheap — that's the only sort the UI uses.
 *
 * Cascading deletes on both parent tables: removing a fiction (or its
 * chapters) from the library shouldn't leave dangling history rows pointing
 * at gone metadata. The Library History tab joins back through fiction +
 * chapter for display data anyway, so an orphan would just render blank.
 */
@Entity(
    tableName = "chapter_history",
    primaryKeys = ["fictionId", "chapterId"],
    foreignKeys = [
        ForeignKey(
            entity = Fiction::class,
            parentColumns = ["id"],
            childColumns = ["fictionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Chapter::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["openedAt"]),
        // Room requires an index on the child side of any FK whose parent
        // column isn't the leading edge of the PK — without this Room
        // emits a warning ("missing index on chapterId"). Composite PK
        // covers fictionId via its leading position; chapterId needs its
        // own index for the cascade-delete planner to find rows fast.
        Index(value = ["chapterId"]),
    ],
)
data class ChapterHistory(
    val fictionId: String,
    val chapterId: String,
    /** Epoch millis of the most recent open. Updated in place on re-open. */
    val openedAt: Long,
    /** True once the user has finished the chapter (end-of-chapter event). */
    val completed: Boolean = false,
    /**
     * Optional fraction-read snapshot at the most recent open/completion
     * (0.0..1.0). Null when we don't have a reliable measure yet — kept
     * forward-compatible so a future progress-tracker can populate it
     * without another migration.
     */
    val fractionRead: Float? = null,
)
