package `in`.jphe.storyvox.data.source

import `in`.jphe.storyvox.data.source.plugin.SourcePluginRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #472 — runtime URL resolver. Walks every registered plugin's
 * [UrlMatcher] capability, returns ranked [RouteMatch] candidates,
 * lets the repository's `addByUrl` pick the best one (or surface a
 * chooser when several are tied at high confidence).
 *
 * ## Walking order
 *
 * 1. [UrlRouter] regex bank — the established pre-#472 hardcoded
 *    patterns for Royal Road + GitHub. Kept here so the existing
 *    full-test-coverage bank stays authoritative for those two cases.
 *    The resolver wraps each hit in a [RouteMatch] at confidence 1.0.
 * 2. Every plugin that implements [UrlMatcher] — via
 *    [SourcePluginRegistry]. Each matcher's `matchUrl` result is
 *    appended.
 * 3. The collected matches are sorted by confidence descending. Ties
 *    fall back to the registry's declared order (which is alphabetical
 *    by display-name per [SourcePluginRegistry]).
 *
 * The Readability catch-all (`:source-readability`) is a registered
 * plugin like the others — it implements [UrlMatcher] and always
 * returns confidence 0.1 for any plausible HTTP(S) URL, so it
 * naturally falls to the bottom of the list and only "wins" when
 * nothing else claimed the input.
 *
 * ## "No URL is a dead-end"
 *
 * JP's design directive for v1: paste any URL, get a route. The
 * Readability fallback guarantees this for any HTTP(S) URL. URLs that
 * aren't even HTTP(S) (file paths, malformed input) return an empty
 * list and the UI surfaces the unknown branch.
 */
@Singleton
class UrlResolver @Inject constructor(
    private val registry: SourcePluginRegistry,
) {

    /**
     * Returns every plausible route for [url], highest confidence
     * first. Empty list = no backend claimed it (the paste is
     * genuinely unparseable, e.g. an empty string or a non-URL).
     */
    fun resolve(url: String): List<RouteMatch> {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return emptyList()

        val matches = mutableListOf<RouteMatch>()

        // (1) Legacy [UrlRouter] regex bank — Royal Road + GitHub.
        // These backends predate the @SourcePlugin migration of their
        // URL pattern and the well-tested regexes live there; keeping
        // the indirection lets us migrate them piecemeal in a follow-up
        // (their `*Source` classes can implement [UrlMatcher] and we
        // delete the corresponding [UrlRouter] arm in the same PR).
        UrlRouter.route(trimmed)?.let { legacy ->
            matches += RouteMatch(
                sourceId = legacy.sourceId,
                fictionId = legacy.fictionId,
                confidence = 1.0f,
                label = labelForLegacy(legacy.sourceId),
            )
        }

        // (2) Every plugin that implements [UrlMatcher].
        for (descriptor in registry.all) {
            val source = descriptor.source
            if (source !is UrlMatcher) continue
            // Skip duplicates if a source has both legacy regex coverage
            // AND a UrlMatcher implementation (Royal Road / GitHub
            // during the transition). The legacy entry is authoritative.
            if (matches.any { it.sourceId == descriptor.id }) continue
            source.matchUrl(trimmed)?.let { matches += it }
        }

        // (3) Sort. Tie-breaker is insertion order, which equals the
        // registry's display-name sort (see [SourcePluginRegistry.all])
        // because mutableListOf preserves it under stable sort.
        return matches.sortedByDescending { it.confidence }
    }

    /**
     * Convenience: the single best match, or null if nothing claimed
     * the URL.
     */
    fun resolveBest(url: String): RouteMatch? = resolve(url).firstOrNull()

    /** Confident-enough matches that compete for the "chooser modal"
     *  surface. Cuts off at 0.5 to keep the chooser focused on routes
     *  the user is likely actually choosing between — the Readability
     *  catch-all (0.1) never appears here. */
    fun resolveConfidentMatches(url: String): List<RouteMatch> =
        resolve(url).filter { it.confidence >= CHOOSER_CONFIDENCE_THRESHOLD }

    private fun labelForLegacy(sourceId: String): String = when (sourceId) {
        SourceIds.ROYAL_ROAD -> "Royal Road fiction"
        SourceIds.GITHUB -> "GitHub repository"
        else -> sourceId.replaceFirstChar { it.uppercase() }
    }

    private companion object {
        /** Below this confidence, a route doesn't compete for the
         *  chooser modal — it can still win as the single best match
         *  (Readability fallback). */
        const val CHOOSER_CONFIDENCE_THRESHOLD: Float = 0.5f
    }
}
