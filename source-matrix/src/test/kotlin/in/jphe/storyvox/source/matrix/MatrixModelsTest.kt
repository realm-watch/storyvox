package `in`.jphe.storyvox.source.matrix

import `in`.jphe.storyvox.source.matrix.net.MatrixDisplayName
import `in`.jphe.storyvox.source.matrix.net.MatrixError
import `in`.jphe.storyvox.source.matrix.net.MatrixJoinedRooms
import `in`.jphe.storyvox.source.matrix.net.MatrixMessagesResponse
import `in`.jphe.storyvox.source.matrix.net.MatrixRoomName
import `in`.jphe.storyvox.source.matrix.net.MatrixRoomTopic
import `in`.jphe.storyvox.source.matrix.net.MatrixWhoami
import `in`.jphe.storyvox.source.matrix.net.humanMessage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #457 — JSON parsing checks for the Matrix Client-Server
 * API responses storyvox reads. These run against captured-from-spec
 * fixtures, not a live homeserver; they guarantee storyvox keeps
 * parsing the documented shapes even when the homeserver adds new
 * fields (the `ignoreUnknownKeys = true` posture).
 */
class MatrixModelsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `parses whoami response`() {
        // Real response shape from the Matrix spec's
        // GET /_matrix/client/v3/account/whoami. The `user_id` is
        // the canonical `@handle:homeserver` form; `device_id` is
        // optional (omitted when the token was issued without one).
        val body = """
            {
              "user_id": "@alice:matrix.org",
              "device_id": "ABCDEFG",
              "is_guest": false
            }
        """.trimIndent()

        val whoami = json.decodeFromString<MatrixWhoami>(body)

        assertEquals("@alice:matrix.org", whoami.userId)
        assertEquals("ABCDEFG", whoami.deviceId)
        // is_guest is unknown to our model; ignoreUnknownKeys drops
        // it without complaint.
    }

    @Test
    fun `parses joined_rooms response`() {
        // GET /_matrix/client/v3/joined_rooms returns just a flat
        // list of room ids. No metadata; the source layer fetches
        // m.room.name / m.room.topic per room for rendering.
        val body = """
            {
              "joined_rooms": [
                "!abcdef:matrix.org",
                "!xyz123:fosdem.org",
                "!storyvox:kde.org"
              ]
            }
        """.trimIndent()

        val joined = json.decodeFromString<MatrixJoinedRooms>(body)

        assertEquals(3, joined.joinedRooms.size)
        assertEquals("!abcdef:matrix.org", joined.joinedRooms[0])
        // The second room is on a different homeserver — federation
        // means a user's joined-rooms set can span multiple
        // homeservers; we just round-trip the ids as the spec
        // defines them.
        assertEquals("!xyz123:fosdem.org", joined.joinedRooms[1])
    }

    @Test
    fun `parses room state name and topic`() {
        val nameBody = """{"name": "Storyvox Dev"}"""
        val topicBody = """{"topic": "Engineering chat for the storyvox project"}"""

        assertEquals("Storyvox Dev", json.decodeFromString<MatrixRoomName>(nameBody).name)
        assertEquals(
            "Engineering chat for the storyvox project",
            json.decodeFromString<MatrixRoomTopic>(topicBody).topic,
        )
    }

    @Test
    fun `parses messages response with mixed event types`() {
        // GET /_matrix/client/v3/rooms/{roomId}/messages?dir=b
        // returns events in reverse-chronological order in `chunk`,
        // with pagination tokens `start` + `end`. v1 only renders
        // m.room.message-typed events; the source layer filters
        // the rest at the call site.
        val body = """
            {
              "start": "t392-516_47314_0_7_1_1_1_11444_1",
              "end": "t47409-4357353_219380_26003_2265",
              "chunk": [
                {
                  "event_id": "${'$'}ghi789:matrix.org",
                  "type": "m.room.message",
                  "sender": "@alice:matrix.org",
                  "origin_server_ts": 1715630000000,
                  "content": {
                    "msgtype": "m.text",
                    "body": "Latest thought from alice"
                  },
                  "unsigned": { "age": 5400 }
                },
                {
                  "event_id": "${'$'}def456:matrix.org",
                  "type": "m.room.member",
                  "sender": "@bob:matrix.org",
                  "origin_server_ts": 1715629900000,
                  "state_key": "@bob:matrix.org",
                  "content": { "membership": "join" }
                },
                {
                  "event_id": "${'$'}abc123:matrix.org",
                  "type": "m.room.message",
                  "sender": "@bob:matrix.org",
                  "origin_server_ts": 1715629800000,
                  "content": {
                    "msgtype": "m.image",
                    "body": "dragon-sketch.png",
                    "url": "mxc://matrix.org/abc",
                    "info": { "mimetype": "image/png", "size": 245678 }
                  }
                }
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString<MatrixMessagesResponse>(body)

        assertEquals(3, response.chunk.size)
        assertEquals("t47409-4357353_219380_26003_2265", response.end)
        // Newest-first per dir=b — alice's text is the first chunk
        // entry.
        assertEquals("m.room.message", response.chunk[0].type)
        assertEquals("@alice:matrix.org", response.chunk[0].sender)
        assertEquals("m.text", response.chunk[0].msgtype)
        assertEquals("Latest thought from alice", response.chunk[0].body)
        // The membership event must round-trip through the parser
        // without falling over — even though the source layer
        // filters it at the call site.
        assertEquals("m.room.member", response.chunk[1].type)
        // Media event surfaces filename in body; msgtype tells us
        // it's not text. The renderer prefixes "Attachment: ".
        assertEquals("m.image", response.chunk[2].msgtype)
        assertEquals("dragon-sketch.png", response.chunk[2].body)
    }

    @Test
    fun `parses displayname response with null value`() {
        // Matrix returns `{"displayname": null}` when the user
        // hasn't set a display name — the spec also allows the
        // homeserver to return 404, both forms collapse to "no
        // displayname" at the source layer.
        val nullBody = """{"displayname": null}"""
        val setBody = """{"displayname": "Alice 🐉"}"""

        assertEquals(null, json.decodeFromString<MatrixDisplayName>(nullBody).displayname)
        assertEquals("Alice 🐉", json.decodeFromString<MatrixDisplayName>(setBody).displayname)
    }

    @Test
    fun `parses error envelope with retry_after_ms hint`() {
        // M_LIMIT_EXCEEDED is the Matrix-spec error code for
        // rate-limit responses. The homeserver may set a
        // `retry_after_ms` field in addition to the HTTP
        // Retry-After header; the transport prefers the header but
        // falls back to this field.
        val body = """
            {
              "errcode": "M_LIMIT_EXCEEDED",
              "error": "Too many requests",
              "retry_after_ms": 4321
            }
        """.trimIndent()

        val error = json.decodeFromString<MatrixError>(body)

        assertEquals("M_LIMIT_EXCEEDED", error.errcode)
        assertEquals("Too many requests", error.error)
        assertEquals(4321L, error.retryAfterMs)
        assertEquals("Too many requests", error.humanMessage())
    }

    @Test
    fun `error human message falls back to errcode when error is blank`() {
        // Some homeservers (older Synapse) return the errcode
        // without a human `error` field. The humanMessage helper
        // falls back to errcode rather than null so the UI still
        // has something to render.
        val body = """{"errcode": "M_FORBIDDEN", "error": ""}"""
        val error = json.decodeFromString<MatrixError>(body)
        assertEquals("M_FORBIDDEN", error.humanMessage())
    }
}
