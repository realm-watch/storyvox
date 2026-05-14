package `in`.jphe.storyvox.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import `in`.jphe.storyvox.data.db.entity.FictionMemoryEntry
import kotlinx.coroutines.flow.Flow

/**
 * Issue #217 — DAO for the cross-fiction memory table.
 *
 * Three hot reads:
 *  - [observeForFiction] backs the per-fiction Notebook tab.
 *  - [observeByName] is the cross-fiction lookup the prompt-builder
 *    runs once per chat turn; the `name` index makes it O(log N).
 *  - [allForBudget] feeds the token-budget pruning path when the
 *    cross-fiction context block has to drop oldest entries first.
 *
 * Writes go exclusively through [upsert]; the composite PK
 * `(fictionId, name)` means a duplicate `(fiction, name)` overwrites
 * the prior row, which is the upsert semantics the repository wants.
 */
@Dao
interface FictionMemoryDao {

    /** All entries for one fiction, ordered by first-seen chapter
     *  index when known (entries with `firstSeenChapterIndex IS NULL`
     *  sort last). Powers the Notebook sub-tab. */
    @Query(
        """
        SELECT * FROM fiction_memory_entry
         WHERE fictionId = :fictionId
         ORDER BY
           CASE WHEN firstSeenChapterIndex IS NULL THEN 1 ELSE 0 END,
           firstSeenChapterIndex ASC,
           lastUpdated DESC
        """,
    )
    fun observeForFiction(fictionId: String): Flow<List<FictionMemoryEntry>>

    /** Cross-fiction lookup — every entry matching [name] across the
     *  user's library. Most-recently-updated first. Optionally
     *  excludes a single fiction (the chat's anchor book) so the AI
     *  only sees "other books with this name". */
    @Query(
        """
        SELECT * FROM fiction_memory_entry
         WHERE name = :name
           AND (:excludeFictionId IS NULL OR fictionId != :excludeFictionId)
         ORDER BY lastUpdated DESC
        """,
    )
    suspend fun findByName(name: String, excludeFictionId: String?): List<FictionMemoryEntry>

    /** All entries the user has, optionally excluding a book. Used by
     *  the prompt-builder when it walks every name mentioned in a
     *  chat reply (we look up each separately, but this is here for
     *  the future structured-LLM extractor that wants the whole
     *  library at once). */
    @Query(
        """
        SELECT * FROM fiction_memory_entry
         WHERE (:excludeFictionId IS NULL OR fictionId != :excludeFictionId)
         ORDER BY lastUpdated DESC
        """,
    )
    suspend fun allEntries(excludeFictionId: String?): List<FictionMemoryEntry>

    /** Synchronous fetch of all entries for a fiction — used by the
     *  prompt-builder when it wants to inject the current book's
     *  notebook into the system prompt (a follow-up to v1; the
     *  method is here so the surface area lines up). */
    @Query("SELECT * FROM fiction_memory_entry WHERE fictionId = :fictionId")
    suspend fun forFiction(fictionId: String): List<FictionMemoryEntry>

    /** Upsert keyed on the composite PK (fictionId, name). */
    @Upsert
    suspend fun upsert(entry: FictionMemoryEntry)

    /** Delete one entry — the Notebook UI's "X" affordance. */
    @Query("DELETE FROM fiction_memory_entry WHERE fictionId = :fictionId AND name = :name")
    suspend fun delete(fictionId: String, name: String)

    /** Wipe every entry for a fiction. Reserved for future "forget
     *  this book" surface; not wired into the v1 UI. */
    @Query("DELETE FROM fiction_memory_entry WHERE fictionId = :fictionId")
    suspend fun deleteAllForFiction(fictionId: String)
}
