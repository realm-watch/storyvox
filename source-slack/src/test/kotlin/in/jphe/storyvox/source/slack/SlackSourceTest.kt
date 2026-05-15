package `in`.jphe.storyvox.source.slack

import `in`.jphe.storyvox.source.slack.net.SlackFile
import `in`.jphe.storyvox.source.slack.net.SlackMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #454 — unit coverage for the pure helpers in [SlackSource].
 * Network / cache flows are covered indirectly through the
 * Discord-style integration shape; here we pin the input → output
 * behavior of the in-source transform helpers, the URL claim regex,
 * and the system-subtype filter.
 */
class SlackSourceTest {

    // ─── id round-trips ────────────────────────────────────────────

    @Test
    fun `fictionId round-trips through channelId`() {
        val id = slackFictionId("C0123ABCDEF")
        assertEquals("slack:C0123ABCDEF", id)
        assertEquals("C0123ABCDEF", id.toChannelId())
    }

    @Test
    fun `toChannelId returns null for non-slack prefix`() {
        assertNull("discord:123".toChannelId())
        assertNull("telegram:-100".toChannelId())
        assertNull("".toChannelId())
        assertNull("slack:".toChannelId())
    }

    @Test
    fun `toChannelId strips chapter suffix`() {
        // The chapter id form embeds the channel id followed by
        // `::ts-…`. Decoding the fiction id from a chapter id must
        // yield just the channel id.
        assertEquals(
            "C0123ABCDEF",
            "slack:C0123ABCDEF::ts-1747340531.123456".toChannelId(),
        )
    }

    @Test
    fun `chapterIdFor encodes ts prefix`() {
        assertEquals(
            "slack:C0123ABCDEF::ts-1747340531.123456",
            chapterIdFor("slack:C0123ABCDEF", "1747340531.123456"),
        )
    }

    // ─── URL matcher ───────────────────────────────────────────────
    // The matchUrl path on SlackSource delegates straight to
    // SLACK_ARCHIVE_URL_PATTERN; we test the regex shape here so
    // these tests don't have to instantiate a SlackSource (which
    // needs a SlackApi the test harness doesn't supply).

    @Test
    fun `SLACK_ARCHIVE_URL_PATTERN claims workspace-subdomain archive URL`() {
        val m = SLACK_ARCHIVE_URL_PATTERN.matchEntire(
            "https://techempower.slack.com/archives/C0123ABCDEF",
        )
        assertEquals("C0123ABCDEF", m?.groupValues?.get(1))
    }

    @Test
    fun `SLACK_ARCHIVE_URL_PATTERN claims message-permalink archive URL`() {
        // Slack's deep-link to a specific message has the
        // /p<TIMESTAMP> suffix. The matcher accepts the URL but
        // the source routes to the channel-level fiction (the
        // message anchor is preserved for a follow-up that lands
        // on the specific chapter).
        val m = SLACK_ARCHIVE_URL_PATTERN.matchEntire(
            "https://techempower.slack.com/archives/C0123ABCDEF/p1747340531123456",
        )
        assertEquals("C0123ABCDEF", m?.groupValues?.get(1))
        assertEquals("/p1747340531123456", m?.groupValues?.get(2))
    }

    @Test
    fun `SLACK_ARCHIVE_URL_PATTERN claims apex-host archive URL`() {
        val m = SLACK_ARCHIVE_URL_PATTERN.matchEntire("https://slack.com/archives/C0123ABCDEF")
        assertEquals("C0123ABCDEF", m?.groupValues?.get(1))
    }

    @Test
    fun `SLACK_ARCHIVE_URL_PATTERN rejects non-archive slack URLs`() {
        // Marketing pages, the apex landing page, the client app —
        // none should claim. The catch-all readability backend will
        // catch them.
        assertNull(SLACK_ARCHIVE_URL_PATTERN.matchEntire("https://slack.com/intl/en-us/"))
        assertNull(SLACK_ARCHIVE_URL_PATTERN.matchEntire("https://slack.com/"))
        assertNull(SLACK_ARCHIVE_URL_PATTERN.matchEntire("https://api.slack.com/methods/auth.test"))
        // The web-app URL grammar is intentionally NOT claimed —
        // see the SLACK_ARCHIVE_URL_PATTERN kdoc.
        assertNull(
            SLACK_ARCHIVE_URL_PATTERN.matchEntire(
                "https://app.slack.com/client/T012345/C0123ABCDEF",
            ),
        )
    }

    @Test
    fun `SLACK_ARCHIVE_URL_PATTERN rejects non-slack hosts`() {
        assertNull(SLACK_ARCHIVE_URL_PATTERN.matchEntire("https://example.com/archives/C123"))
        assertNull(SLACK_ARCHIVE_URL_PATTERN.matchEntire("https://discord.com/channels/123/456"))
    }

    // ─── system-subtype filter ────────────────────────────────────

    @Test
    fun `isUserContentMessage keeps regular user messages`() {
        val msg = makeMessage(subtype = null, text = "Hello!")
        assertTrue(msg.isUserContentMessage())
    }

    @Test
    fun `isUserContentMessage keeps bot messages`() {
        val msg = makeMessage(subtype = "bot_message", text = "PR opened")
        assertTrue(msg.isUserContentMessage())
    }

