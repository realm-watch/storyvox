package `in`.jphe.storyvox.source.wikipedia.config

import kotlinx.coroutines.flow.Flow

/**
 * Issue #377 — abstraction over the Wikipedia source's persistent
 * config. Today only the language code (which Wikipedia host the
 * source talks to: `en.wikipedia.org`, `de.wikipedia.org`, etc.).
 *
 * Implementation lives in :app on top of DataStore so the source
 * module stays free of Android Preferences plumbing — same pattern
 * as [`in`.jphe.storyvox.source.outline.config.OutlineConfig].
 *
 * Wikipedia is read-only and public — no auth token here. The
 * language code is the one knob worth surfacing to the user.
 */
interface WikipediaConfig {
    /** Hot stream of the current config state. */
    val state: Flow<WikipediaConfigState>

    /** Synchronous snapshot for code paths that can't suspend. */
    suspend fun current(): WikipediaConfigState
}

/**
 * One Wikipedia config state. [languageCode] is a Wikipedia subdomain
 * prefix — `en`, `de`, `ja`, `simple`, etc. Defaults to `en` (English
 * Wikipedia) for first-launch.
 *
 * The MediaWiki opensearch / REST endpoints all live at
 * `<lang>.wikipedia.org/...`, so this single field picks the host.
 * Blank / whitespace fall back to `en` so a bad value never bricks
 * the source.
 */
data class WikipediaConfigState(
    val languageCode: String = DEFAULT_LANGUAGE_CODE,
) {
    /** Canonical Wikipedia host for this language. Always
     *  `https://<lang>.wikipedia.org`. */
    val baseUrl: String
        get() {
            val raw = languageCode.trim().lowercase()
            val lang = if (raw.isBlank()) DEFAULT_LANGUAGE_CODE else raw
            return "https://$lang.wikipedia.org"
        }

    companion object {
        const val DEFAULT_LANGUAGE_CODE: String = "en"
    }
}
