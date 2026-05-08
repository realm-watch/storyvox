package `in`.jphe.storyvox.source.github.auth

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * OkHttp interceptor that attaches `Authorization: Bearer <token>` to
 * outgoing GitHub API requests when a session is captured.
 *
 * Issue #91. Spec: docs/superpowers/specs/2026-05-08-github-oauth-design.md
 *
 * Properties:
 * - **Anonymous fallback** — if the in-memory session is [GitHubSession.Anonymous]
 *   or [GitHubSession.Expired], the request goes out unauthenticated.
 *   Existing 60 req/hr handling in `GitHubApi.mapResponse` keeps working.
 * - **Host pinning** — the header attaches *only* when `req.url.host ==
 *   api.github.com`. Redirects to `raw.githubusercontent.com` or any
 *   other host see a clean request without the token. Defends against
 *   token leaks via redirect.
 * - **401 → Expired** — when GitHub answers 401, the in-memory session
 *   flips to [GitHubSession.Expired] (disk copy intact, so Settings can
 *   show "Session expired" instead of silently losing identity). The 401
 *   response itself is returned to the caller so `GitHubApi.mapResponse`
 *   classifies it as `HttpError(401, …)`.
 *
 * Construction note: the interceptor reads `auth.sessionState.value`
 * synchronously on every call. That's a `StateFlow` `.value` access — a
 * volatile field read, no suspension, no IO. Cheaper than a SharedPreferences
 * lookup per request, which is why [GitHubAuthRepository] keeps the in-
 * memory copy in the first place.
 */
internal class GitHubAuthInterceptor @Inject constructor(
    private val auth: GitHubAuthRepository,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.host != API_HOST) {
            return chain.proceed(req)
        }
        val session = auth.sessionState.value
        val token = (session as? GitHubSession.Authenticated)?.token
        val outgoing = if (token != null) {
            req.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            req
        }
        val response = chain.proceed(outgoing)
        // 401 only matters if we *sent* a token — an unauthenticated 401
        // from GitHub is technically possible (rate-limit on a private
        // endpoint, etc.) but doesn't represent token expiry on our end.
        if (token != null && response.code == 401) {
            auth.markExpired()
        }
        return response
    }

    private companion object {
        const val API_HOST: String = "api.github.com"
    }
}
