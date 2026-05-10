package `in`.jphe.storyvox.ui.component

/**
 * Maps a raw exception string to user-facing copy that doesn't leak the
 * shape of the underlying network stack.
 *
 * Pre-#171: error states across FictionDetail, Browse, and source-mempalace
 * surfaced strings like `HTTP 0: Network timeout: timeout` and
 * `java.io.IOException: Palace host not configured` directly to the
 * user. The technical strings are useful for bug reports but read as
 * scary nonsense to a reader who just wanted to listen to a chapter.
 *
 * Call sites should pass the raw message through this function before
 * setting it on [ErrorBlock]. The mapping is intentionally lenient —
 * we'd rather show one generic "the realm is unreachable" than ten
 * specific copies that drift apart over time. Capture the technical
 * detail in logcat for bug reports; keep the user-facing copy short
 * and recoverable-sounding.
 *
 * Returns the raw message unchanged when no known pattern matches.
 */
fun friendlyErrorMessage(raw: String?): String {
    val s = raw ?: return "Something went wrong. Try again in a moment."
    val lower = s.lowercase()
    return when {
        // Network timeouts / offline / DNS — by far the most common.
        // "HTTP 0:" is OkHttp's shape when the connection never opened.
        s.startsWith("HTTP 0") ||
        lower.contains("network timeout") ||
        lower.contains("sockettimeout") ||
        lower.contains("unknownhostexception") ||
        lower.contains("connection refused") ||
        lower.contains("failed to connect") ->
            "Connection lost. Check your network and try again."

        // Cloudflare or similar bot-challenge — we couldn't transparently
        // solve a challenge. User can retry; sometimes resolves on its own.
        lower.contains("cloudflare") ||
        lower.contains("challenge") ->
            "Royal Road asked us to wait. Try again in a moment."

        // Rate-limited (our own gate or the server's 429).
        lower.contains("rate limited") ||
        lower.contains("too many requests") ||
        lower.contains("retry after") ->
            "We're sending requests too fast — slow down and try again."

        // Server errors. Don't blame the user.
        s.contains("HTTP 5") || lower.contains("server error") ->
            "The realm is having trouble. Try again in a moment."

        // Auth — but only for fetch contexts (playback has its own typed
        // PlaybackError.AzureAuthFailed path).
        s.contains("HTTP 401") || s.contains("HTTP 403") ||
        lower.contains("authentication") ->
            "We couldn't sign in. Re-paste your credentials in Settings."

        // Specific config gaps surfaced as IOException — Memory Palace
        // is the canonical example (PalaceDaemonApi throws on empty host).
        lower.contains("host not configured") ||
        lower.contains("not configured") ->
            "This source isn't set up yet. Open Settings to configure it."

        // Other 4xx — usually means something the user can't fix.
        s.contains("HTTP 4") ->
            "The realm returned an unexpected response. Try again later."

        // Fall back to the raw string. Either the source layer already
        // produced user-friendly copy, or we don't have a mapping yet.
        // Better to show something specific than to swallow detail.
        else -> s
    }
}
