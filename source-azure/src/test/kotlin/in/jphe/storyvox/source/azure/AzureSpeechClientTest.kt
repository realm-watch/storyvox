package `in`.jphe.storyvox.source.azure

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [AzureSpeechClient]. Uses MockWebServer so the
 * tests run hermetic — no real Azure call, no key leak, no flake.
 */
class AzureSpeechClientTest {

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient.Builder()
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun creds(key: String? = "test-key", region: String = "eastus"): AzureCredentials {
        // Map-backed test double so we don't need Robolectric or a
        // real EncryptedSharedPreferences.
        val backing = mutableMapOf<String, String?>()
        if (key != null) backing[AzureCredentials.KEY_AZURE_KEY] = key
        backing[AzureCredentials.KEY_AZURE_REGION] = region
        return object : AzureCredentials() {
            override fun key() = backing[AzureCredentials.KEY_AZURE_KEY]
            override fun regionId() =
                backing[AzureCredentials.KEY_AZURE_REGION] ?: "eastus"
            override fun region() = AzureRegion.byId(regionId()) ?: AzureRegion.DEFAULT
            override val isConfigured: Boolean get() = key() != null
        }
    }

    /** A client that points at MockWebServer instead of
     *  `*.tts.speech.microsoft.com`. PR-5 retry tests also override
     *  [sleepMillis] to a no-op so the 250 + 500ms backoff chain
     *  doesn't slow CI by ~750ms per failing-after-retry test. */
    private fun client(creds: AzureCredentials): AzureSpeechClient =
        object : AzureSpeechClient(http, creds) {
            override fun endpointUrlFor(regionId: String): String =
                server.url("/cognitiveservices/v1").toString()
            override fun voicesListUrlFor(regionId: String): String =
                server.url("/cognitiveservices/voices/list").toString()
            override fun sleepMillis(ms: Long) = Unit
        }

    /** Enqueue [response] N times so [AzureSpeechClient.withRetry]'s
     *  initial-attempt + retries each see a fresh response. PR-5 retry
     *  tests use this when the response is itself a retryable failure
     *  (429 / 5xx) — without it MockWebServer dispatches the single
     *  response to the first request, then closes the connection on
     *  subsequent retries → tests would see NetworkError, not the
     *  expected typed error. */
    private fun MockWebServer.enqueueRepeated(response: MockResponse, times: Int) {
        repeat(times) { enqueue(response) }
    }

