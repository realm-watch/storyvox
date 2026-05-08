package `in`.jphe.storyvox.source.azure

import `in`.jphe.storyvox.source.azure.di.AzureHttp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Errors emitted by [AzureSpeechClient.synthesize]. The PR-5 (Solara's
 * plan) error-handling pass elevates these into the `PlaybackState.error`
 * channel and drives the picker's offline / auth-error UI; for PR-2
 * we just thread the failure shape so `AzureVoiceEngine` and its
 * tests can branch on it.
 */
sealed class AzureError(message: String, cause: Throwable? = null) :
    IOException(message, cause) {

    /** 401 / 403 — bad / revoked subscription key. The Settings flow
     *  invalidates the key and prompts the user to re-paste. */
    class AuthFailed(message: String) : AzureError(message)

    /** 429 — throttled. Retried with backoff inside the client; this
     *  type only escapes after the retry budget is exhausted. */
    class Throttled(message: String) : AzureError(message)

    /** 4xx that isn't 401/403/429 — bad SSML, unsupported voice,
     *  region mismatch. Surfaces as a one-shot toast; not retryable. */
    class BadRequest(val httpCode: Int, message: String) : AzureError(message)

    /** 5xx — Azure-side outage. Retried with backoff; this type
     *  escapes only after retries fail. */
    class ServerError(val httpCode: Int, message: String) : AzureError(message)

    /** TCP / TLS / DNS failure — treated as offline by callers
     *  (fallback path or error state). */
    class NetworkError(cause: Throwable) :
        AzureError(cause.message ?: cause::class.java.simpleName, cause)
}

/**
 * Thin OkHttp wrapper around Azure Speech Services' synthesis
 * endpoint. One operation: POST SSML, return raw PCM bytes.
 *
 * **Endpoint:**
 * `https://{region}.tts.speech.microsoft.com/cognitiveservices/v1`
 *
 * **Auth:** `Ocp-Apim-Subscription-Key: {key}` header. Single header,
 * no token refresh, no signer — the BYOK simplicity Solara's spec
 * cited as the primary reason for picking Azure over GCP/AWS.
 *
 * **Output format:** `raw-24khz-16bit-mono-pcm` per Solara's
 * recommendation in open question #7. 24 kHz matches Kokoro's
 * sample rate so the AudioTrack rebuild on voice swap stays cheap;
 * Azure renders HD voices at 24 kHz natively, so the lower 16 kHz
 * option would just downsample server-side anyway.
 *
 * **Retries are NOT implemented here.** PR-5 in Solara's plan adds
 * the 429/5xx exponential backoff; PR-2 stops at "map the response
 * code to an [AzureError] type". A retrying wrapper around
 * [AzureSpeechClient.synthesize] is the right shape for the retry
 * policy and stays a follow-up PR.
 *
 * **Logging redaction.** The `Ocp-Apim-Subscription-Key` header is
 * redacted by the [HttpLoggingInterceptor] configured in
 * `AzureModule.provideAzureHttp` so even at BODY level no key bytes
 * land in logcat. The class itself never prints — debug logging of
 * the SSML body is fine (chapter text, not secret), but headers are
 * scrubbed.
 *
 * **Body buffering.** Solara's spec picks "Option A — read fully into
 * ByteArray" for v1 (one sentence per request, ~80 KB at 24 kHz mono
 * for ~5 s of audio). Sub-sentence streaming (Option B) is a PR-9
 * follow-up if real-world latency proves problematic. The full
 * sentence in memory drops cleanly into the existing `PcmAppender`
 * pipeline shape that Piper/Kokoro already use.
 */
