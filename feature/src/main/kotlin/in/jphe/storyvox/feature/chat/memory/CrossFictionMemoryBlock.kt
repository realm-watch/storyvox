package `in`.jphe.storyvox.feature.chat.memory

import `in`.jphe.storyvox.data.db.entity.FictionMemoryEntry
import `in`.jphe.storyvox.data.repository.FictionMemoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull

/**
 * Issue #217 — builds the "Cross-fiction context" block appended to
 * the system prompt when the user has the toggle ON. Walks every
 * proper-name candidate the user just typed (or every entity in the
 * current book's notebook) and surfaces entries for the same name in
 * OTHER books.
 *
 * Token budget: capped at [TOKEN_BUDGET_CHARS] characters (~500
 * tokens at the conventional 4 chars/token estimate). When more
 * entries match than the budget allows, oldest entries (smallest
 * `lastUpdated`) are dropped first — recent memory is more likely
 * relevant.
 *
 * The block is *additive only* — if there's nothing to add (toggle
 * off, no cross-fiction matches, or all candidate names were
 * filtered), [build] returns an empty BuildResult and the caller
 * just appends nothing. This keeps the system-prompt diff-noise-free
 * when memory is empty.
 *
 * Title resolution is delegated to a small functional dependency
 * ([titleResolver]) so the v1 plumbing doesn't have to choose between
 * pulling in `FictionRepository` (core-data) or `FictionRepositoryUi`
 * (feature) — the caller wires whichever it already has handy. The
 * resolver is called once per distinct fictionId; null results fall
 * back to the fictionId string itself.
 */
