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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OllamaProviderTest {

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

    private fun providerWith(baseUrl: String, model: String = "llama3.3"): OllamaProvider {
        return OllamaProvider(
            http = http,
            configFlow = flowOf(LlmConfig(ollamaBaseUrl = baseUrl, ollamaModel = model)),
            json = json,
        )
    }

    @Test
    fun `streams text deltas in order`() = runTest {
        val sse = listOf(
            """data: {"choices":[{"delta":{"content":"Hi "}}]}""",
            """data: {"choices":[{"delta":{"content":"there."}}]}""",
            """data: [DONE]""",
        ).joinToString("\n") + "\n"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse)
                .setResponseCode(200),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        val tokens = providerWith(baseUrl).stream(
            messages = listOf(LlmMessage(LlmMessage.Role.user, "hi")),
        ).toList()
        assertEquals(listOf("Hi ", "there."), tokens)

        val req = server.takeRequest()
        // Path should hit the OpenAI-compat endpoint.
        assertEquals("/v1/chat/completions", req.path)
        // No Authorization header expected on Ollama.
        assertEquals(null, req.getHeader("Authorization"))
    }

    @Test
    fun `blank URL raises NotConfigured`() = runTest {
        val ex = assertThrows(LlmError.NotConfigured::class.java) {
            runBlocking {
                providerWith("").stream(
                    listOf(LlmMessage(LlmMessage.Role.user, "x")),
                ).toList()
            }
        }
        assertEquals(ProviderId.Ollama, ex.provider)
    }

    @Test
    fun `5xx raises ProviderError`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val ex = assertThrows(LlmError.ProviderError::class.java) {
            runBlocking {
                providerWith(baseUrl).stream(
                    listOf(LlmMessage(LlmMessage.Role.user, "x")),
                ).toList()
            }
        }
        assertEquals(500, ex.status)
        assertTrue(ex.detail.contains("oops"))
    }

    @Test
    fun `probe ok on 200 from api tags`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"models":[]}"""))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val result = providerWith(baseUrl).probe()
        assertEquals(ProbeResult.Ok, result)

        val req = server.takeRequest()
        assertEquals("/api/tags", req.path)
    }

    @Test
    fun `probe not-reachable on connection failure`() = runTest {
        // Start with a bad URL — port 1 is reserved and refuses
        // connections fast on most systems.
        val result = providerWith("http://127.0.0.1:1").probe()
        assertTrue(result is ProbeResult.NotReachable)
    }
}
