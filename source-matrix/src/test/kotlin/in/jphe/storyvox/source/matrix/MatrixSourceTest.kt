package `in`.jphe.storyvox.source.matrix

import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.matrix.net.MatrixEvent
import `in`.jphe.storyvox.source.matrix.net.matrixTextContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #457 — non-IO tests for the Matrix backend: id parsing,
 * URL claim ranking, coalescing rules, attachment surfacing. The
 * HTTP transport is exercised against captured JSON in
 * [MatrixModelsTest]; these tests cover the pure-logic surfaces
 * the source layer applies on top.
 */
class MatrixSourceTest {

    private fun event(
        id: String,
        sender: String,
        ts: Long,
        body: String = "msg-$id",
        msgtype: String = "m.text",
        type: String = "m.room.message",
    ): MatrixEvent = MatrixEvent(
        eventId = id,
        type = type,
        sender = sender,
        originServerTs = ts,
        content = matrixTextContent(body, msgtype),
    )

    // ─── fiction-id round-trip ────────────────────────────────────────

    @Test
    fun `fiction id round-trips a room id with bang colon and exclam`() {
        // The matrix:<roomIdEncoded> shape must round-trip a real
        // Matrix room id (which contains `!` and `:` characters)
        // back to its original form. The URL-encoding hides those
        // characters from the chapter id's `::` separator so the
        // parser doesn't get confused.
        val roomId = "!abcdef:matrix.org"
        val fictionId = matrixFictionId(roomId)
        assertEquals("matrix:%21abcdef%3Amatrix.org", fictionId)
        assertEquals(roomId, fictionId.toRoomId())
    }

    @Test
    fun `fiction id with chapter separator decodes only the room id portion`() {
        // The chapter id appends ::event-<eventId>. The room-id
        // decoder must stop at the `::` so it doesn't try to
        // URL-decode the event suffix.
        val roomId = "!xyz:fosdem.org"
        val fictionId = matrixFictionId(roomId)
        val chapterId = chapterIdFor(fictionId, "\$abc:fosdem.org")
        assertEquals(roomId, chapterId.toRoomId())
    }

    @Test
    fun `toRoomId returns null for non-matrix ids`() {
        assertNull("discord:123".toRoomId())
        assertNull("".toRoomId())
        // matrix: prefix with empty body — defensive guard against
        // a corrupted persisted id.
        assertNull("matrix:".toRoomId())
    }

    // ─── URL matching ─────────────────────────────────────────────────

    @Test
    fun `matchUrl claims matrix to room-id share link with high confidence`() {
        val source = source()
        val match = source.matchUrl("https://matrix.to/#/!storyvox:matrix.org")
        assertNotNull(match)
        assertEquals(SourceIds.MATRIX, match!!.sourceId)
        assertEquals(0.9f, match.confidence)
        assertEquals("Matrix room", match.label)
        // Fiction id must round-trip back to the original room id.
        assertEquals("!storyvox:matrix.org", match.fictionId.toRoomId())
    }

    @Test
    fun `matchUrl claims matrix to room-alias link with lower confidence`() {
        val source = source()
        val match = source.matchUrl("https://matrix.to/#/#storyvox:matrix.org")
        assertNotNull(match)
        assertEquals(SourceIds.MATRIX, match!!.sourceId)
        // Lower confidence: aliases need a server-side resolve to
        // a !roomid:server. v1 still claims them so the user can
        // pick Matrix as the route.
        assertEquals(0.7f, match.confidence)
    }

    @Test
    fun `matchUrl claims raw client-server API URL`() {
        val source = source()
        val match = source.matchUrl(
            "https://matrix.org/_matrix/client/v3/rooms/!storyvox:matrix.org/messages",
        )
        assertNotNull(match)
        assertEquals(0.9f, match!!.confidence)
        assertEquals("!storyvox:matrix.org", match.fictionId.toRoomId())
    }

    @Test
    fun `matchUrl returns null for non-matrix URLs`() {
        val source = source()
        assertNull(source.matchUrl("https://discord.com/channels/123/456"))
        assertNull(source.matchUrl("https://example.com/foo"))
        assertNull(source.matchUrl("not-a-url"))
        assertNull(source.matchUrl(""))
    }

    // ─── coalescing ───────────────────────────────────────────────────

    @Test
    fun `same sender within window coalesces into one group`() {
        // Three messages from alice, 60s apart. 5 min window
        // collapses all three into one chapter.
        val events = listOf(
            event("1", "@alice:matrix.org", 1715630000000L, "First thought"),
            event("2", "@alice:matrix.org", 1715630060000L, "Adding to that"),
            event("3", "@alice:matrix.org", 1715630120000L, "One more thing"),
        )

        val groups = coalesceMatrixEvents(events, coalesceMinutes = 5)

        assertEquals(1, groups.size)
        assertEquals("1", groups[0].headEvent.eventId)
        assertEquals(3, groups[0].events.size)
        // Title surfaces the head event preview after the sender;
        // the resolver here is "alice" because we pass a name in.
        val title = groups[0].title(resolvedSender = "Alice")
        assertTrue("title leads with the sender name", title.startsWith("Alice"))
        assertTrue("title carries the head event body", title.contains("First thought"))
    }

