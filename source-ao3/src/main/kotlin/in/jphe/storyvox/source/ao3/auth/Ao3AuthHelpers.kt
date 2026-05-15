package `in`.jphe.storyvox.source.ao3.auth

/**
 * Pure-Kotlin helpers extracted from [Ao3AuthWebView] (#426 PR2) so
 * they can be unit-tested on plain JUnit without touching the Android
 * WebView surface.
 */

/**
 * Magic-prefix key the AO3 WebView capture path uses to thread the
 * username through the same `Map<String, String>` channel as the
 * actual cookies. The prefix `__storyvox_user` is namespaced so a
 * real AO3 cookie can never collide with it (AO3 cookie names don't
 * start with `__storyvox`); the AuthViewModel + downstream source
 * code pluck it out before pushing the rest into the OkHttp jar.
 *
 * Mirrored in `:feature`'s
 * [`in.jphe.storyvox.feature.auth.USERNAME_KEY`] — they MUST agree
 * on the literal string.
 */
const val USERNAME_KEY: String = "__storyvox_user"

/**
 * Extract the AO3 username from a URL like
 * `https://archiveofourown.org/users/<username>` or
 * `https://archiveofourown.org/users/<username>/pseuds/<pseud>`.
 *
 * Returns null on a non-`/users/` URL (e.g. the `/users/login` form
 * itself) or on a URL with no following path segment. The username
 * is the AO3 stable login id, not the displayed pseud — both AO3's
 * subscriptions and Marked-for-Later endpoints key off the login
 * id, so this is what we want to capture.
 *
 * Reserved tokens (`login`, `logout`, `password`, etc.) are rejected
 * — AO3 uses them under `/users/<token>` for its own auth surfaces
 * and they should never be persisted as a username.
 */
internal fun extractUsernameFromUrl(url: String?): String? {
    if (url == null) return null
    val path = url.substringAfter("archiveofourown.org", url)
    val tail = path.substringAfter("/users/", missingDelimiterValue = "")
    if (tail.isEmpty()) return null
    // First path segment after /users/. Reject reserved tokens that
    // AO3 also uses under /users/<token> (e.g. /users/login, /users/logout).
    val segment = tail.substringBefore('/').substringBefore('?')
    if (segment.isBlank()) return null
    if (segment.lowercase() in RESERVED_USER_SEGMENTS) return null
    return segment
}

private val RESERVED_USER_SEGMENTS: Set<String> = setOf(
    "login", "logout", "password", "passwords", "activate", "activation",
    "new", "sign_up", "sign_in", "confirmation",
)
