package `in`.jphe.storyvox.llm.provider

import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmError
import `in`.jphe.storyvox.llm.LlmMessage
import `in`.jphe.storyvox.llm.ProbeResult
import `in`.jphe.storyvox.llm.ProviderId
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Coverage for both Foundry deployment modes against a MockWebServer.
 * The two modes share a parser path (OpenAI-compat SSE) but diverge on
 * URL shape, body shape, and auth header — all of which need to land
 * correctly or Azure rejects.
 *
 * Sister to [AzureFoundryUrlBuilderTest] (pure URL coverage) and
 * [OpenAiApiProviderTest] (the body / SSE shape these tests share).
 */
class AzureFoundryProviderTest {

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

    private fun providerWith(
        key: String?,
        deployment: String = "gpt-4o-prod",
        serverless: Boolean = false,
    ): AzureFoundryProvider {
        val baseUrl = server.url("/").toString().trimEnd('/')
        return AzureFoundryProvider(
            http = http,
            store = FakeStore(foundry = key),
            configFlow = flowOf(
                LlmConfig(
                    foundryEndpoint = baseUrl,
                    foundryDeployment = deployment,
                    foundryServerless = serverless,
                ),
            ),
            json = json,
        )
    }

    // Standard happy-path SSE body. OpenAI-compat shape — same delta
    // structure as OpenAI / Ollama.
    private val happySse = listOf(
        """data: {"choices":[{"delta":{"role":"assistant"}}]}""",
        """data: {"choices":[{"delta":{"content":"Hello "}}]}""",
        """data: {"choices":[{"delta":{"content":"world."}}]}""",
        """data: [DONE]""",
    ).joinToString("\n") + "\n"

    @Test
    fun `deployed mode hits openai-deployments path with api-key header and no model in body`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(happySse)
                .setResponseCode(200),
        )
        val tokens = providerWith(key = "k", deployment = "gpt-4o-prod", serverless = false)
            .stream(
                messages = listOf(LlmMessage(LlmMessage.Role.user, "hi")),
                systemPrompt = "be a librarian",
            ).toList()
        assertEquals(listOf("Hello ", "world."), tokens)

        val req = server.takeRequest()
        // URL: /openai/deployments/{name}/chat/completions
        assertTrue(
            "expected deployments path, got ${req.path}",
            req.path?.contains("/openai/deployments/gpt-4o-prod/chat/completions") == true,
        )
        assertTrue(
            "missing api-version query string",
            req.path?.contains("api-version=2024-12-01-preview") == true,
        )
        // Auth: api-key header (NOT Authorization Bearer).
        assertEquals("k", req.getHeader("api-key"))
        assertNull("Foundry must not send Authorization", req.getHeader("Authorization"))
        // Body: deployed mode omits the `model` field (it's in the URL).
        val body = req.body.readUtf8()
        assertFalse(
            "deployed body must not include `model` field — Azure rejects it",
            body.contains(""""model":"""),
        )
        // System prompt threaded as a regular message at head of array.
        assertTrue(body.contains(""""role":"system""""))
        assertTrue(body.contains("be a librarian"))
    }

    @Test
    fun `serverless mode hits models path with model in body`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(happySse)
                .setResponseCode(200),
        )
        val tokens = providerWith(key = "k", deployment = "Llama-3.3-70B-Instruct", serverless = true)
            .stream(
                messages = listOf(LlmMessage(LlmMessage.Role.user, "hi")),
                systemPrompt = null,
            ).toList()
        assertEquals(listOf("Hello ", "world."), tokens)

        val req = server.takeRequest()
        assertTrue(
            "expected /models/chat/completions, got ${req.path}",
            req.path?.startsWith("/models/chat/completions") == true,
        )
        assertEquals("k", req.getHeader("api-key"))
        val body = req.body.readUtf8()
        // Serverless body MUST contain the model id — that's how the
        // catalog endpoint routes the call.
        assertTrue(
            "serverless body must include the model field",
            body.contains(""""model":"Llama-3.3-70B-Instruct""""),
        )
    }

    @Test
    fun `401 raises AuthFailed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
        val ex = assertThrows(LlmError.AuthFailed::class.java) {
            runBlocking {
                providerWith(key = "k").stream(
                    listOf(LlmMessage(LlmMessage.Role.user, "x")),
                ).toList()
            }
        }
        assertEquals(ProviderId.Foundry, ex.provider)
    }

    @Test
    fun `5xx raises ProviderError with body excerpt`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("upstream busy"))
        val ex = assertThrows(LlmError.ProviderError::class.java) {
            runBlocking {
                providerWith(key = "k").stream(
                    listOf(LlmMessage(LlmMessage.Role.user, "x")),
                ).toList()
            }
        }
        assertEquals(ProviderId.Foundry, ex.provider)
        assertEquals(503, ex.status)
        assertTrue(ex.detail.contains("upstream busy"))
    }

    @Test
    fun `missing key raises NotConfigured`() = runTest {
        val ex = assertThrows(LlmError.NotConfigured::class.java) {
            runBlocking {
                providerWith(key = null).stream(
                    listOf(LlmMessage(LlmMessage.Role.user, "x")),
                ).toList()
            }
        }
        assertEquals(ProviderId.Foundry, ex.provider)
    }

    @Test
    fun `missing endpoint raises NotConfigured even with key`() = runTest {
        val provider = AzureFoundryProvider(
            http = http,
            store = FakeStore(foundry = "k"),
            configFlow = flowOf(
                LlmConfig(
                    foundryEndpoint = "",
                    foundryDeployment = "gpt-4o",
                ),
            ),
            json = json,
        )
        val ex = assertThrows(LlmError.NotConfigured::class.java) {
            runBlocking {
                provider.stream(listOf(LlmMessage(LlmMessage.Role.user, "x"))).toList()
            }
        }
        assertEquals(ProviderId.Foundry, ex.provider)
    }

    @Test
    fun `probe returns Ok when key accepted`() = runTest {
        // Foundry chat-completions returns 405 for GET — that's the
        // "endpoint exists, key accepted" signal we treat as Ok.
        server.enqueue(MockResponse().setResponseCode(405))
        val result = providerWith(key = "k").probe()
        assertEquals(ProbeResult.Ok, result)
    }

    @Test
    fun `probe returns AuthError on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = providerWith(key = "k").probe()
        assertTrue(result is ProbeResult.AuthError)
    }

    @Test
    fun `probe returns Misconfigured when no key`() = runTest {
        val result = providerWith(key = null).probe()
        assertTrue(result is ProbeResult.Misconfigured)
    }

    @Test
    fun `probe returns NotReachable on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val result = providerWith(key = "k").probe()
        assertTrue(result is ProbeResult.NotReachable)
    }
}
