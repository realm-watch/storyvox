package `in`.jphe.storyvox.source.ao3.auth

import `in`.jphe.storyvox.data.auth.AuthSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.ao3.net.Ao3Api
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AO3's contribution to the cross-source [AuthSource] map (#426 PR2).
 *
 * AO3 (Archive of Our Own) runs on Ruby on Rails; the identity-cookie
 * name is `_otwarchive_session`, the standard Rails session cookie
 * named after the application (the Organization for Transformative
 * Works' OTW Archive codebase). Once that cookie appears in the
 * WebView's jar after the user submits the login form, capture every
 * cookie on `archiveofourown.org` and hand them to the OkHttp jar so
 * authed requests (subscriptions, Marked for Later) attach them
 * automatically.
 *
 * Mirrors [RoyalRoadAuthSource][in.jphe.storyvox.source.royalroad.auth.RoyalRoadAuthSource]:
 *  - [signInUrl] is the AO3 user-login form.
 *  - [identityCookieName] is the Rails session cookie name.
 *  - [cookieHost] is the bare `archiveofourown.org` eTLD+1 the
 *    [Ao3CookieJar][in.jphe.storyvox.source.ao3.net.Ao3CookieJar]
 *    keys cookies under.
 *
 * AO3 does not use Cloudflare-style `__cf_bm` cookies (the Archive
 * runs its own infrastructure), so the capture set is just the
 * Rails session + the `remember_user_token` AO3 issues when the
 * user ticks "Remember me" on the form. Both are harvested without
 * special-casing by the [Ao3AuthWebView]'s "any cookie on this host"
 * sweep.
 */
@Singleton
internal class Ao3AuthSource @Inject constructor() : AuthSource {

    override val sourceId: String = SourceIds.AO3

    /**
     * AO3's login form. POST target is `/users/login` (same URL,
     * different method) — the form on this page submits to itself
     * then redirects to the user's home page. Storyvox never sees
     * the password; the WebView submits the form, AO3 sets the
     * session cookie, and our capture path harvests it from the
     * cookie store.
     */
    override val signInUrl: String = "${Ao3Api.BASE_URL}/users/login"

    /**
     * AO3 = Rails; the session cookie is `_otwarchive_session` —
     * "otwarchive" being the upstream Rails app name (the OTW
     * Archive open-source codebase the Archive of Our Own is built
     * from). Setting persists for the browser session by default;
     * with "Remember me" ticked AO3 additionally sets
     * `remember_user_token` which keeps the session alive across
     * cookie-store restarts (we capture that one too — see
     * [Ao3AuthWebView]'s catch-all sweep).
     */
    override val identityCookieName: String = "_otwarchive_session"

    /**
     * eTLD+1 host the [Ao3CookieJar][in.jphe.storyvox.source.ao3.net.Ao3CookieJar]
     * keys cookies under. AO3 only serves under the bare domain
     * (no `www.` subdomain), so this matches exactly what OkHttp
     * sees on outgoing requests.
     */
    override val cookieHost: String = "archiveofourown.org"
}