    @Test
    fun `same sender crossing window splits into separate groups`() {
        // alice posts at t=0 and t=60s (within 5 min window) →
        // group A. Then t=10min later → group B.
        val events = listOf(
            event("1", "@alice:matrix.org", 1715630000000L),
            event("2", "@alice:matrix.org", 1715630060000L),
            event("3", "@alice:matrix.org", 1715630660000L),
        )

        val groups = coalesceMatrixEvents(events, coalesceMinutes = 5)

        assertEquals(2, groups.size)
        assertEquals(listOf("1", "2"), groups[0].events.map { it.eventId })
        assertEquals(listOf("3"), groups[1].events.map { it.eventId })
    }

    @Test
    fun `different senders always split regardless of window`() {
        // Even with a 30-min window, alice → bob → alice → bob
        // produces 4 chapters — chat-thread shape.
        val events = listOf(
            event("1", "@alice:matrix.org", 1715630000000L),
            event("2", "@bob:matrix.org", 1715630001000L),
            event("3", "@alice:matrix.org", 1715630002000L),
            event("4", "@bob:matrix.org", 1715630003000L),
        )

        val groups = coalesceMatrixEvents(events, coalesceMinutes = 30)

        assertEquals(4, groups.size)
        assertEquals("@alice:matrix.org", groups[0].events.first().sender)
        assertEquals("@bob:matrix.org", groups[1].events.first().sender)
    }

    @Test
    fun `zero window disables coalescing`() {
        // Defensive guard: the Settings slider's minimum is 1 min,
        // but if a stored value drifts to 0 we don't want a crash.
        val events = listOf(
            event("1", "@alice:matrix.org", 1715630000000L),
            event("2", "@alice:matrix.org", 1715630001000L),
            event("3", "@alice:matrix.org", 1715630002000L),
        )

        val groups = coalesceMatrixEvents(events, coalesceMinutes = 0)

        assertEquals(3, groups.size)
        groups.forEach { assertEquals(1, it.events.size) }
    }

    @Test
    fun `empty input yields empty groups`() {
        assertEquals(emptyList<Any>(), coalesceMatrixEvents(emptyList(), coalesceMinutes = 5))
    }

    // ─── rendering ────────────────────────────────────────────────────

    @Test
    fun `media event surfaces filename via Attachment prefix in title and body`() {
        // m.image events carry the filename in `body` (per the
        // Matrix spec). The title preview prefixes "Attachment: "
        // so TTS narrates "Attachment: dragon-sketch.png" cleanly.
        val mediaEvent = event(
            id = "${'$'}img1",
            sender = "@alice:matrix.org",
            ts = 1715630000000L,
            body = "dragon-sketch.png",
            msgtype = "m.image",
        )
        val group = MatrixEventGroup(
            headEvent = mediaEvent,
            events = listOf(mediaEvent),
            headTimestampMillis = 1715630000000L,
        )

        val title = group.title(resolvedSender = "Alice")
        assertTrue("title surfaces attachment", title.contains("Attachment: dragon-sketch.png"))

        val html = group.toHtml { it }
        assertTrue(
            "html surfaces the attachment line",
            html.contains("<p>Attachment: dragon-sketch.png</p>"),
        )

        val plain = group.toPlainText { it }
        assertEquals("Attachment: dragon-sketch.png", plain)
    }

    @Test
    fun `text event renders as paragraph with html escaping`() {
        val textEvent = event(
            id = "${'$'}txt1",
            sender = "@alice:matrix.org",
            ts = 1715630000000L,
            body = "Hello <world> & friends",
        )
        val group = MatrixEventGroup(
            headEvent = textEvent,
            events = listOf(textEvent),
            headTimestampMillis = 1715630000000L,
        )

        val html = group.toHtml { it }
        assertEquals("<p>Hello &lt;world&gt; &amp; friends</p>", html)
        val plain = group.toPlainText { it }
        // Plain text keeps the original characters — escaping is
        // an HTML-rendering concern only.
        assertEquals("Hello <world> & friends", plain)
    }

    @Test
    fun `title falls back to bare sender id when display name is blank`() {
        val ev = event("1", "@alice:matrix.org", 1715630000000L, "Hello")
        val group = MatrixEventGroup(
            headEvent = ev,
            events = listOf(ev),
            headTimestampMillis = 1715630000000L,
        )
        // Empty resolved sender → fallback to the bare Matrix id.
        val title = group.title(resolvedSender = "")
        assertTrue("title leads with the bare matrix id", title.startsWith("@alice:matrix.org"))
    }

    // ─── helpers ──────────────────────────────────────────────────────

    private fun source(): MatrixSource {
        // The MatrixSource constructor takes a MatrixApi + a
        // MatrixConfig; matchUrl + the id helpers don't touch
        // either, so we build a real (empty) MatrixApi against an
        // OkHttp client that is never used. None of these tests
        // make an HTTP call, so no actual networking happens.
        val fakeConfig = object : `in`.jphe.storyvox.source.matrix.config.MatrixConfig {
            override val state =
                kotlinx.coroutines.flow.flowOf(
                    `in`.jphe.storyvox.source.matrix.config.MatrixConfigState(),
                )
            override suspend fun current() =
                `in`.jphe.storyvox.source.matrix.config.MatrixConfigState()
        }
        val api = `in`.jphe.storyvox.source.matrix.net.MatrixApi(
            client = okhttp3.OkHttpClient(),
            config = fakeConfig,
        )
        return MatrixSource(
            api = api,
            config = fakeConfig,
        )
    }
}
