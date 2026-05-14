package `in`.jphe.storyvox.llm.provider

import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmError
import `in`.jphe.storyvox.llm.LlmMessage
import `in`.jphe.storyvox.llm.ProbeResult
import `in`.jphe.storyvox.llm.ProviderId
import `in`.jphe.storyvox.llm.auth.GoogleOAuthTokenSource
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * Issue #219 — exercises the SA-JSON branch of [VertexProvider].
 * Separated from the main [VertexProviderTest] because this branch
 * uses `android.util.Base64` (via [GoogleJwtSigner]), which requires
 * Robolectric to back the Android stub at JVM-test time.
 *
 * The mock server doubles as both `oauth2.googleapis.com/token`
 * (handled at `/token`) and `generativelanguage.googleapis.com`
 * (everything else). We rewrite the SA fixture's `token_uri` to
 * point at the mock so the production parser still accepts it.
 */
@RunWith(RobolectricTestRunner::class)
class VertexProviderServiceAccountTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var http: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
    }

    @After fun tearDown() { server.shutdown() }

    @Test
    fun `stream uses Authorization Bearer when SA JSON is configured`() = runTest {
        // First request: the token exchange. Second: the SSE stream.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"ya29.sa-token","expires_in":3600,"token_type":"Bearer"}""")
                .setResponseCode(200),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"hello"}]}}]}""" + "\n",
                )
                .setResponseCode(200),
        )
        val provider = providerWithSaJson()

        val tokens = provider.stream(
            messages = listOf(LlmMessage(LlmMessage.Role.user, "hi")),
        ).toList()
        assertEquals(listOf("hello"), tokens)

        // First HTTP request: token exchange. Verifies the JWT body.
        val tokenReq = server.takeRequest()
        assertEquals("/token", tokenReq.path)
        val tokenBody = tokenReq.body.readUtf8()
        assertTrue(tokenBody.contains("grant_type="))
        assertTrue(tokenBody.contains("assertion="))

        // Second HTTP request: the actual streamGenerateContent. Must
        // carry Bearer auth, NOT a key= query parameter.
        val streamReq = server.takeRequest()
        assertEquals("Bearer ya29.sa-token", streamReq.getHeader("Authorization"))
        assertTrue(
            "Stream path was ${streamReq.path}",
            streamReq.path?.startsWith(
                "/v1beta/models/gemini-2.5-flash:streamGenerateContent",
            ) == true,
        )
        assertNull(
            "key= query param must be absent in SA mode",
            streamReq.requestUrl?.queryParameter("key"),
        )
        assertEquals(
            "alt=sse should still be present",
            "sse",
            streamReq.requestUrl?.queryParameter("alt"),
        )
    }

    @Test
    fun `SA wins over API key when both are configured`() = runTest {
        // Two-mode misconfiguration: both vertex_key AND sa_json on
        // disk. The provider must pick SA — that's the stronger
        // credential and the most recent action.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"ya29.sa-wins","expires_in":3600,"token_type":"Bearer"}""")
                .setResponseCode(200),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"candidates\":[]}\n")
                .setResponseCode(200),
        )
        val provider = providerWithSaJson(apiKey = "should-not-be-used")

        provider.stream(listOf(LlmMessage(LlmMessage.Role.user, "x"))).toList()
        server.takeRequest()  // discard token exchange
        val streamReq = server.takeRequest()
        assertEquals("Bearer ya29.sa-wins", streamReq.getHeader("Authorization"))
        assertNull(streamReq.requestUrl?.queryParameter("key"))
    }

    @Test
    fun `probe uses Bearer auth in SA mode`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"ya29.probe","expires_in":3600,"token_type":"Bearer"}""")
                .setResponseCode(200),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"models":[]}"""),
        )
        val result = providerWithSaJson().probe()
        assertEquals(ProbeResult.Ok, result)

        server.takeRequest()  // discard token exchange
        val probeReq = server.takeRequest()
        assertEquals("GET", probeReq.method)
        assertNotNull(probeReq.getHeader("Authorization"))
        assertNull(probeReq.requestUrl?.queryParameter("key"))
    }

    @Test
    fun `probe surfaces 403 with a service-account-flavoured AuthError message`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"ya29.probe","expires_in":3600,"token_type":"Bearer"}""")
                .setResponseCode(200),
        )
        server.enqueue(MockResponse().setResponseCode(403))
        val result = providerWithSaJson().probe()
        assertTrue(result is ProbeResult.AuthError)
        // Hint should mention IAM/Vertex AI User so a user with a fresh
        // SA who forgot to grant roles knows where to look.
        assertTrue(
            (result as ProbeResult.AuthError).message.contains("IAM") ||
                result.message.contains("Vertex AI User"),
        )
    }

    @Test
    fun `probe surfaces malformed SA JSON as Misconfigured`() = runTest {
        val provider = object : VertexProvider(
            http = http,
            store = FakeStore(vertexSaJson = "not json at all"),
            configFlow = flowOf(LlmConfig(vertexModel = "gemini-2.5-flash")),
            json = json,
            tokenSource = GoogleOAuthTokenSource(http),
        ) {
            override val baseUrl: String = server.url("/").toString()
        }
        val result = provider.probe()
        assertTrue(
            "Expected Misconfigured, got $result",
            result is ProbeResult.Misconfigured,
        )
    }

    @Test
    fun `token exchange failure surfaces as AuthFailed`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"invalid_grant"}""")
                .setResponseCode(401),
        )
        val ex = assertThrows(LlmError.AuthFailed::class.java) {
            runBlocking {
                providerWithSaJson().stream(
                    listOf(LlmMessage(LlmMessage.Role.user, "x")),
                ).toList()
            }
        }
        assertEquals(ProviderId.Vertex, ex.provider)
    }

    /** Build a VertexProvider whose `token_uri` rewrites to the
     *  MockWebServer's `/token` path and whose base URL is also the
     *  mock — so we can intercept BOTH the OAuth exchange AND the
     *  stream call with a single server. */
    private fun providerWithSaJson(apiKey: String? = null): VertexProvider {
        val raw = javaClass.classLoader!!.getResource("test-sa.json")!!.readText()
        val rewritten = raw.replace(
            "\"https://oauth2.googleapis.com/token\"",
            "\"${server.url("/token")}\"",
        )
        val mockBase = server.url("/").toString()
        return object : VertexProvider(
            http = http,
            store = FakeStore(vertex = apiKey, vertexSaJson = rewritten),
            configFlow = flowOf(LlmConfig(vertexModel = "gemini-2.5-flash")),
            json = json,
            tokenSource = GoogleOAuthTokenSource(http),
        ) {
            override val baseUrl: String = mockBase
        }
    }
}
