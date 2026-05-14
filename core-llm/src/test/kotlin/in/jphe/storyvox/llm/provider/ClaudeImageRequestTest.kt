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
 * Issue #215 — end-to-end wire test for the multi-modal request
 * shape emitted by [ClaudeApiProvider.stream]. When the latest user
 * message carries [LlmContentBlock.Image] parts, the payload posted
 * to Anthropic must use the typed-block content array (not the
 * string-content shape).
 */
class ClaudeImageRequestTest {

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

    private fun providerWith(key: String?): ClaudeApiProvider {
        val testEndpoint = server.url("/v1/messages").toString()
        return object : ClaudeApiProvider(
            http = http,
            store = FakeStore(claude = key),
            configFlow = flowOf(LlmConfig(claudeModel = "claude-haiku-4.5")),
            json = json,
        ) {
            override val endpointUrl: String = testEndpoint
        }
    }

    @Test
    fun `image-bearing send posts typed-block content array`() = runTest {
        val sse = """
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"I see a cat."}}
            data: {"type":"message_stop"}

        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse)
                .setResponseCode(200),
        )

        val tokens = providerWith("k").stream(
            messages = listOf(
                LlmMessage(
                    role = LlmMessage.Role.user,
                    content = "what's in this photo?",
                    parts = listOf(
                        LlmContentBlock.Image(
                            base64 = "Zm9vYmFy",  // "foobar"
                            mimeType = "image/jpeg",
                        ),
                        LlmContentBlock.Text("what's in this photo?"),
                    ),
                ),
            ),
        ).toList()
        assertEquals(listOf("I see a cat."), tokens)

        val req = server.takeRequest()
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject

        // model + max_tokens stayed on the body; image-request shape
        // doesn't drop them.
        assertTrue(body.containsKey("model"))
        assertTrue(body.containsKey("max_tokens"))
        // messages[0].content is now an array (not a string).
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        val msg = messages[0].jsonObject
        assertEquals("user", msg["role"]?.jsonPrimitive?.contentOrNull)
        val content = msg["content"]!!.jsonArray
        assertEquals(2, content.size)
        // Block 0: image with base64 source.
        val img = content[0].jsonObject
        assertEquals("image", img["type"]?.jsonPrimitive?.contentOrNull)
        val src = img["source"]!!.jsonObject
        assertEquals("base64", src["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("image/jpeg", src["media_type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Zm9vYmFy", src["data"]?.jsonPrimitive?.contentOrNull)
        // Block 1: text.
        val text = content[1].jsonObject
        assertEquals("text", text["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "what's in this photo?",
            text["text"]?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test
    fun `text-only send keeps the legacy string-content shape`() = runTest {
        // Sanity check that the multi-modal dispatch doesn't bleed
        // into the text-only hot path — text-only messages should
        // still serialize as the simpler string-content shape that
        // every Anthropic-compat backend handles.
        val sse = """
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}
            data: {"type":"message_stop"}

        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse)
                .setResponseCode(200),
        )

        providerWith("k").stream(
            messages = listOf(
                LlmMessage(LlmMessage.Role.user, "just text"),
            ),
        ).toList()

        val req = server.takeRequest()
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        val msg = body["messages"]!!.jsonArray[0].jsonObject
        // content is a string, not an array.
        assertEquals(
            "just text",
            msg["content"]?.jsonPrimitive?.contentOrNull,
        )
    }
}
