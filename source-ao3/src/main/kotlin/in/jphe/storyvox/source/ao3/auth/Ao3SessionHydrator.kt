package `in`.jphe.storyvox.source.ao3.auth

import `in`.jphe.storyvox.data.auth.SessionHydrator
import `in`.jphe.storyvox.source.ao3.net.Ao3CookieJar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hydrates the per-source OkHttp [Ao3CookieJar] from a WebView-captured
 * cookie map (PR2 of #426).
 *
 * Mirrors [RoyalRoadSessionHydrator][in.jphe.storyvox.source.royalroad.auth.RoyalRoadSessionHydrator]:
 *
 *  - Stores under the AO3 eTLD+1 `archiveofourown.org`. AO3 only
 *    serves under the bare domain (no `www.` subdomain that needs
 *    its own bucket), so this is a single-host write.
 *  - Subsequent OkHttp requests to AO3 attach the captured
 *    `_otwarchive_session` (+ optional `remember_user_token`)
 *    cookie automatically — the authed [Ao3Api][in.jphe.storyvox.source.ao3.net.Ao3Api]
 *    surfaces (subscriptions, Marked for Later) immediately reach
 *    their gated endpoints without restarting the app.
 *
 * Bound into the cross-source `Map<String, SessionHydrator>` keyed by
 * [SourceIds.AO3][in.jphe.storyvox.data.source.SourceIds.AO3] in
 * [`Ao3Module`][in.jphe.storyvox.source.ao3.di.Ao3Bindings]. The
 * AuthViewModel's `captureCookies(sourceId, ...)` looks it up by
 * sourceId.
 */
@Singleton
internal class Ao3SessionHydrator @Inject constructor(
    private val cookieJar: Ao3CookieJar,
) : SessionHydrator {

    override fun hydrate(cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        cookieJar.hydrate(host = HOST, cookies = cookies)
    }

    override fun clear() {
        cookieJar.clearAll()
    }

    private companion object {
        // Ao3CookieJar keys by topPrivateDomain — match it.
        const val HOST = "archiveofourown.org"
    }
}
