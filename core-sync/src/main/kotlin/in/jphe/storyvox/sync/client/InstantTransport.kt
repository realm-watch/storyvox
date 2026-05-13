package `in`.jphe.storyvox.sync.client

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * Seam for HTTP traffic — the production wiring is OkHttp; tests substitute
 * a fake.
 *
 * Why an interface and not just OkHttp: the magic-code happy path is so
 * thin (4 endpoints, all POST JSON, no streaming) that decoupling lets unit
 * tests cover the InstantClient logic without spinning a MockWebServer
 * thread pool. The production class is a one-liner around OkHttp.
 */
interface InstantHttpTransport {
    /** Returns the response body as a string. Throws [IOException] on
     *  network failure; returns the body even on non-2xx so callers can
     *  surface server-side error messages. */
    fun postJson(url: String, jsonBody: String): TransportResult
}

data class TransportResult(
    val code: Int,
    val body: String,
) {
    val isSuccessful: Boolean get() = code in 200..299
}

/** Production transport. Reuses a single [OkHttpClient] for connection pooling. */
class OkHttpInstantTransport(
    private val client: OkHttpClient = defaultClient(),
) : InstantHttpTransport {

    override fun postJson(url: String, jsonBody: String): TransportResult {
        val req = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA))
            .build()
        return try {
            client.newCall(req).execute().use { resp: Response ->
                TransportResult(
                    code = resp.code,
                    body = resp.body?.string().orEmpty(),
                )
            }
        } catch (e: IOException) {
            // Wrap as a synthetic "0" code so callers can branch on
            // transient-network with the same shape as a real error body.
            TransportResult(code = 0, body = e.message ?: "network failure")
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .build()
    }
}
