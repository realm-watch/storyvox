package `in`.jphe.storyvox.data.auth

/**
 * Bridge between the WebView capture path and the per-source OkHttp cookie jar.
 *
 * `:feature` and `:core-data` can't see `:source-royalroad` — the RR cookie jar
 * is internal to that module. This interface lives here so [AuthViewModel]
 * can hand the captured cookie map to whoever owns the live HTTP client.
 *
 * The implementation in `:source-royalroad` hydrates [RoyalRoadCookieJar] for
 * the `royalroad.com` host and clears it on sign-out.
 */
interface SessionHydrator {

    /** Push captured WebView cookies into the live OkHttp jar. */
    fun hydrate(cookies: Map<String, String>)

    /** Drop every cookie from the live OkHttp jar. */
    fun clear()
}
