package `in`.jphe.storyvox.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Issue #217 — cross-fiction AI memory entry. One row per
 * (fictionId, name) pair recording an entity (character, place, or
 * concept) the AI has told the user about in a particular book's
 * chat.
 *
 * Powers two surfaces:
 *  1. The per-fiction Notebook sub-tab on the Library → Fiction detail
 *     screen — `entitiesForFiction(fictionId)` is the hot query, the
 *     index on `fictionId` keeps it constant-time.
 *  2. The cross-fiction prompt-builder block — when the user asks
 *     "who is X?" in book B's chat, [findEntityAcrossFictions] surfaces
 *     prior entries from books A/C/... where the same name appeared.
 *     The composite index on `name` covers that lookup.
 *
 * Design notes:
 *  - Composite primary key `(fictionId, name)` so upsert semantics
 *    are "one fact per name per book". Re-stating the same character
 *    name in a follow-up reply updates `summary` + `lastUpdated`
 *    rather than appending a duplicate row. This is approximate —
 *    same name can map to different people across books — which is
 *    why the *cross-fiction* lookup returns ALL matches and lets the
 *    AI disambiguate ("Sarah from Project Hail Mary; Sarah from
 *    The Wandering Inn").
 *  - `name` is the index column, NOT the PK, because Room's composite
 *    PK index already covers `(fictionId, name)` left-to-right, which
 *    is fine for the per-fiction read but NOT for the cross-fiction
 *    read (`WHERE name = ?`). The explicit single-column index
 *    handles the cross-fiction path.
 *  - No FK to fiction: a user can have prior memory entries for a
 *    book they've since removed from their library. Surfacing those
 *    is the whole point — "you read this character in a book you
 *    don't have anymore" is still useful context. The repository
 *    layer can opportunistically prune on demand if storage becomes
 *    an issue.
 *  - `entityType` is TEXT (enum name) for forward-compat — adding a
 *    fourth kind (EVENT, ITEM, etc.) doesn't require a migration.
 *  - `firstSeenChapterIndex` is nullable because the heuristic
 *    extractor doesn't always know which chapter the AI was
 *    referencing. When known it lets the Notebook UI sort entries
 *    by first appearance.
 */
@Entity(
    tableName = "fiction_memory_entry",
    primaryKeys = ["fictionId", "name"],
    indices = [
        Index(value = ["name"]),
        Index(value = ["fictionId"]),
    ],
)
data class FictionMemoryEntry(
    /** The fiction row this entry was learned from. No FK so removal
     *  of the fiction doesn't cascade-wipe what the AI remembers. */
    val fictionId: String,
    /** CHARACTER / PLACE / CONCEPT — stored as enum name. */
    val entityType: String,
    /** Canonical display name. Used as part of the composite PK so
     *  case + spacing matter — the extractor normalises before
     *  upsert (strips trailing punctuation, collapses whitespace). */
    val name: String,
    /** One-line summary the AI provided, or the user typed manually.
     *  Capped at ~300 chars at upsert time so a runaway AI response
     *  can't bloat the table. */
    val summary: String,
    /** Chapter index (0-based) the entity was first seen at, or null
     *  if unknown. The Notebook UI orders by this when present. */
    val firstSeenChapterIndex: Int? = null,
    /** Epoch millis of the most recent upsert. */
    val lastUpdated: Long,
    /** True when the user added/edited this entry manually via the
     *  Notebook UI. Distinguishes hand-curated facts (preserved
     *  across re-extractions) from AI-extracted ones (overwritable). */
    val userEdited: Boolean = false,
) {
    /** Kind of entity the entry represents. String-backed at the DB
     *  layer for additive-evolution. */
    enum class Kind { CHARACTER, PLACE, CONCEPT }
}
