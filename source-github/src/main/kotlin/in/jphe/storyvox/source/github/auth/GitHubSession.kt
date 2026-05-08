package `in`.jphe.storyvox.source.github.auth

/**
 * Tri-state GitHub session for the Sources → GitHub Settings row + the
 * OkHttp [GitHubAuthInterceptor].
 *
 * Mirrors `:core-data`'s [`in`.jphe.storyvox.data.auth.SessionState] but
 * lives next to the GitHub-specific auth code so the source module
 * doesn't depend on the cross-source RR-shaped contract during P0. A
 * future "PR Auth-A" refactor (per Ember's spec § Multi-source
 * `AuthRepository`) can fold this into the cross-source `SourceAuth`
 * interface without behavioural change.
 */
sealed interface GitHubSession {
    /** No token on disk; calls go out unauthenticated (60 req/hr). */
    data object Anonymous : GitHubSession

    /** Token in memory + on disk. Calls bear `Authorization: Bearer ...`. */
    data class Authenticated(
        val token: String,
        val login: String?,
        val scopes: String,
        val grantedAt: Long,
    ) : GitHubSession

    /**
     * Token rejected by api.github.com (401). The encrypted-prefs copy is
     * left intact so Settings can show "Session expired — sign in again"
     * rather than silently losing the user's identity. Re-auth via
     * [GitHubAuthRepository.captureSession] transitions back to
     * [Authenticated].
     */
    data object Expired : GitHubSession

    /**
     * Public-facing identity surface for `:feature` / `:app` UI without
     * exposing the raw token. Settings only needs login + state; never
     * the token string itself. Mirrors `SessionState`'s shape but
     * scrubs the secret.
     */
    val displayLogin: String?
        get() = (this as? Authenticated)?.login

    val isAuthenticated: Boolean
        get() = this is Authenticated
}
