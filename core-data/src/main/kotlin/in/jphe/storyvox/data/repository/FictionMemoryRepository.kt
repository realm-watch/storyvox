package `in`.jphe.storyvox.data.repository

import `in`.jphe.storyvox.data.db.dao.FictionMemoryDao
import `in`.jphe.storyvox.data.db.entity.FictionMemoryEntry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Issue #217 — cross-fiction AI memory surface.
 *
 * Three responsibilities:
 *  1. Upsert entities the AI mentions in a chat reply
 *     ([recordEntity]). Idempotent on `(fictionId, name)` — restating
 *     a fact for the same character overwrites the prior summary.
 *  2. Cross-fiction lookup ([findEntityAcrossFictions]) — used by the
 *     ChatViewModel prompt-builder to surface "you also know X from
 *     books Y and Z" before each turn.
 *  3. Per-fiction observation ([entitiesForFiction]) — backs the
 *     Notebook sub-tab on the Library → Fiction detail screen.
 *
 * Per-user-library partition: there's no per-author / per-world
 * segmentation. If a user reads two books with characters both named
 * "Sarah", [findEntityAcrossFictions] returns both candidates and the
 * AI is expected to disambiguate. Coarse but cheap; the v1 trade-off
 * documented in #217.
 *
 * The repository itself doesn't honour the "carry memory across
 * fictions" Settings toggle — that gating happens at the prompt-builder
 * call-site. The repo is the write path of last resort; if you call
 * [recordEntity], it writes. This mirrors the InboxRepository pattern.
 */
interface FictionMemoryRepository {

    /** Upsert an entity for [fictionId]. [name] is the natural-key
     *  half of the composite PK; passing the same `(fictionId, name)`
     *  pair updates the prior row. [summary] is truncated to
     *  [SUMMARY_MAX_CHARS] before write to bound row size. */
    suspend fun recordEntity(
        fictionId: String,
        entityType: FictionMemoryEntry.Kind,
        name: String,
        summary: String,
        firstSeenChapterIndex: Int? = null,
        userEdited: Boolean = false,
    )

    /**
     * All entries matching [name] across the user's library. When
     * [excludeFictionId] is non-null its rows are filtered out — the
     * common ChatViewModel use is "find OTHER books where this name
     * appears", excluding the chat's anchor book.
     */
    suspend fun findEntityAcrossFictions(
        name: String,
        excludeFictionId: String? = null,
    ): List<FictionMemoryEntry>

    /** Live feed of every entry for [fictionId] — Notebook UI. */
    fun entitiesForFiction(fictionId: String): Flow<List<FictionMemoryEntry>>

    /** Synchronous fetch of every entry for [fictionId]. */
    suspend fun forFictionOnce(fictionId: String): List<FictionMemoryEntry>

    /** Delete a single entry by its composite PK — Notebook "X" tap. */
    suspend fun deleteEntry(fictionId: String, name: String)

    companion object {
        /** Trim AI-generated summaries to this length on write. The
         *  cap is generous — most one-liners are under 100 chars; the
         *  ceiling guards against a runaway model dumping a paragraph
         *  into a single entry. */
        const val SUMMARY_MAX_CHARS: Int = 300
    }
}

@Singleton
class FictionMemoryRepositoryImpl @Inject constructor(
    private val dao: FictionMemoryDao,
) : FictionMemoryRepository {

    override suspend fun recordEntity(
        fictionId: String,
        entityType: FictionMemoryEntry.Kind,
        name: String,
        summary: String,
        firstSeenChapterIndex: Int?,
        userEdited: Boolean,
    ) {
        val normalised = name.trim()
        if (normalised.isBlank()) return
        val summaryClipped = summary.trim().take(FictionMemoryRepository.SUMMARY_MAX_CHARS)
        // Preserve user-edited entries: if a hand-curated row already
        // exists for (fictionId, name), don't let the AI extractor
        // silently overwrite it. The Notebook UI is the explicit
        // override path — re-editing manually re-flips userEdited=true.
        if (!userEdited) {
            val existing = dao.forFiction(fictionId).firstOrNull { it.name == normalised }
            if (existing?.userEdited == true) return
        }
        dao.upsert(
            FictionMemoryEntry(
                fictionId = fictionId,
                entityType = entityType.name,
                name = normalised,
                summary = summaryClipped,
                firstSeenChapterIndex = firstSeenChapterIndex,
                lastUpdated = System.currentTimeMillis(),
                userEdited = userEdited,
            ),
        )
    }

    override suspend fun findEntityAcrossFictions(
        name: String,
        excludeFictionId: String?,
    ): List<FictionMemoryEntry> = dao.findByName(name.trim(), excludeFictionId)

    override fun entitiesForFiction(fictionId: String): Flow<List<FictionMemoryEntry>> =
        dao.observeForFiction(fictionId)

    override suspend fun forFictionOnce(fictionId: String): List<FictionMemoryEntry> =
        dao.forFiction(fictionId)

    override suspend fun deleteEntry(fictionId: String, name: String) {
        dao.delete(fictionId, name.trim())
    }
}
