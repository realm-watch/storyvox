package `in`.jphe.storyvox.source.royalroad.auth

import `in`.jphe.storyvox.data.auth.SessionHydrator
import `in`.jphe.storyvox.source.royalroad.net.RoyalRoadCookieJar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hydrates the per-source OkHttp [RoyalRoadCookieJar] from a WebView-captured
 * cookie map. Cookies are stored under the eTLD+1 `royalroad.com`, so every
 * subsequent OkHttp request to `www.royalroad.com` (or any subdomain) attaches
 * the captured `.AspNetCore.Identity.Application`, ARRAffinity, and Cloudflare
 * cookies automatically.
 */
@Singleton
internal class RoyalRoadSessionHydrator @Inject constructor(
    private val cookieJar: RoyalRoadCookieJar,
) : SessionHydrator {

    override fun hydrate(cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        cookieJar.hydrate(host = HOST, cookies = cookies)
    }

    override fun clear() {
        cookieJar.clearAll()
    }

    private companion object {
        // RoyalRoadCookieJar keys by topPrivateDomain — match it.
        const val HOST = "royalroad.com"
    }
}
