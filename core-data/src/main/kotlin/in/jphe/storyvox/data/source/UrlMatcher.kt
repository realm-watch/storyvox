package `in`.jphe.storyvox.data.source

/**
 * Issue #472 — capability interface for backends that can claim a URL.
 *
 * A `FictionSource` implements [UrlMatcher] when it knows how to recognise
 * a URL it could fetch. The magic-link paste-anything flow (see
 * [UrlResolver]) walks every registered plugin, asks each one to claim
 * the input, and picks the highest-confidence match. Sources that don't
 * have meaningful URL semantics (Azure TTS, RSS local subscriptions,
 * EPUB-via-SAF) simply don't implement this interface — there's no
 * default no-op `matchUrl` on `FictionSource` itself, by deliberate
 * design, so the interface stays focused on the read-side surface.
 *
 * ## Confidence ranking
 *
 * Implementations return a [RouteMatch] with a `confidence` in `[0.0,
 * 1.0]`. The resolver sorts matches descending; ties are broken in the
 * registry's declared order. Use these tiers as a guide:
 *
 *  - **1.0** — host + path matches a canonical fiction URL exactly
 *    (e.g. `royalroad.com/fiction/{id}`).
 *  - **0.8** — host matches and path looks plausible but isn't unique
 *    (e.g. a wiki article that could also be an RSS feed root).
 *  - **0.5** — host-only match (e.g. `notion.so` without a workspace
 *    token configured); the user may still want this route, but the
 *    backend can't fully resolve it without more setup.
 *  - **0.1** — Readability catch-all. Always loses to any other match.
 *
 * ## Stateless
 *
 * `matchUrl` must be pure. Implementations parse the input string and
 * return — no network calls, no DB access. The resolver may invoke
 * `matchUrl` on every paste-debounce tick (300ms while the user types)
 * and must remain cheap.
 *
 * @see UrlResolver
 * @see RouteMatch
 */
interface UrlMatcher {
    /**
     * Inspect [url] and return a [RouteMatch] when this backend can
     * handle it, or `null` when it can't. Implementations should accept
     * canonical, mobile, and chapter / sub-page URL variants that all
     * resolve to the same fiction.
     */
    fun matchUrl(url: String): RouteMatch?
}

/**
 * A single backend's claim on a pasted URL. Carries enough information
 * to (a) rank against competing matches, (b) hand to the repository's
 * `addByUrl` so it can route the detail fetch.
 *
 * @property sourceId The plugin's `id` (matches [FictionSource.id] and
 *  [`SourceIds`] constants).
 * @property fictionId The backend-specific fiction id the resolver
 *  hands to `FictionSource.fictionDetail`. Same shape the source uses
 *  internally — e.g. `"12345"` for Royal Road, `"github:owner/repo"`
 *  for GitHub, `"hackernews:42"` for Hacker News, `"readability:<hash>"`
 *  for the Readability catch-all.
 * @property confidence `[0.0, 1.0]` — see kdoc on [UrlMatcher] for the
 *  tiering convention.
 * @property label Human-readable name shown in the chooser modal when
 *  multiple backends claim the same URL — usually the source's
 *  displayName + a short qualifier ("Royal Road fiction", "GitHub
 *  repo").
 */
data class RouteMatch(
    val sourceId: String,
    val fictionId: String,
    val confidence: Float,
    val label: String,
)