    @Test
    fun `synthesize returns response body bytes on 200`() {
        val expected = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(expected)),
        )

        val pcm = client(creds()).synthesize("<speak>hi</speak>")

        assertArrayEquals(expected, pcm)
    }

    @Test
    fun `synthesize sends the subscription key header`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer()))

        client(creds(key = "secret-abc-123")).synthesize("<speak/>")

        val recorded = server.takeRequest()
        assertEquals("secret-abc-123", recorded.getHeader("Ocp-Apim-Subscription-Key"))
        assertEquals(
            "raw-24khz-16bit-mono-pcm",
            recorded.getHeader("X-Microsoft-OutputFormat"),
        )
        // OkHttp appends "; charset=utf-8" to a String body's Content-Type
        // (the SSML body is constructed via String.toRequestBody). Confirm
        // the prefix matches; the charset suffix is fine — Azure accepts
        // it.
        assertTrue(
            "Content-Type starts with application/ssml+xml",
            recorded.getHeader("Content-Type")?.startsWith("application/ssml+xml") == true,
        )
        assertNotNull("user-agent set", recorded.getHeader("User-Agent"))
    }

    @Test
    fun `synthesize POSTs the SSML body verbatim`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer()))
        val ssml = "<speak version=\"1.0\"><voice name=\"x\">hi</voice></speak>"

        client(creds()).synthesize(ssml)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(ssml, recorded.body.readUtf8())
    }

    @Test
    fun `401 throws AuthFailed`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        val c = client(creds())

        val ex = assertThrows(AzureError.AuthFailed::class.java) {
            c.synthesize("<speak/>")
        }
        assertTrue(
            "message mentions HTTP 401",
            ex.message?.contains("401") == true,
        )
    }

    @Test
    fun `403 throws AuthFailed`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
        val c = client(creds())

        assertThrows(AzureError.AuthFailed::class.java) {
            c.synthesize("<speak/>")
        }
    }

    @Test
    fun `429 throws Throttled after retry budget exhausted`() {
        // PR-5 retry: initial attempt + 2 retries = 3 mock responses.
        // All three throttled → typed Throttled escapes withRetry.
        server.enqueueRepeated(
            MockResponse().setResponseCode(429).setBody("Too Many Requests"),
            times = 3,
        )
        val c = client(creds())

        assertThrows(AzureError.Throttled::class.java) {
            c.synthesize("<speak/>")
        }
    }

    @Test
    fun `429 then 200 succeeds via retry`() {
        // PR-5 retry: initial 429 should silently retry, second 429
        // also retries, third response (200) returns the body.
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(Buffer().write(byteArrayOf(0x42))),
        )
        val c = client(creds())

        val pcm = c.synthesize("<speak/>")

        assertArrayEquals(byteArrayOf(0x42), pcm)
        // Confirm 3 requests actually went out (the retries are real).
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `400 throws BadRequest with body excerpt`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("SSML parse failed at line 1"),
        )
        val c = client(creds())

        val ex = assertThrows(AzureError.BadRequest::class.java) {
            c.synthesize("<speak><invalid")
        }
        assertEquals(400, ex.httpCode)
        assertTrue(
            "message includes body excerpt",
            ex.message?.contains("SSML parse failed") == true,
        )
    }

    @Test
    fun `500 throws ServerError after retry budget exhausted`() {
        // PR-5: 3 attempts, all 500 → typed ServerError escapes.
        server.enqueueRepeated(
            MockResponse().setResponseCode(500).setBody("oops"),
            times = 3,
        )
        val c = client(creds())

        val ex = assertThrows(AzureError.ServerError::class.java) {
            c.synthesize("<speak/>")
        }
        assertEquals(500, ex.httpCode)
    }

    @Test
    fun `503 throws ServerError after retry budget exhausted`() {
        server.enqueueRepeated(MockResponse().setResponseCode(503), times = 3)
        val c = client(creds())

        assertThrows(AzureError.ServerError::class.java) {
            c.synthesize("<speak/>")
        }
    }

    @Test
    fun `missing key throws AuthFailed before any HTTP traffic`() {
        // Server enqueues nothing — if the client sends a request,
        // MockWebServer hangs and the test times out instead of
        // surfacing an AuthFailed. Confirms the credential gate.
        val c = client(creds(key = null))

        assertThrows(AzureError.AuthFailed::class.java) {
            c.synthesize("<speak/>")
        }
    }

    @Test
    fun `network failure surfaces as NetworkError`() {
        // Shut the server down before the call so OkHttp gets a
        // ConnectException — not a 5xx.
        server.shutdown()
        val c = client(creds())

        assertThrows(AzureError.NetworkError::class.java) {
            c.synthesize("<speak/>")
        }
    }

    @Test
    fun `region from credentials shapes the endpoint URL`() {
        // Override endpointUrlFor in a way that captures the regionId
        // it was called with — confirms the client reads region from
        // credentials and passes it through, even though the test
        // double redirects the URL.
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer()))
        val capturedRegions = mutableListOf<String>()
        val c = object : AzureSpeechClient(http, creds(region = "westeurope")) {
            override fun endpointUrlFor(regionId: String): String {
                capturedRegions += regionId
                return server.url("/cognitiveservices/v1").toString()
            }
        }

        c.synthesize("<speak/>")

        assertEquals(listOf("westeurope"), capturedRegions)
    }
}
