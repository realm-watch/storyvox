package `in`.jphe.storyvox.source.royalroad.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cookie jar keyed by host. The host (auth) layer hydrates this
 * from EncryptedSharedPreferences after WebView login and clears it on logout.
 *
 * We persist whole-cookie strings (name=value) and re-hydrate them as
 * non-expiring cookies for the royalroad.com host. Cloudflare cookies
 * (__cf_bm, cf_clearance) and the AspNetCore identity cookie all just work.
 */
@Singleton
internal class RoyalRoadCookieJar @Inject constructor() : CookieJar {
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

    /** Hydrate from a captured cookie set (e.g. WebView post-login). */
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
