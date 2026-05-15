package `in`.jphe.storyvox.data.auth

/**
 * Declares that a [`in`.jphe.storyvox.data.source.FictionSource] supports
 * WebView-based sign-in (#426).
 *
 * Source modules contribute an [AuthSource] to the Hilt graph via
 *
 * ```kotlin
 * @Binds
 * @IntoMap
 * @StringKey(SourceIds.ROYAL_ROAD)
 * abstract fun bindAuthSource(impl: RoyalRoadAuthSource): AuthSource
 * ```
 *
 * `:core-data`'s [`in`.jphe.storyvox.data.repository.AuthRepository] consumes
 * the resulting `Map<String, AuthSource>` to look up per-source WebView
 * configuration (sign-in URL, identity-cookie name, cookie host) when
 * [`in`.jphe.storyvox.feature.auth.AuthViewModel] captures a session.
 *
 * Today Royal Road is the only contributor. PR2 of #426 adds AO3; the
 * same shape will host any future source (GitHub PAT-equivalent flows
 * keep their own auth path via [`in`.jphe.storyvox.source.github.auth.GitHubAuthRepository]
 * for now — the WebView cookie-capture model in [AuthSource] doesn't
 * fit token-pasting flows).
 *
 * Lives in `:core-data` (not `:feature`) so the data layer can route
 * by sourceId without taking a hard dep on any source module. The
 * source module owns the actual implementation; `:core-data` only sees
 * this interface.
 */
interface AuthSource {

    /**
     * Stable sourceId this auth provider attaches to — matches the
     * corresponding [`in`.jphe.storyvox.data.source.FictionSource.id]
     * and the Hilt `@StringKey` used at the binding site.
     */
    val sourceId: String

    /**
     * URL the WebView should load to start the sign-in flow. The
     * source-owned WebView host watches navigation and harvests
     * cookies once [identityCookieName] appears in the host's
     * cookie jar.
     */
    val signInUrl: String

    /**
     * Name of the cookie whose presence signals "login complete".
     * Royal Road uses `.AspNetCore.Identity.Application`; AO3 uses
     * `_otwarchive_session` (PR2 wires this).
     *
     * Used by the WebView capture path to decide when to deliver
     * the captured cookie map back to [`in`.jphe.storyvox.feature.auth.AuthViewModel].
     */
    val identityCookieName: String

    /**
     * Host (eTLD+1, e.g. `royalroad.com`) under which to store
     * captured cookies in the source's OkHttp [`in`.jphe.storyvox.data.auth.SessionHydrator].
     * The hydrator implementation owns the canonical host string —
     * exposing it here so future cross-source dispatch (e.g. choosing
     * which hydrator to call by sourceId) doesn't need to reach into
     * each source's internals.
     */
    val cookieHost: String
}
