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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.util.concurrent.TimeUnit

class VertexProviderTest {

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

    private fun providerWith(key: String?): VertexProvider {
        val mockBase = server.url("/").toString()
        return object : VertexProvider(
            http = http,
            store = FakeStore(vertex = key),
            configFlow = flowOf(LlmConfig(vertexModel = "gemini-2.5-flash")),
            json = json,
        ) {
            override val baseUrl: String = mockBase
        }
    }

    @Test
    fun `streams text deltas in order`() = runTest {
        // Three SSE frames + a final one with no content (just
        // finishReason). The parser should emit only the text-bearing
        // frames, in order.
        val sse = listOf(
            """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"Once "}]}}]}""",
            """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"upon "}]}}]}""",
            """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"a time."}]}}]}""",
            """data: {"candidates":[{"content":{"parts":[]},"finishReason":"STOP"}]}""",
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
        // Path includes the model + the colon-suffixed RPC name.
        assertTrue(
            "Path was ${req.path}",
            req.path?.startsWith(
                "/v1beta/models/gemini-2.5-flash:streamGenerateContent",
            ) == true,
        )
        // Auth is in the URL query string, not a header.
        assertTrue(req.path?.contains("key=k") == true)
        assertTrue(req.path?.contains("alt=sse") == true)
        // No Authorization header — Vertex API key auth doesn't use one.
        assertNull(req.getHeader("Authorization"))

        // Body should round-trip to a Vertex shape: contents[].parts[].text
        // with role mapping (user → "user"), and system_instruction
        // outside the contents array.
        val body = req.body.readUtf8()
        val parsed = json.parseToJsonElement(body).jsonObject
        assertNotNull(parsed["contents"])
        assertNotNull(parsed["system_instruction"])
        // assistant role would map to "model"; we only sent a user
        // message so the only role here is "user".
        assertTrue(body.contains(""""role":"user""""))
        assertTrue(body.contains(""""text":"hi""""))
        assertTrue(body.contains("be a librarian"))
    }

    @Test
    fun `assistant role maps to model in wire body`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"candidates\":[]}\n")
                .setResponseCode(200),
        )
        providerWith("k").stream(
            messages = listOf(
                LlmMessage(LlmMessage.Role.user, "first"),
                LlmMessage(LlmMessage.Role.assistant, "reply"),
                LlmMessage(LlmMessage.Role.user, "follow-up"),
            ),
        ).toList()
        val body = server.takeRequest().body.readUtf8()
        // Gemini calls the assistant turn "model" not "assistant".
        assertTrue("Body had no model role: $body", body.contains(""""role":"model""""))
        assertTrue(body.contains(""""role":"user""""))
        // No system_instruction when no system prompt was passed.
        val parsed = json.parseToJsonElement(body).jsonObject
        assertNull(parsed["system_instruction"])
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
        assertEquals(ProviderId.Vertex, ex.provider)
    }

    @Test
    fun `5xx raises ProviderError`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("upstream busy"))
        val ex = assertThrows(LlmError.ProviderError::class.java) {
            runBlocking {
                providerWith("k").stream(
                    listOf(LlmMessage(LlmMessage.Role.user, "x")),
                ).toList()
            }
        }
        assertEquals(ProviderId.Vertex, ex.provider)
        assertEquals(503, ex.status)
        assertTrue(ex.detail.contains("upstream busy"))
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
        assertEquals(ProviderId.Vertex, ex.provider)
    }

    @Test
    fun `probe ok on 200 from models endpoint`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"models":[]}"""))
        val result = providerWith("k").probe()
        assertEquals(ProbeResult.Ok, result)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertTrue(
            "Probe path was ${req.path}",
            req.path?.startsWith("/v1beta/models?") == true,
        )
        assertTrue(req.path?.contains("key=k") == true)
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

    // Round-trip + JSON-shape test for the Vertex wire body. Cassia
    // explicitly asks for this; it lives next to the network tests
    // because the data classes are package-private to provider/.
    @Test
    fun `wire body round-trips with Vertex JSON shape`() {
        val req = VertexRequest(
            contents = listOf(
                VertexContent(role = "user", parts = listOf(VertexPart("hello"))),
                VertexContent(role = "model", parts = listOf(VertexPart("hi back"))),
            ),
            systemInstruction = VertexContent(parts = listOf(VertexPart("be terse"))),
            generationConfig = VertexGenerationConfig(temperature = 0.7f, maxOutputTokens = 256),
        )
        val encoded = Json.encodeToString(req)
        val parsed = Json.parseToJsonElement(encoded).jsonObject

        // contents[0].parts[0].text == "hello", role "user".
        val contents = parsed["contents"]!!.jsonArray
        assertEquals(2, contents.size)
        val first = contents[0].jsonObject
        assertEquals("user", first["role"]!!.jsonPrimitive.content)
        assertEquals(
            "hello",
            first["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        // contents[1].role == "model" (Gemini's name for assistant).
        assertEquals(
            "model",
            contents[1].jsonObject["role"]!!.jsonPrimitive.content,
        )

        // system_instruction is at the top level (NOT inside contents).
        // It carries the same `parts: [{text}]` shape but no role.
        val sys = parsed["system_instruction"]!!.jsonObject
        assertEquals(
            "be terse",
            sys["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )

        // generationConfig.maxOutputTokens (camel-cased as Gemini wants).
        val gen = parsed["generationConfig"]!!.jsonObject
        assertEquals("256", gen["maxOutputTokens"]!!.jsonPrimitive.content)
        assertEquals("0.7", gen["temperature"]!!.jsonPrimitive.content)
    }
}
