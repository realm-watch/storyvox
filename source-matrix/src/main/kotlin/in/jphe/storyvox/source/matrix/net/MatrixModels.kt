package `in`.jphe.storyvox.source.matrix.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Issue #457 — minimal Matrix Client-Server API response shapes.
 *
 * Only the fields storyvox actually reads are declared. The Matrix
 * spec returns dozens of fields per event (sender domains, age, hash,
 * signatures, unsigned data, redacts...); declaring them all would
 * just be future-fragile noise. `Json.ignoreUnknownKeys = true` drops
 * what we don't ask for, and the spec's stable v3 surface guarantees
 * additive evolution within a major version so unknown new fields
 * don't break us.
 */

/**
 * `GET /_matrix/client/v3/account/whoami` response. Confirms a token
 * is live and returns the `@user:homeserver` id behind it. Used by
 * Settings → Library & Sync → Matrix to render "Signed in as
 * @alice:matrix.org" once the user pastes a token.
 */
@Serializable
internal data class MatrixWhoami(
    @SerialName("user_id")
    val userId: String,
    /** Optional device id the token was issued for; we don't surface
     *  it in v1 but it's part of the documented response shape. */
    @SerialName("device_id")
    val deviceId: String? = null,
)

/**
 * `GET /_matrix/client/v3/joined_rooms` response. Just an array of
 * room ids; metadata (name, topic, member count) requires per-room
 * state lookups.
 */
@Serializable
internal data class MatrixJoinedRooms(
    @SerialName("joined_rooms")
    val joinedRooms: List<String> = emptyList(),
)

/**
 * `GET /_matrix/client/v3/rooms/{roomId}/state/m.room.name` response.
 * Matrix returns just `{"name": "..."}` for room name state events.
 * Older rooms may not have an `m.room.name` event set, in which case
 * the homeserver returns 404 and the source falls back to the room id
 * as the display title.
 */
@Serializable
internal data class MatrixRoomName(
    val name: String = "",
)

/**
 * `GET /_matrix/client/v3/rooms/{roomId}/state/m.room.topic` response.
 * Topics are optional; absence is a 404 from the homeserver, surfaced
 * as a null description on the [`in`.jphe.storyvox.data.source.model.FictionSummary].
 */
@Serializable
internal data class MatrixRoomTopic(
    val topic: String = "",
)

/**
 * `GET /_matrix/client/v3/rooms/{roomId}/messages` response envelope.
 *
 * Matrix wraps the actual events in a `chunk` array, plus pagination
 * tokens `start` + `end` for backwards/forwards walking. The source
 * layer passes the most recent batch's `end` token back as the
 * `from` parameter on the next call to walk further back in history.
 */
@Serializable
internal data class MatrixMessagesResponse(
    /** Events in the response, in the order requested. With
     *  `dir=b` the chunk is newest-first (matching Discord's
     *  ordering) and the source layer reverses for chronological
     *  rendering. */
    val chunk: List<MatrixEvent> = emptyList(),
    /** Pagination token to pass back as `from` on the next call for
     *  more events in the same direction. Null when the homeserver
     *  has no more events to return. */
    val end: String? = null,
    /** Pagination token for the opposite direction. Unused in v1
     *  (we only walk backwards from the room's current head) but
     *  carried in the model for completeness. */
    val start: String? = null,
)

/**
 * One `m.room.message`-shaped event from `/rooms/{id}/messages`.
 *
 * The Matrix event envelope is generic — many event types share this
 * outer shape (state events, ephemeral events, custom event types).
 * v1 surfaces only events with `type == "m.room.message"`; everything
 * else is filtered at the source layer (joins, redactions, reactions,
 * receipts, typing).
 *
 * The `content` field carries the message-type-specific body —
 * `m.text`, `m.image`, `m.file`, `m.video`, etc. v1 reads `body` from
 * any of them (text messages have the prose in `body`; media events
 * have the filename in `body` and a separate `url` / `info` block we
 * don't need to render — TTS just narrates "Attachment: filename").
 */
