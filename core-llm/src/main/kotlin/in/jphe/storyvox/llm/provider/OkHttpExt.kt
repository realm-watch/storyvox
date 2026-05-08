package `in`.jphe.storyvox.llm.provider

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** application/json — single source of truth for body POSTs. */
internal val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * Suspend wrapper around an OkHttp Call that returns the [Response].
 * Caller is responsible for closing the response (use Kotlin's
 * `response.use { … }`).
 *
 * Cancellation of the suspending coroutine cancels the underlying
 * [Call] — on a streaming response that sends TCP RST to the
 * provider, so they stop generating + we stop billing characters.
 *
 * Streaming providers consume the body as `response.body!!.source()`
 * inside the calling Flow. That outer Flow is itself suspending +
 * cancellable, so user-cancellation propagates: collector cancel →
 * Flow cancel → response.close → provider sees the abort.
 */
internal suspend fun Call.executeAsync(): Response =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation {
            try { cancel() } catch (_: Throwable) { /* best-effort */ }
        }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) {
                    cont.resume(response)
                } else {
                    // Coroutine was cancelled between request and
                    // response — close the body to release the
                    // connection back to the pool.
                    response.close()
                }
            }
        })
    }
