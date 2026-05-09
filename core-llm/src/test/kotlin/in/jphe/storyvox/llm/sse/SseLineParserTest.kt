package `in`.jphe.storyvox.llm.sse

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Snapshot tests against canned wire formats from each provider.
 * Direct ports of the SSE shape examples in
 * `cloud-chat-assistant/llm_stream.py`.
 */
class SseLineParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `anthropic content_block_delta returns text`() {
        val line = """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello, "}}"""
        assertEquals("Hello, ", SseLineParser.anthropic(line, json))
    }

    @Test
    fun `anthropic message_start returns null`() {
        val line = """data: {"type":"message_start","message":{"id":"msg_01","model":"claude-haiku"}}"""
        assertNull(SseLineParser.anthropic(line, json))
    }

    @Test
    fun `anthropic message_stop returns null`() {
        val line = """data: {"type":"message_stop"}"""
        assertNull(SseLineParser.anthropic(line, json))
    }

    @Test
    fun `anthropic ignores non-data line`() {
        assertNull(SseLineParser.anthropic("event: ping", json))
        assertNull(SseLineParser.anthropic("", json))
        assertNull(SseLineParser.anthropic(": comment", json))
    }

    @Test
    fun `anthropic ignores malformed json`() {
        assertNull(SseLineParser.anthropic("data: {not json", json))
    }

    @Test
    fun `openAiCompat returns delta content`() {
        val line = """data: {"id":"chatcmpl-1","choices":[{"index":0,"delta":{"role":"assistant","content":"Once "}}]}"""
        assertEquals("Once ", SseLineParser.openAiCompat(line, json))
    }

    @Test
    fun `openAiCompat done sentinel returns null`() {
        assertNull(SseLineParser.openAiCompat("data: [DONE]", json))
        assertNull(SseLineParser.openAiCompat("data: [DONE]   ", json))
    }

    @Test
    fun `openAiCompat empty choices returns null`() {
        val line = """data: {"id":"chatcmpl-1","choices":[]}"""
        assertNull(SseLineParser.openAiCompat(line, json))
    }

    @Test
    fun `openAiCompat first-chunk role-only returns null`() {
        // First chunk often has role but no content; we should not
        // emit anything for it.
        val line = """data: {"choices":[{"delta":{"role":"assistant"}}]}"""
        assertNull(SseLineParser.openAiCompat(line, json))
    }

    @Test
    fun `openAiCompat ignores non-data line`() {
        assertNull(SseLineParser.openAiCompat("", json))
        assertNull(SseLineParser.openAiCompat(": keep-alive", json))
    }

    @Test
    fun `vertex returns text from candidates parts`() {
        val line = """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"Hello "}]}}]}"""
        assertEquals("Hello ", SseLineParser.vertex(line, json))
    }

    @Test
    fun `vertex finishReason-only frame returns null`() {
        val line = """data: {"candidates":[{"content":{"parts":[]},"finishReason":"STOP"}]}"""
        assertNull(SseLineParser.vertex(line, json))
    }

    @Test
    fun `vertex empty candidates returns null`() {
        val line = """data: {"candidates":[]}"""
        assertNull(SseLineParser.vertex(line, json))
    }

    @Test
    fun `vertex ignores non-data line and malformed json`() {
        assertNull(SseLineParser.vertex("", json))
        assertNull(SseLineParser.vertex(": keep-alive", json))
        assertNull(SseLineParser.vertex("data: {not json", json))
    }
}