class CrossFictionMemoryBlock(
    private val memoryRepo: FictionMemoryRepository,
    /** Resolve a fictionId to a human-readable title. Returning null
     *  is fine — the block renders the id as a fallback. */
    private val titleResolver: suspend (String) -> String?,
) {

    /**
     * Build the block for [fictionId]'s chat. [userMessage] is the
     * user's current turn — names in it are the lookup keys. If the
     * user didn't type any name we'd recognise, we fall back to the
     * current book's notebook entries as the lookup set ("you also
     * know these characters from other books").
     *
     * Returns an empty BuildResult when the block is empty so the
     * caller can blindly append `result.text` to the prompt.
     */
    suspend fun build(
        fictionId: String,
        userMessage: String,
        enabled: Boolean,
    ): BuildResult {
        if (!enabled) return BuildResult.empty()

        val candidateNames = candidateNamesFrom(userMessage, fictionId)
        if (candidateNames.isEmpty()) return BuildResult.empty()

        // Look up each candidate in OTHER books. Flatten + de-dupe by
        // (book, name) so the same character-in-book pair doesn't
        // appear twice if the user mentioned the name twice.
        val rawHits = candidateNames.flatMap { name ->
            memoryRepo.findEntityAcrossFictions(name, excludeFictionId = fictionId)
        }.distinctBy { it.fictionId to it.name }

        if (rawHits.isEmpty()) return BuildResult.empty()

        // Resolve other-book titles so the AI sees readable labels,
        // not opaque ids. Cache per fictionId so we don't repeat
        // lookups for the same book.
        val titles = HashMap<String, String>()
        val resolved = rawHits.map { entry ->
            val title = titles.getOrPut(entry.fictionId) {
                titleResolver(entry.fictionId) ?: entry.fictionId
            }
            ResolvedHit(entry = entry, fictionTitle = title)
        }

        // Sort newer first then prune to the character budget.
        val pruned = pruneToBudget(resolved.sortedByDescending { it.entry.lastUpdated })
        val rendered = renderBlock(pruned)

        return BuildResult(
            text = rendered,
            entryCount = pruned.size,
            droppedCount = resolved.size - pruned.size,
            approxTokens = approxTokens(rendered),
        )
    }

    private fun renderBlock(hits: List<ResolvedHit>): String {
        if (hits.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n\n## Cross-fiction context (other books in the reader's library)\n\n")
        sb.append("The reader has also read about these entities in other books. ")
        sb.append("If the name matches across books these may be different ")
        sb.append("characters/places — disambiguate by book.\n\n")
        hits.forEach { h ->
            sb.append("- ").append(h.entry.name)
            sb.append(" (from \"").append(h.fictionTitle).append("\", ")
            sb.append(h.entry.entityType.lowercase()).append("): ")
            sb.append(h.entry.summary).append('\n')
        }
        return sb.toString()
    }

    private fun pruneToBudget(sorted: List<ResolvedHit>): List<ResolvedHit> {
        val out = mutableListOf<ResolvedHit>()
        var running = HEADER_CHAR_OVERHEAD
        for (h in sorted) {
            val approxLineSize = h.entry.name.length + h.entry.summary.length +
                h.fictionTitle.length + LINE_CHAR_OVERHEAD
            if (running + approxLineSize > TOKEN_BUDGET_CHARS) break
            out += h
            running += approxLineSize
        }
        return out
    }

    /**
     * Naive proper-name detection in the user's message:
     *  - Tokens that start with a capital and aren't a STOPWORD.
     *  - When no name candidates land we broaden to the current
     *    book's notebook so the cross-fiction block fires for
     *    underspecified queries ("who is he again?") — we surface
     *    notebook characters from other books, since the user is
     *    implicitly thinking about some character.
     *
     * Heuristic, deliberately permissive. Over-extraction surfaces
     * extra rows in the prompt (token cost), under-extraction drops
     * context (no behavioural break). The budget enforcer keeps
     * blast radius bounded.
     */
    private suspend fun candidateNamesFrom(
        userMessage: String,
        fictionId: String,
    ): Set<String> {
        val tokens = NAME_TOKEN.findAll(userMessage).map { it.value.trim() }.toMutableList()
        val cleaned = tokens
            .map { it.trim('.', ',', '!', '?', ':', ';', '\'', '"') }
            .filter { it.length > 1 && it !in STOPWORDS }
            .toMutableSet()

        if (cleaned.isEmpty()) {
            cleaned += memoryRepo.forFictionOnce(fictionId).map { it.name }.take(8)
        }
        return cleaned
    }

    /** Approximate token cost. 4 chars/token is the canonical
     *  English-text rule of thumb the OpenAI tokeniser averages
     *  toward. Surfaced as a metric for the debug overlay (per the
     *  decisions list in #217). */
    fun approxTokens(text: String): Int = (text.length + 3) / 4

    data class BuildResult(
        val text: String,
        val entryCount: Int,
        val droppedCount: Int,
        val approxTokens: Int,
    ) {
        companion object {
            fun empty(): BuildResult = BuildResult("", 0, 0, 0)
        }
    }

    private data class ResolvedHit(
        val entry: FictionMemoryEntry,
        val fictionTitle: String,
    )

    companion object {
        /** Character cap for the rendered block — approximately 500
         *  tokens at the 4 chars/token rule of thumb. */
        const val TOKEN_BUDGET_CHARS: Int = 2000

        /** Approximate non-line overhead for the section header. */
        private const val HEADER_CHAR_OVERHEAD: Int = 220

        /** Approximate per-line scaffolding overhead beyond the
         *  data fields (bullet, parentheses, etc.). */
        private const val LINE_CHAR_OVERHEAD: Int = 24

        /** Capitalised-word tokeniser. */
        private val NAME_TOKEN = Regex(
            """\b[A-Z][a-zA-Z]{1,}(?:\s+[A-Z][a-zA-Z]+){0,2}""",
        )

        private val STOPWORDS = setOf(
            "The", "A", "An", "This", "That", "These", "Those", "It",
            "He", "She", "They", "We", "You", "I", "His", "Her",
            "Their", "Why", "Who", "What", "When", "Where", "How",
            "Which", "But", "And", "Or", "So", "If", "Storyvox",
        )

        /** Convenience for callers that have a `Flow<String?>` for
         *  the title rather than a suspend function — wraps the
         *  Flow's firstOrNull into the [titleResolver] signature. */
        fun titleResolverFor(flowOf: (String) -> Flow<String?>): suspend (String) -> String? =
            { id -> flowOf(id).firstOrNull() }
    }
}
