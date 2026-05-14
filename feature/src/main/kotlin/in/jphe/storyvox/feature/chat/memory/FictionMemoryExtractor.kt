package `in`.jphe.storyvox.feature.chat.memory

import `in`.jphe.storyvox.data.db.entity.FictionMemoryEntry

/**
 * Issue #217 — v1 regex-based entity extractor. The AI chat reply is
 * the source of memory entries: when the assistant says "Kelsier is a
 * Mistborn who…", we want to record `Kelsier → "Mistborn who…"` in
 * the per-fiction memory table.
 *
 * This is *deliberately* approximate. A future follow-up will replace
 * it with a structured LLM call (a second model invocation that
 * returns JSON like `[{name, type, summary}]`), which will be both
 * more accurate and more expensive. The regex extractor is "better
 * than nothing for v1" — it'll over-extract (every capitalised word
 * looks like a name), and the Notebook UI lets the user delete
 * bogus rows. See follow-up (a) in the PR description.
 *
 * Two patterns the extractor recognises:
 *   1. **Defining sentence**: `<Name> is a <NounPhrase>` or
 *      `<Name> is the <NounPhrase>`. The NounPhrase is the summary.
 *   2. **Possessive/role**: `<Name>'s <role>` or `<Name>, the <role>`
 *      — captures the role as the summary.
 *
 * Names are heuristically detected as one-to-three capitalised words
 * (e.g. "Vin", "Lord Ruler", "Hoid the Bondsmith") with optional
 * apostrophes (Sazed's). We *exclude* sentence-starting capitals by
 * requiring at least one non-capital token before the candidate, OR
 * by recognising specific high-noise starter words.
 *
 * The output [ExtractedEntity] is unscored — every match is returned.
 * Scoring is a v2 concern (follow-up (d)).
 */
object FictionMemoryExtractor {

    /** What the extractor recovered from one reply. The caller upserts
     *  these via [FictionMemoryRepository.recordEntity]. */
    data class ExtractedEntity(
        val name: String,
        val kind: FictionMemoryEntry.Kind,
        val summary: String,
    )

    /**
     * Capitalised-word-name regex. Matches:
     *   Kelsier
     *   Lord Ruler
     *   Hoid the Bondsmith
     *   Mr. Ollivander       (period allowed in the leading token)
     *
     * Excludes:
     *   The, A, An, …        (sentence-leaders, handled via STOPWORDS)
     *
     * Anchored on word boundary so we don't grab "Reading" out of
     * "Reading is fun". The 1-4 word ceiling stops it from gobbling
     * the entire rest of the sentence on something like
     * "I am Iron Man Tony Stark".
     */
    private val NAME = Regex("""\b([A-Z][a-zA-Z]{1,}(?:\.\s+[A-Z][a-zA-Z]+)?(?:\s+(?:the|of|de|von|van)?\s*[A-Z][a-zA-Z]+){0,3})""")

    /**
     * "X is (a|the|an) ..." up to a sentence terminator. Greedy on the
     * predicate side so multi-clause predicates ("a mentor who taught
     * Vin to use her allomantic powers") survive intact.
     */
    private val IS_A = Regex(
        """\b([A-Z][a-zA-Z]+(?:\s+[A-Z][a-zA-Z]+){0,2})\s+is\s+(?:a|an|the)\s+([^.!?]+)[.!?]""",
    )

    /** Common English sentence-leaders + AI chat boilerplate. These
     *  almost-never refer to a character even when capitalised. */
    private val STOPWORDS = setOf(
        "The", "A", "An", "This", "That", "These", "Those", "It", "He",
        "She", "They", "We", "You", "I", "His", "Her", "Their", "My",
        "Our", "Your", "Yes", "No", "Some", "Many", "Most", "All",
        "Every", "Each", "There", "Here", "Now", "Then", "When", "Where",
        "Who", "Why", "How", "What", "Which", "But", "And", "Or", "So",
        "However", "Although", "Because", "If", "Unless", "While",
        // AI-reply scaffolding words
        "Based", "Given", "From", "According", "In", "On", "At",
        "Quote", "Quoting", "Note", "Notes", "Spoiler", "Spoilers",
        "Chapter", "Book", "Fiction", "Story", "Reader", "Listener",
        "Storyvox",
    )

    /** Extract entities from [reply]. The output may contain duplicate
     *  names with different summaries — the repository upserts in
     *  order, so the last one wins, which matches "AI restated and
     *  refined the description" semantics. */
    fun extract(reply: String): List<ExtractedEntity> {
        if (reply.isBlank()) return emptyList()
        val out = mutableListOf<ExtractedEntity>()

        // Pattern 1: "X is a Y."
        IS_A.findAll(reply).forEach { m ->
            val name = m.groupValues[1].trim()
            if (name in STOPWORDS) return@forEach
            // First token alone is usually safer (e.g. "Vin" not
            // "Vin Venture" — the latter is over-eager). Take the
            // last token's capitalisation as the signal: if it looks
            // like a name (no STOPWORD), keep full; else fall back.
            val nameNorm = normaliseName(name)
            if (nameNorm.isBlank()) return@forEach
            val summary = m.groupValues[2].trim().take(180)
            out += ExtractedEntity(
                name = nameNorm,
                kind = classifyKind(summary),
                summary = "Is a $summary.",
            )
        }

        return out.distinctBy { it.name.lowercase() }
    }

    /** Strip trailing punctuation + apostrophes, collapse internal
     *  whitespace. Returns blank if nothing meaningful remains. */
    private fun normaliseName(raw: String): String {
        val cleaned = raw
            .trim()
            .trim(',', '.', '!', '?', ';', ':', '\'', '"')
            .replace(Regex("""\s+"""), " ")
        if (cleaned in STOPWORDS) return ""
        // First-token guard against sentence-leading capitals: if the
        // single token is a STOPWORD-capitalisation, drop. "The" was
        // already caught above; this also catches "However" etc.
        val firstToken = cleaned.substringBefore(' ')
        if (firstToken in STOPWORDS) return ""
        return cleaned
    }

    /** Pick the kind from a heuristic on the summary text. Falls back
     *  to CHARACTER (the most common case). */
    private fun classifyKind(summary: String): FictionMemoryEntry.Kind {
        val lower = summary.lowercase()
        val placeMarkers = listOf("city", "town", "village", "country", "kingdom",
            "continent", "world", "realm", "planet", "region", "valley",
            "mountain", "river", "island", "ocean", "forest", "tavern", "inn")
        val conceptMarkers = listOf("system", "magic", "art", "philosophy",
            "religion", "language", "ritual", "ability", "power", "concept",
            "technique", "law", "rule")
        return when {
            placeMarkers.any { it in lower } -> FictionMemoryEntry.Kind.PLACE
            conceptMarkers.any { it in lower } -> FictionMemoryEntry.Kind.CONCEPT
            else -> FictionMemoryEntry.Kind.CHARACTER
        }
    }
}
