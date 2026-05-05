package `in`.jphe.storyvox.source.royalroad.net

import `in`.jphe.storyvox.source.royalroad.model.RoyalRoadIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps OkHttp with a process-wide 1-req/sec gate for royalroad.com.
 *
 * Why a single shared gate: parallel chapter prefetch must NOT exceed RR's
 * politeness budget. The mutex serializes the entire request, the delay
 * sleeps the calling coroutine until the next slot opens.
 */
@Singleton
internal class RateLimitedClient @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    private val gate = Mutex()
    private var lastRequestAt: Long = 0L

    suspend fun get(url: String, extraHeaders: Map<String, String> = emptyMap()): Response =
        execute(buildGet(url, extraHeaders))

    suspend fun post(url: String, body: okhttp3.RequestBody, extraHeaders: Map<String, String> = emptyMap()): Response =
        execute(
            Request.Builder()
                .url(url)
                .header("User-Agent", RoyalRoadIds.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "en-US,en;q=0.9")
                .also { extraHeaders.forEach { (k, v) -> it.header(k, v) } }
                .post(body)
                .build()
        )

    private fun buildGet(url: String, extraHeaders: Map<String, String>): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", RoyalRoadIds.USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .also { extraHeaders.forEach { (k, v) -> it.header(k, v) } }
            .get()
            .build()

    private suspend fun execute(req: Request): Response = gate.withLock {
        val now = System.currentTimeMillis()
        val wait = (lastRequestAt + RoyalRoadIds.MIN_REQUEST_INTERVAL_MS) - now
        if (wait > 0) delay(wait)
        lastRequestAt = System.currentTimeMillis()
        withContext(Dispatchers.IO) { httpClient.newCall(req).execute() }
    }
}
