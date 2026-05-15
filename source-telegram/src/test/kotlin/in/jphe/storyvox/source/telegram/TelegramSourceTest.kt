package `in`.jphe.storyvox.source.telegram

import `in`.jphe.storyvox.source.telegram.net.TelegramMessage
import `in`.jphe.storyvox.source.telegram.net.TelegramChat
import `in`.jphe.storyvox.source.telegram.net.TelegramDocument
import `in`.jphe.storyvox.source.telegram.net.TelegramAudio
import `in`.jphe.storyvox.source.telegram.net.TelegramPhotoSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #462 — unit coverage for the pure helpers in
 * [TelegramSource]. Network / cache flows are covered indirectly
 * through the Discord-style integration shape; here we pin the
 * input → output behavior of the in-source transform helpers.
 */
class TelegramSourceTest {

    @Test
    fun `fictionId round-trips through chatId`() {
        val id = telegramFictionId(-1001234567890L)
        assertEquals("telegram:-1001234567890", id)
        assertEquals(-1001234567890L, id.toChatId())
    }

    @Test
    fun `toChatId returns null for non-telegram prefix`() {
        assertNull("discord:123".toChatId())
        assertNull("".toChatId())
        assertNull("telegram:".toChatId())
    }

    @Test
    fun `toChatId returns null for handle form`() {
        // The handle form ("telegram:@username") doesn't carry a
        // numeric chat id — resolving it requires a getChat call.
        assertNull("telegram:@somechannel".toChatId())
    }

    @Test
    fun `chapterIdFor encodes msg prefix`() {
        assertEquals(
            "telegram:-1001234567890::msg-42",
            chapterIdFor("telegram:-1001234567890", 42L),
        )
    }

    @Test
    fun `titlePreview prefers text over caption`() {
        val msg = makeMessage(text = "Hello world", caption = "ignored")
        assertEquals("Hello world", msg.titlePreview())
    }

    @Test
    fun `titlePreview falls back to caption`() {
        val msg = makeMessage(text = null, caption = "Caption only")
        assertEquals("Caption only", msg.titlePreview())
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
    fun `titlePreview surfaces attachment filename when body blank`() {
        val msg = makeMessage(
            text = null,
            caption = null,
            document = TelegramDocument(fileId = "f1", fileName = "dragon-sketch.png"),
        )
        assertEquals("Attachment: dragon-sketch.png", msg.titlePreview())
    }

    @Test
    fun `titlePreview surfaces audio performer + title when present`() {
        val msg = makeMessage(
            text = null,
            audio = TelegramAudio(fileId = "a1", title = "Song", performer = "Artist", duration = 60),
        )
        assertEquals("Audio: Artist — Song", msg.titlePreview())
    }

    @Test
    fun `titlePreview falls back for fully empty post`() {
        val msg = makeMessage(text = null, caption = null)
        assertEquals("(empty post)", msg.titlePreview())
    }

    @Test
    fun `toPlainText concatenates body + attachments`() {
        val msg = makeMessage(
            text = "The body",
            document = TelegramDocument(fileId = "f1", fileName = "x.pdf"),
        )
        val plain = msg.toPlainText()
        assertTrue(plain.contains("The body"))
        assertTrue(plain.contains("Attachment: x.pdf"))
    }

    @Test
    fun `toHtml escapes ampersand and angle brackets`() {
        val msg = makeMessage(text = "Hello <world> & friends")
        val html = msg.toHtml()
        assertTrue(html.contains("&lt;world&gt;"))
        assertTrue(html.contains("&amp;"))
        assertTrue(!html.contains("<world>"))
    }

    @Test
    fun `url pattern matches t-me handle`() {
        val m = TELEGRAM_URL_PATTERN.matchEntire("https://t.me/storyvox_official")
        assertEquals("storyvox_official", m?.groupValues?.get(1))
    }

    @Test
    fun `url pattern matches telegram-me handle`() {
        val m = TELEGRAM_URL_PATTERN.matchEntire("https://telegram.me/somechan")
        assertEquals("somechan", m?.groupValues?.get(1))
    }

    @Test
    fun `url pattern matches handle with trailing message id`() {
        val m = TELEGRAM_URL_PATTERN.matchEntire("https://t.me/somechan/42")
        assertEquals("somechan", m?.groupValues?.get(1))
    }

    @Test
    fun `url pattern rejects non-telegram domain`() {
        val m = TELEGRAM_URL_PATTERN.matchEntire("https://discord.com/somechan")
        assertNull(m)
    }

    private fun makeMessage(
        messageId: Long = 1L,
        date: Long = 1_700_000_000L,
        chatId: Long = -1001234567890L,
        text: String? = null,
        caption: String? = null,
        document: TelegramDocument? = null,
        audio: TelegramAudio? = null,
        photo: List<TelegramPhotoSize>? = null,
    ): TelegramMessage =
        TelegramMessage(
            messageId = messageId,
            date = date,
            chat = TelegramChat(id = chatId, type = "channel", title = "Test Channel"),
            text = text,
            caption = caption,
            document = document,
            audio = audio,
            photo = photo,
        )
}