    @Test
    fun `isUserContentMessage keeps thread broadcasts`() {
        // Thread broadcasts are user-authored content re-posted to
        // the channel — keep them.
        val msg = makeMessage(subtype = "thread_broadcast", text = "Important update")
        assertTrue(msg.isUserContentMessage())
    }

    @Test
    fun `isUserContentMessage filters channel-join messages`() {
        assertFalse(makeMessage(subtype = "channel_join").isUserContentMessage())
        assertFalse(makeMessage(subtype = "channel_leave").isUserContentMessage())
        assertFalse(makeMessage(subtype = "group_join").isUserContentMessage())
        assertFalse(makeMessage(subtype = "group_leave").isUserContentMessage())
    }

    @Test
    fun `isUserContentMessage filters topic and pin events`() {
        assertFalse(makeMessage(subtype = "channel_topic").isUserContentMessage())
        assertFalse(makeMessage(subtype = "channel_purpose").isUserContentMessage())
        assertFalse(makeMessage(subtype = "channel_name").isUserContentMessage())
        assertFalse(makeMessage(subtype = "pinned_item").isUserContentMessage())
        assertFalse(makeMessage(subtype = "unpinned_item").isUserContentMessage())
    }

    // ─── title preview ─────────────────────────────────────────────

    @Test
    fun `titlePreview returns short text as-is`() {
        val msg = makeMessage(text = "Hello world")
        assertEquals("Hello world", msg.titlePreview())
    }

    @Test
    fun `titlePreview truncates long bodies`() {
        val long = "x".repeat(80)
        val msg = makeMessage(text = long)
        val title = msg.titlePreview()
        assertTrue("expected ellipsis", title.endsWith("…"))
        assertEquals(58, title.length) // 57 + ellipsis char
    }

    @Test
    fun `titlePreview collapses newlines`() {
        val msg = makeMessage(text = "Line one\nLine two\r\nLine three")
        assertEquals("Line one Line two  Line three", msg.titlePreview())
    }

    @Test
    fun `titlePreview surfaces attachment title when body blank`() {
        val msg = makeMessage(
            text = "",
            files = listOf(SlackFile(id = "F1", name = "sketch.png", title = "Dragon sketch")),
        )
        assertEquals("Attachment: Dragon sketch", msg.titlePreview())
    }

    @Test
    fun `titlePreview falls back to filename when title blank`() {
        val msg = makeMessage(
            text = "",
            files = listOf(SlackFile(id = "F1", name = "sketch.png", title = null)),
        )
        assertEquals("Attachment: sketch.png", msg.titlePreview())
    }

    @Test
    fun `titlePreview yields empty-marker when nothing present`() {
        val msg = makeMessage(text = "", files = null)
        assertEquals("(empty message)", msg.titlePreview())
    }

    // ─── body renderers ───────────────────────────────────────────

    @Test
    fun `toHtml escapes angle brackets`() {
        // Slack messages routinely include <@U012345> mentions and
        // <#C012345|channel-name> channel refs — they MUST render as
        // text, not as HTML elements that the reader view tries to
        // parse.
        val msg = makeMessage(text = "Hello <@U012345> see <#C012345|general>")
        val html = msg.toHtml()
        assertTrue(html.contains("&lt;@U012345&gt;"))
        assertTrue(html.contains("&lt;#C012345|general&gt;"))
        assertFalse("must not leave raw <@…> in output", html.contains("<@U012345>"))
    }

    @Test
    fun `toHtml lists attachment lines after text`() {
        val msg = makeMessage(
            text = "Check this out",
            files = listOf(SlackFile(id = "F1", name = "doc.pdf", title = null)),
        )
        val html = msg.toHtml()
        assertTrue(html.contains("<p>Check this out</p>"))
        assertTrue(html.contains("<p>Attachment: doc.pdf</p>"))
    }

    @Test
    fun `toPlainText narrates attachments naturally`() {
        val msg = makeMessage(
            text = "See the sketch",
            files = listOf(SlackFile(id = "F1", name = "sketch.png", title = "Dragon sketch")),
        )
        val plain = msg.toPlainText()
        assertTrue(plain.contains("See the sketch"))
        assertTrue(plain.contains("Attachment: Dragon sketch"))
    }

    @Test
    fun `toPlainText returns empty string when nothing to narrate`() {
        val msg = makeMessage(text = "", files = null)
        assertEquals("", msg.toPlainText())
    }

    // ─── ts parsing ────────────────────────────────────────────────

    @Test
    fun `tsMillis parses slack float-seconds string`() {
        // Slack's ts is "<unix_seconds>.<micros>". Converting via
        // Double preserves enough precision for chapter timestamps.
        val msg = makeMessage(ts = "1747340531.123456")
        // 1747340531.123456 seconds * 1000 = 1747340531123 millis
        // (with the trailing .456 microseconds rounded down).
        assertEquals(1747340531123L, msg.tsMillis())
    }

    @Test
    fun `tsMillis returns null on unparseable ts`() {
        val msg = makeMessage(ts = "")
        assertNull(msg.tsMillis())
        val msg2 = makeMessage(ts = "not-a-ts")
        assertNull(msg2.tsMillis())
    }

    // ─── helpers ──────────────────────────────────────────────────

    private fun makeMessage(
        ts: String = "1747340531.123456",
        subtype: String? = null,
        text: String = "msg",
        files: List<SlackFile>? = null,
    ): SlackMessage = SlackMessage(
        ts = ts,
        subtype = subtype,
        user = "U012345",
        text = text,
        files = files,
    )
}
