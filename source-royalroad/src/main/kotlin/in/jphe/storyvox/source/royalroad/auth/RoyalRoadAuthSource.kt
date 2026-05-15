package `in`.jphe.storyvox.source.royalroad.auth

import `in`.jphe.storyvox.data.auth.AuthSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.royalroad.model.RoyalRoadIds
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Royal Road's contribution to the cross-source [AuthSource] map (#426).
 *
 * The WebView capture configuration here mirrors the long-standing
 * literals in [RoyalRoadAuthWebView]: load `/account/login`, watch for
 * `.AspNetCore.Identity.Application`, hydrate the captured cookies into
 * the OkHttp jar at the `royalroad.com` eTLD+1.
 *
 * `:source-royalroad`'s [RoyalRoadAuthWebView] composable is what
 * actually drives the WebView today and the literals live there as
 * private constants; this class re-exposes the same values through
 * the [AuthSource] interface so PR2's AO3 sign-in screen — and any
 * future cross-source sign-in router — can pick the right configuration
 * by sourceId rather than referencing a source's internal composable.
 */
@Singleton
internal class RoyalRoadAuthSource @Inject constructor() : AuthSource {

    override val sourceId: String = SourceIds.ROYAL_ROAD

    override val signInUrl: String = "${RoyalRoadIds.BASE_URL}/account/login"

    /**
     * Royal Road runs on ASP.NET Core; the identity-cookie name is the
     * default `.AspNetCore.Identity.Application` and persists for as
     * long as the user stays signed in. Matches the literal in
     * [RoyalRoadAuthWebView].
     */
    override val identityCookieName: String = ".AspNetCore.Identity.Application"

    /**
     * eTLD+1 host the [RoyalRoadSessionHydrator] keys cookies under.
     * Royal Road serves from both `www.royalroad.com` and the bare
     * domain; the cookie jar stores under the bare domain so OkHttp's
     * Set-Cookie attachment routes both back to the server.
     */
    override val cookieHost: String = "royalroad.com"
}