@Singleton
open class AzureSpeechClient @Inject constructor(
    @AzureHttp private val http: OkHttpClient,
    private val credentials: AzureCredentials,
) {
    /** Open for tests — MockWebServer overrides this to point at a
     *  local URL. Production callers leave it at the default. */
    protected open fun endpointUrlFor(regionId: String): String =
        "https://$regionId.tts.speech.microsoft.com/cognitiveservices/v1"

    /**
     * POST [ssml] to Azure, return the raw PCM body.
     *
     * @throws AzureError.AuthFailed   on 401 / 403 (bad key)
     * @throws AzureError.Throttled    on 429
     * @throws AzureError.BadRequest   on other 4xx (bad SSML, etc.)
     * @throws AzureError.ServerError  on 5xx
     * @throws AzureError.NetworkError on TCP / TLS / DNS failure
     */
    open fun synthesize(ssml: String): ByteArray {
        val key = credentials.key()
            ?: throw AzureError.AuthFailed("No Azure subscription key configured")
        val regionId = credentials.regionId()

        val request = Request.Builder()
            .url(endpointUrlFor(regionId))
            .header(HEADER_KEY, key)
            .header(HEADER_OUTPUT_FORMAT, OUTPUT_FORMAT_PCM_24KHZ)
            .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_SSML)
            .header(HEADER_USER_AGENT, USER_AGENT)
            .post(ssml.toRequestBody(SSML_MEDIA_TYPE))
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw AzureError.NetworkError(e)
        }

        return response.use { resp ->
            when {
                resp.isSuccessful -> {
                    // The whole sentence body comes back as one chunked
                    // response. Reading it fully here matches the
                    // existing engine-handle contract (return ByteArray).
                    // For ~5 s of audio at 24 kHz mono that's ~240 KB —
                    // bounded, fits in a single RequestBody, no need to
                    // page through chunks.
                    resp.body?.bytes() ?: ByteArray(0)
                }
                resp.code == 401 || resp.code == 403 -> {
                    throw AzureError.AuthFailed(
                        "Azure rejected key (HTTP ${resp.code}): " +
                            (resp.message.takeIf { it.isNotBlank() }
                                ?: "authentication failed"),
                    )
                }
                resp.code == 429 -> {
                    throw AzureError.Throttled(
                        "Azure throttled the request (HTTP 429). " +
                            "Free-tier quota exhausted, or burst limit hit.",
                    )
                }
                resp.code in 400..499 -> {
                    val excerpt = resp.body?.string()?.take(256) ?: resp.message
                    throw AzureError.BadRequest(
                        resp.code,
                        "Azure rejected request (HTTP ${resp.code}): $excerpt",
                    )
                }
                resp.code in 500..599 -> {
                    throw AzureError.ServerError(
                        resp.code,
                        "Azure server error (HTTP ${resp.code}): " +
                            (resp.message.takeIf { it.isNotBlank() } ?: "unknown"),
                    )
                }
                else -> {
                    throw AzureError.ServerError(
                        resp.code,
                        "Unexpected HTTP ${resp.code} from Azure",
                    )
                }
            }
        }
    }

    companion object {
        internal const val HEADER_KEY = "Ocp-Apim-Subscription-Key"
        internal const val HEADER_OUTPUT_FORMAT = "X-Microsoft-OutputFormat"
        internal const val HEADER_CONTENT_TYPE = "Content-Type"
        internal const val HEADER_USER_AGENT = "User-Agent"

        /** 24 kHz mono 16-bit PCM. Matches Kokoro's native sample rate
         *  so AudioTrack rebuilds on voice swap stay cheap. Per Solara's
         *  open-question #7 recommendation. */
        internal const val OUTPUT_FORMAT_PCM_24KHZ = "raw-24khz-16bit-mono-pcm"

        /** Sample rate of the PCM bytes returned by [synthesize]. The
         *  engine handle reports this to AudioTrack so playback runs
         *  at the right speed. */
        const val SAMPLE_RATE_HZ: Int = 24_000

        internal const val CONTENT_TYPE_SSML = "application/ssml+xml"
        internal val SSML_MEDIA_TYPE = "application/ssml+xml".toMediaType()

        /** Per Azure docs, a meaningful User-Agent helps with their
         *  service-side debugging when a request misbehaves. The
         *  version suffix is filled by Hilt at runtime; tests use
         *  the literal string. */
        internal const val USER_AGENT = "storyvox/azure-tts"
    }
}
