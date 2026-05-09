package `in`.jphe.storyvox.llm.sse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Three SSE line parsers covering the full LLM provider matrix:
 *  - [anthropic] — Claude direct, Anthropic Teams (same wire format).
 *  - [openAiCompat] — OpenAI, Ollama, DigitalOcean, Puter, Foundry
 *    serverless, Foundry deployed. Most of the field shares this.
 *  - [vertex] — Google Vertex AI / Gemini. True SSE (when called with
 *    `?alt=sse`) but the JSON shape is Gemini's, not OpenAI's.
 *
 * Direct Kotlin port of `cloud-chat-assistant/llm_stream.py`'s
 * `_parse_sse_line`. Bedrock uses a binary event-stream format and
 * gets its own parser when that provider ships.
 *
 * All functions return `null` for "no token in this line" — junk
 * lines, comments, the `[DONE]` sentinel, malformed JSON. Callers
 * pass through and continue reading. Returning a token (non-null
 * String) means "emit this".
 */
object SseLineParser {

    /**
     * Anthropic events have a `type` field on the JSON object inside
     * the `data:` line. Only `content_block_delta` carries text, in
     * `delta.text`. Other events (`message_start`, `message_delta`,
     * `message_stop`, `usage`, `ping`) we ignore.
     */
    fun anthropic(line: String, json: Json): String? {
        if (!line.startsWith("data: ")) return null
        val data = line.substring(6)
        if (data.isBlank()) return null
        val obj = runCatching { json.parseToJsonElement(data) }
            .getOrNull()?.jsonObject ?: return null
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        if (type != "content_block_delta") return null
        return obj["delta"]?.jsonObject
            ?.get("text")?.jsonPrimitive?.contentOrNull
    }

    /**
     * OpenAI-compat: tokens in `choices[0].delta.content`. The
     * `[DONE]` sentinel ends the stream and returns null; the
     * caller's loop terminates naturally because the server then
     * closes the connection.
     */
    fun openAiCompat(line: String, json: Json): String? {
        if (!line.startsWith("data: ")) return null
        val data = line.substring(6)
        if (data.trim() == "[DONE]") return null
        if (data.isBlank()) return null
        val obj = runCatching { json.parseToJsonElement(data) }
            .getOrNull()?.jsonObject ?: return null
        val choices = obj["choices"]?.jsonArray ?: return null
        if (choices.isEmpty()) return null
        return choices[0].jsonObject["delta"]?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull
    }

    /**
     * Vertex / Gemini events. Each `data:` line is a full
     * `GenerateContentResponse` whose token text is at
     * `candidates[0].content.parts[0].text`. Unlike OpenAI there's no
     * `[DONE]` sentinel — the server simply closes the stream when
     * the last frame's `finishReason` is set; the caller's read loop
     * terminates naturally on EOF.
     */
    fun vertex(line: String, json: Json): String? {
        if (!line.startsWith("data: ")) return null
        val data = line.substring(6)
        if (data.isBlank()) return null
        val obj = runCatching { json.parseToJsonElement(data) }
            .getOrNull()?.jsonObject ?: return null
        val candidates = obj["candidates"]?.jsonArray ?: return null
        if (candidates.isEmpty()) return null
        val parts = candidates[0].jsonObject["content"]?.jsonObject
            ?.get("parts")?.jsonArray ?: return null
        if (parts.isEmpty()) return null
        return parts[0].jsonObject["text"]?.jsonPrimitive?.contentOrNull
    }
}
