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

class OpenAiApiProviderTest {

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

    private fun providerWith(key: String?): OpenAiApiProvider {
        val streamUrl = server.url("/v1/chat/completions").toString()
        val modelsUrl = server.url("/v1/models").toString()
        return object : OpenAiApiProvider(
            http = http,
            store = FakeStore(openAi = key),
            configFlow = flowOf(LlmConfig(openAiModel = "gpt-4o-mini")),
            json = json,
        ) {
            override val endpointUrl: String = streamUrl
            override val probeUrl: String = modelsUrl
        }
    }

    @Test
    fun `streams text deltas in order`() = runTest {
        val sse = listOf(
            """data: {"choices":[{"delta":{"role":"assistant"}}]}""",
            """data: {"choices":[{"delta":{"content":"Once "}}]}""",
            """data: {"choices":[{"delta":{"content":"upon "}}]}""",
            """data: {"choices":[{"delta":{"content":"a time."}}]}""",
            """data: [DONE]""",
        ).joinToString("\n") + "\n"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse)
                .setResponseCode(200),
        )
        val tokens = providerWith("k").stream(
            messages = listOf(LlmMessage(LlmMessage.Role.user, "hi")),
            systemPrompt = "be a librarian",
        ).toList()
        assertEquals(listOf("Once ", "upon ", "a time."), tokens)

        val req = server.takeRequest()
        assertEquals("Bearer k", req.getHeader("Authorization"))
        // Body should put the system prompt as the first message.
        val body = req.body.readUtf8()
        assertTrue(body.contains(""""role":"system""""))
        assertTrue(body.contains("be a librarian"))
    }

    @Test
    fun `401 raises AuthFailed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
        val ex = assertThrows(LlmError.AuthFailed::class.java) {
            runBlocking {
                providerWith("k").stream(
                    listOf(LlmMessage(LlmMessage.Role.user, "x")),
                ).toList()
            }
        }
        assertEquals(ProviderId.OpenAi, ex.provider)
    }

    @Test
    fun `missing key raises NotConfigured`() = runTest {
        val ex = assertThrows(LlmError.NotConfigured::class.java) {
            runBlocking {
                providerWith(null).stream(
                    listOf(LlmMessage(LlmMessage.Role.user, "x")),
                ).toList()
            }
        }
        assertEquals(ProviderId.OpenAi, ex.provider)
    }

    @Test
    fun `probe ok on 200 from models endpoint`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
        val result = providerWith("k").probe()
        assertEquals(ProbeResult.Ok, result)
    }

    @Test
    fun `probe auth-error on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = providerWith("k").probe()
        assertTrue(result is ProbeResult.AuthError)
    }

    @Test
    fun `probe misconfigured when no key`() = runTest {
        val result = providerWith(null).probe()
        assertTrue(result is ProbeResult.Misconfigured)
    }
}
