package `in`.jphe.storyvox.source.ao3.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cookie jar for AO3, keyed by host eTLD+1 (PR2 of #426).
 *
 * Mirrors `RoyalRoadCookieJar` exactly — the cross-source auth
 * scaffold in PR1 generalized the WebView capture path, so each
 * source still owns its own OkHttp jar (one per host avoids cookie
 * leakage across sources). The jar is hydrated from a captured
 * cookie map after WebView sign-in via [hydrate], and cleared on
 * sign-out via [clearAll].
 *
 * AO3 serves only under the bare `archiveofourown.org` host — no
 * `www.` subdomain, no separate static-asset domain that needs the
 * session cookie — so the host key is a single entry. The
 * `_otwarchive_session` cookie + the optional `remember_user_token`
 * end up in the same bucket and attach to every authed GET.
 */
@Singleton
internal class Ao3CookieJar @Inject constructor() : CookieJar {
    private val store = ConcurrentHashMap<String, MutableMap<String, Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val bucket = store.getOrPut(url.topPrivateDomain() ?: url.host) { ConcurrentHashMap() }
        cookies.forEach { c -> bucket[c.name] = c }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val key = url.topPrivateDomain() ?: url.host
        val bucket = store[key] ?: return emptyList()
        val now = System.currentTimeMillis()
        return bucket.values.filter { it.expiresAt > now && it.matches(url) }
    }

    /**
     * Hydrate from a captured cookie set (post WebView sign-in).
     * Cookies are minted as non-expiring (expiresAt = MAX_VALUE
     * via [Cookie.Builder]'s default) and host-only on AO3's
     * eTLD+1 so subsequent OkHttp requests attach them on every
     * authed GET. Same shape RoyalRoadCookieJar uses.
     */
    fun hydrate(host: String, cookies: Map<String, String>) {
        val bucket = store.getOrPut(host) { ConcurrentHashMap() }
        cookies.forEach { (name, value) ->
            bucket[name] = Cookie.Builder()
                .domain(host)
                .name(name)
                .value(value)
                .path("/")
                .secure()
                .build()
        }
    }

    fun clearAll() {
        store.clear()
    }
}