@Serializable
internal data class MatrixEvent(
    @SerialName("event_id")
    val eventId: String,
    /** Matrix event type — `m.room.message`, `m.room.member`,
     *  `m.reaction`, etc. The source layer keeps only
     *  `m.room.message` for v1. */
    val type: String = "",
    /** `@user:homeserver` id of the event sender. */
    val sender: String = "",
    /** Origin-server timestamp in unix millis (Matrix is millis-precision,
     *  unlike Discord's ISO-8601 strings). The coalesce window
     *  comparison is a direct subtract — no date parsing. */
    @SerialName("origin_server_ts")
    val originServerTs: Long = 0,
    /** Message-type-specific body. v1 reads `body` (always present
     *  on `m.room.message` per the spec) and `msgtype` (which
     *  distinguishes `m.text` / `m.image` / `m.file` / ...). The
     *  generic JsonObject lets us read those without declaring a
     *  union of every event content shape. */
    val content: JsonObject = JsonObject(emptyMap()),
) {
    /** Message-content body. For `m.text` this is the prose; for
     *  `m.image` / `m.file` / `m.video` it's the filename. Empty
     *  when the event has no body field (which is true for some
     *  edge cases like redacted events). */
    val body: String
        get() = content.stringOrEmpty("body")

    /** Matrix `msgtype` discriminator — `m.text`, `m.image`,
     *  `m.file`, `m.video`, `m.audio`, `m.emote`, `m.notice`. Empty
     *  for events that aren't `m.room.message` (the source filters
     *  those out before consulting this field). */
    val msgtype: String
        get() = content.stringOrEmpty("msgtype")
}

/**
 * `GET /_matrix/client/v3/profile/{userId}/displayname` response.
 * Per the Matrix spec a profile may have no displayname set, in
 * which case the field is absent and the homeserver returns 404
 * (or 200 with `displayname == null` on some implementations).
 * Both forms are equivalent at the source layer and fall back to
 * the bare `@handle:server` Matrix id for the chapter byline.
 */
@Serializable
internal data class MatrixDisplayName(
    val displayname: String? = null,
)

/**
 * Structured error envelope returned by Matrix on 4xx/5xx —
 * `{ "errcode": "M_FORBIDDEN", "error": "Invalid token" }`. Decoded
 * for human-readable surfacing in the
 * [`in`.jphe.storyvox.data.source.model.FictionResult.Failure]
 * message field; the runCatching guard in [`in`.jphe.storyvox.source.matrix.net.MatrixApi]
 * handles non-JSON bodies (e.g. reverse-proxy HTML error pages
 * from a self-hosted homeserver behind nginx).
 */
@Serializable
internal data class MatrixError(
    /** Standard `M_*` error code from the Matrix spec — `M_FORBIDDEN`,
     *  `M_UNKNOWN_TOKEN`, `M_LIMIT_EXCEEDED`, `M_NOT_FOUND`, etc. */
    val errcode: String = "",
    /** Human-readable error message — homeserver-specific, but
     *  typically helpful (e.g. "Access token has expired", "Room
     *  not found"). */
    val error: String = "",
    /** On `M_LIMIT_EXCEEDED` the homeserver may include a
     *  retry-after-millis hint inline (in addition to the
     *  `Retry-After` HTTP header). The transport layer prefers the
     *  HTTP header but falls back to this field when the header
     *  is absent. */
    @SerialName("retry_after_ms")
    val retryAfterMs: Long? = null,
)

/**
 * Helper: read a string from a Matrix `content` JsonObject without
 * throwing on missing / null / non-string fields. Keeps the
 * generic-event surface ergonomic at the call site.
 */
internal fun JsonObject.stringOrEmpty(key: String): String {
    val element: JsonElement = this[key] ?: return ""
    if (element is JsonNull) return ""
    if (element is JsonPrimitive) return element.contentOrNull.orEmpty()
    return ""
}

/**
 * Internal helper used by tests + the source layer to construct a
 * synthetic Matrix `content` block for fixture messages without
 * round-tripping through JSON. Production code reads `content` from
 * the homeserver response; this builder is for unit-test message
 * fixtures and the legacy stub paths.
 */
internal fun matrixTextContent(body: String, msgtype: String = "m.text"): JsonObject {
    val map = mutableMapOf<String, JsonElement>(
        "body" to JsonPrimitive(body),
        "msgtype" to JsonPrimitive(msgtype),
    )
    return JsonObject(map)
}
