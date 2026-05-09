package `in`.jphe.storyvox.llm.provider

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * AWS binary event-stream frame parser. Used by Bedrock's
 * `converse-stream` response — distinct from Server-Sent Events.
 *
 * Frame layout (big-endian):
 * ```
 * [total_len:u32][headers_len:u32][prelude_crc:u32]
 * [headers:N][payload:M][message_crc:u32]
 * ```
 *
 * Each header is `[name_len:u8][name:UTF-8][type:u8][value]` where for
 * type=7 (string) the value is `[val_len:u16][val:UTF-8]`. We only
 * decode string headers — Bedrock's response uses string-typed
 * `:event-type`, `:content-type`, `:message-type`. Other header types
 * (uuid, timestamp, …) end the parse for that frame; that's safe
 * because we get what we need from the leading string headers.
 *
 * Direct port of `_parse_event_stream` + `_parse_event_headers` in
 * `cloud-chat-assistant/llm_stream.py`. CRC bytes are NOT validated —
 * cloud-chat-assistant doesn't, and TLS already protects the stream
 * end-to-end. Adding CRC32 wouldn't catch anything TLS doesn't.
 */
internal object EventStreamParser {

    /** A decoded event-stream frame. `eventType` comes from the
     *  `:event-type` header (or `:exception-type` on error frames);
     *  `payload` is the JSON-parsed body (empty object if the frame
     *  has no payload or the JSON failed to parse). */
    data class Event(val eventType: String, val payload: JsonObject)

    /**
     * Decode all complete frames in [data] starting at offset 0.
     * Returns the list of events plus the number of bytes consumed
     * — the caller keeps any trailing partial-frame bytes for the
     * next chunk.
     *
     * Tokens stream in chunks of arbitrary size; a single OkHttp
     * read may give us 0 frames (chunk landed mid-frame), 1 frame, or
     * many. The accumulator pattern (caller appends, we drain, caller
     * keeps remainder) is the same as cloud-chat-assistant's
     * `_extract_bedrock_tokens`.
     */
    fun decode(data: ByteArray, json: Json): Pair<List<Event>, Int> {
        val events = mutableListOf<Event>()
        var off = 0
        while (off + 12 <= data.size) {
            val bb = ByteBuffer.wrap(data, off, data.size - off)
                .order(ByteOrder.BIG_ENDIAN)
            val totalLen = bb.int
            val headersLen = bb.int
            // prelude CRC — read past it; we don't validate.
            bb.int

            if (totalLen <= 0 || off + totalLen > data.size) break

            val headersStart = off + 12
            val headersEnd = headersStart + headersLen
            // Frame ends at off + totalLen; last 4 bytes are message CRC.
            val payloadEnd = off + totalLen - 4

            if (headersEnd > payloadEnd || payloadEnd > data.size) {
                // Malformed frame — skip past it to keep streaming alive.
                off += totalLen
                continue
            }

            val headers = parseHeaders(data, headersStart, headersEnd)
            val eventType = headers[":event-type"]
                ?: headers[":exception-type"]
                ?: "unknown"
            val payload = if (payloadEnd > headersEnd) {
                val raw = String(data, headersEnd, payloadEnd - headersEnd, Charsets.UTF_8)
                runCatching { json.parseToJsonElement(raw).jsonObject }
                    .getOrDefault(EMPTY)
            } else {
                EMPTY
            }
            events.add(Event(eventType, payload))
            off += totalLen
        }
        return events to off
    }

    /** Convenience: decode and yield only the text deltas from
     *  `contentBlockDelta` events. Used by the production provider
     *  hot path; tests prefer [decode] for full visibility. */
    fun decodeTokens(data: ByteArray, json: Json): Pair<List<String>, Int> {
        val (events, consumed) = decode(data, json)
        val tokens = events.mapNotNull { ev ->
            if (ev.eventType != "contentBlockDelta") return@mapNotNull null
            // Bedrock contentBlockDelta payload: { "delta": { "text": "…" }, "contentBlockIndex": 0 }
            val delta = ev.payload["delta"]?.jsonObject ?: return@mapNotNull null
            delta["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        }
        return tokens to consumed
    }

    private fun parseHeaders(data: ByteArray, start: Int, end: Int): Map<String, String> {
        val headers = HashMap<String, String>(4)
        var pos = start
        while (pos < end) {
            val nameLen = data[pos].toInt() and 0xFF; pos += 1
            if (pos + nameLen > end) break
            val name = String(data, pos, nameLen, Charsets.UTF_8); pos += nameLen
            if (pos >= end) break
            val type = data[pos].toInt() and 0xFF; pos += 1
            if (type != STRING_HEADER_TYPE) break
            if (pos + 2 > end) break
            val valLen = ((data[pos].toInt() and 0xFF) shl 8) or
                (data[pos + 1].toInt() and 0xFF)
            pos += 2
            if (pos + valLen > end) break
            headers[name] = String(data, pos, valLen, Charsets.UTF_8)
            pos += valLen
        }
        return headers
    }

    private const val STRING_HEADER_TYPE = 7
    private val EMPTY = JsonObject(emptyMap())
}
