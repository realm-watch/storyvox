package `in`.jphe.storyvox.llm.provider

import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmCredentialsStore
import `in`.jphe.storyvox.llm.LlmError
import `in`.jphe.storyvox.llm.LlmMessage
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

/**
 * Provider tests using a fake [LlmCredentialsStore] (the test-only
 * constructor on the open class). The endpoint URL is overridden via
 * a small subclass that points the streaming/probe URLs at
 * [MockWebServer].
 */
class ClaudeApiProviderTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var http: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient.Builder()
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun providerWith(key: String?, configModel: String = "claude-haiku-4.5"):
        ClaudeApiProvider {
        val testEndpoint = server.url("/v1/messages").toString()
        return object : ClaudeApiProvider(
            http = http,
            store = FakeStore(claude = key),
            configFlow = flowOf(LlmConfig(claudeModel = configModel)),
            json = json,
        ) {
            override val endpointUrl: String = testEndpoint
        }
    }

    @Test
    fun `streams text deltas in order`() = runTest {
        val sse = listOf(
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello, "}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"world."}}""",
            """data: {"type":"message_stop"}""",
        ).joinToString("\n") + "\n"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse)
                .setResponseCode(200),
        )

        val tokens = providerWith("test-key").stream(
            messages = listOf(LlmMessage(LlmMessage.Role.user, "hi")),
        ).toList()
        assertEquals(listOf("Hello, ", "world."), tokens)

        val req = server.takeRequest()
        assertEquals("test-key", req.getHeader("x-api-key"))
        assertEquals("2023-06-01", req.getHeader("anthropic-version"))
    }

    @Test
    fun `401 raises AuthFailed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("invalid"))
        val provider = providerWith("bad-key")
        val ex = assertThrows(LlmError.AuthFailed::class.java) {
            runBlocking {
                provider.stream(listOf(LlmMessage(LlmMessage.Role.user, "x"))).toList()
            }
        }
        assertEquals(ProviderId.Claude, ex.provider)
    }

    @Test
    fun `missing key raises NotConfigured`() = runTest {
        val provider = providerWith(null)
        val ex = assertThrows(LlmError.NotConfigured::class.java) {
            runBlocking {
                provider.stream(listOf(LlmMessage(LlmMessage.Role.user, "x"))).toList()
            }
        }
        assertEquals(ProviderId.Claude, ex.provider)
    }

    @Test
    fun `5xx raises ProviderError with body excerpt`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("upstream busy"))
        val provider = providerWith("k")
        val ex = assertThrows(LlmError.ProviderError::class.java) {
            runBlocking {
                provider.stream(listOf(LlmMessage(LlmMessage.Role.user, "x"))).toList()
            }
        }
        assertEquals(503, ex.status)
        assertTrue(ex.detail.contains("upstream busy"))
    }
}

/** Test-only subclass that bypasses SharedPreferences. */
internal class FakeStore(
    private val claude: String? = null,
    private val openAi: String? = null,
) : LlmCredentialsStore() {
    override fun claudeApiKey(): String? = claude
    override fun openAiApiKey(): String? = openAi
}
