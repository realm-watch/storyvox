package `in`.jphe.storyvox.source.outline.config

import kotlinx.coroutines.flow.Flow

/**
 * Issue #245 — abstraction over the Outline source's persistent
 * config (host + API token). Implementation lives in :app because
 * the API token persists in EncryptedSharedPreferences alongside the
 * other source secrets; this interface keeps the source module free
 * of Android Preferences plumbing.
 */
interface OutlineConfig {
    /** Hot stream of the current config state. */
    val state: Flow<OutlineConfigState>

    /** Synchronous snapshot for code paths that can't suspend. */
    suspend fun current(): OutlineConfigState
}

/**
 * One Outline config state. Empty [host] disables the source —
 * Browse → Outline shows the empty state with a CTA back to Settings.
 *
 * [host] is the Outline instance URL (e.g. `outline.example.com` or
 * `https://wiki.mycompany.com`). Trailing slash is fine; the API
 * client trims it.
 *
 * [apiKey] is the API token from Outline's Account → API Tokens.
 * Plaintext in this state object — the disk cipher is at the
 * encrypted SharedPreferences layer, not at this seam.
 */
data class OutlineConfigState(
    val host: String = "",
    val apiKey: String = "",
) {
    val isConfigured: Boolean get() = host.isNotBlank() && apiKey.isNotBlank()

    /** Canonical base URL for the Outline instance. Always
     *  `https://...` (Outline doesn't run plaintext over the public
     *  internet). Trims trailing slash. */
    val baseUrl: String
        get() {
            val h = host.trim().trimEnd('/')
            return when {
                h.startsWith("http://") || h.startsWith("https://") -> h
                else -> "https://$h"
            }
        }
}
