package `in`.jphe.storyvox.llm.provider

import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmContentBlock
import `in`.jphe.storyvox.llm.LlmMessage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Issue #215 — end-to-end wire test for the multi-modal request shape
 * emitted by [OpenAiApiProvider.stream] when the latest user message
 * carries [LlmContentBlock.Image] parts.
 */
class OpenAiImageRequestTest {

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

    private fun providerWith(key: String?): OpenAiApiProvider {
        val testEndpoint = server.url("/v1/chat/completions").toString()
        return object : OpenAiApiProvider(
            http = http,
            store = FakeStore(openAi = key),
            configFlow = flowOf(LlmConfig(openAiModel = "gpt-4o-mini")),
            json = json,
        ) {
            override val endpointUrl: String = testEndpoint
        }
    }

    @Test
    fun `image-bearing send posts data-URI image_url block`() = runTest {
        // OpenAI's chat-completions SSE shape: data lines wrapping
        // a `choices[0].delta.content` token, ending in [DONE].
        val sse = """
            data: {"choices":[{"delta":{"content":"a cat"}}]}
            data: [DONE]

        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse)
                .setResponseCode(200),
        )

        providerWith("k").stream(
            messages = listOf(
                LlmMessage(
                    role = LlmMessage.Role.user,
                    content = "what's in this?",
                    parts = listOf(
                        LlmContentBlock.Image(
                            base64 = "Zm9v",
                            mimeType = "image/png",
                        ),
                        LlmContentBlock.Text("what's in this?"),
                    ),
                ),
            ),
        ).toList()

        val req = server.takeRequest()
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        val messages = body["messages"]!!.jsonArray
        // First (and only) message — content is the typed-block array.
        val msg = messages.last().jsonObject
        assertEquals("user", msg["role"]?.jsonPrimitive?.contentOrNull)
        val content = msg["content"]!!.jsonArray
        assertEquals(2, content.size)
        val img = content[0].jsonObject
        assertEquals("image_url", img["type"]?.jsonPrimitive?.contentOrNull)
        val imageUrl = img["image_url"]!!.jsonObject
        assertEquals(
            "data:image/png;base64,Zm9v",
            imageUrl["url"]?.jsonPrimitive?.contentOrNull,
        )
        val text = content[1].jsonObject
        assertEquals("text", text["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "what's in this?",
            text["text"]?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test
    fun `system prompt is preserved as a string-content message`() = runTest {
        // Image-bearing chats still need the system prompt as a
        // regular message at the head of the messages array (OpenAI
        // convention). The system message itself stays a string —
        // it doesn't carry images.
        val sse = """
            data: {"choices":[{"delta":{"content":"ok"}}]}
            data: [DONE]

        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse)
                .setResponseCode(200),
        )

        providerWith("k").stream(
            messages = listOf(
                LlmMessage(
                    role = LlmMessage.Role.user,
                    content = "x",
                    parts = listOf(
                        LlmContentBlock.Image("Zm9v", "image/jpeg"),
                        LlmContentBlock.Text("x"),
                    ),
                ),
            ),
            systemPrompt = "you are a librarian",
        ).toList()

        val req = server.takeRequest()
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        val messages = body["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        val sys = messages[0].jsonObject
        assertEquals("system", sys["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "you are a librarian",
            sys["content"]?.jsonPrimitive?.contentOrNull,
        )
        val user = messages[1].jsonObject
        assertEquals("user", user["role"]?.jsonPrimitive?.contentOrNull)
        // User content is the typed-block array.
        assertTrue(user["content"] is kotlinx.serialization.json.JsonArray)
    }
}
