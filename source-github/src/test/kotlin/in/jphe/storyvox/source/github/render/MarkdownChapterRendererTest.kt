package `in`.jphe.storyvox.source.github.render

import `in`.jphe.storyvox.data.source.model.ChapterInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownChapterRendererTest {

    private val renderer = MarkdownChapterRenderer()
    private val info = ChapterInfo(
        id = "github:o/r:src/01-intro.md",
        sourceChapterId = "src/01-intro.md",
        index = 0,
        title = "Intro",
    )

    // ─── HTML rendering ────────────────────────────────────────────────

    @Test fun `paragraphs render to p tags`() {
        val out = renderer.render(info, "Hello world.\n\nA second paragraph.")
        assertTrue(out.htmlBody.contains("<p>Hello world.</p>"))
        assertTrue(out.htmlBody.contains("<p>A second paragraph.</p>"))
    }

    @Test fun `inline emphasis becomes em and strong tags`() {
        val out = renderer.render(info, "This is *italic* and **bold**.")
        assertTrue(out.htmlBody.contains("<em>italic</em>"))
        assertTrue(out.htmlBody.contains("<strong>bold</strong>"))
    }

    @Test fun `headings render with appropriate level`() {
        val out = renderer.render(info, "# H1\n## H2\n### H3\n\nbody")
        assertTrue(out.htmlBody.contains("<h1>H1</h1>"))
        assertTrue(out.htmlBody.contains("<h2>H2</h2>"))
        assertTrue(out.htmlBody.contains("<h3>H3</h3>"))
    }

    @Test fun `unordered lists render as ul`() {
        val out = renderer.render(info, "- one\n- two\n- three")
        assertTrue(out.htmlBody.contains("<ul>"))
        assertTrue(out.htmlBody.contains("<li>one</li>"))
        assertTrue(out.htmlBody.contains("<li>three</li>"))
    }

    @Test fun `ordered lists render as ol`() {
        val out = renderer.render(info, "1. first\n2. second")
        assertTrue(out.htmlBody.contains("<ol>"))
    }

    @Test fun `links render with href`() {
        val out = renderer.render(info, "Visit [the wiki](https://example.com/wiki).")
        assertTrue(out.htmlBody.contains("""<a href="https://example.com/wiki">the wiki</a>"""))
    }

    @Test fun `block quotes render as blockquote`() {
        val out = renderer.render(info, "> A quoted line.\n> Another.")
        assertTrue(out.htmlBody.contains("<blockquote>"))
    }

    @Test fun `inline html in markdown is escaped not passed through`() {
        // escapeHtml=true: a `<script>` tag in raw markdown body should
        // be rendered as visible text, never executable. Hardening for
        // untrusted author input.
        val out = renderer.render(info, "Sneaky <script>alert(1)</script> attempt.")
        assertFalse(out.htmlBody.contains("<script>"))
        assertTrue(out.htmlBody.contains("&lt;script&gt;"))
    }

    @Test fun `softbreak becomes br tag`() {
        // Markdown softbreaks (newline within a paragraph, not blank-
        // line-separated) become <br/> so the reader respects line
        // breaks the author intended.
        val out = renderer.render(info, "Line one\nLine two")
        assertTrue(out.htmlBody.contains("<br />"))
    }

    // ─── Plaintext extraction ──────────────────────────────────────────

    @Test fun `plain body strips html markup`() {
        val out = renderer.render(info, "**Bold** and *italic* together.")
        assertEquals("Bold and italic together.", out.plainBody)
    }

    @Test fun `plain body separates paragraphs with newlines`() {
        val out = renderer.render(info, "First.\n\nSecond.\n\nThird.")
        // commonmark TextContentRenderer joins paragraphs with newlines —
        // good enough boundary for SentenceChunker downstream.
        assertTrue(out.plainBody.contains("First."))
        assertTrue(out.plainBody.contains("Second."))
        assertTrue(out.plainBody.contains("Third."))
    }

    @Test fun `plain body drops link URL keeping anchor text`() {
        val out = renderer.render(info, "Check [the docs](https://example.com).")
        assertTrue("plain body should keep anchor text", out.plainBody.contains("the docs"))
        assertFalse("plain body should not read the URL aloud", out.plainBody.contains("example.com"))
    }

    @Test fun `plain body drops images entirely`() {
        // Images don't translate to audio. commonmark's TextContentRenderer
        // emits alt text for images by default — acceptable for accessibility,
        // and the reader gets to skip what they can't see.
        val out = renderer.render(info, "Before ![cover](cover.png) after.")
        assertFalse(out.plainBody.contains("cover.png"))
    }

    // ─── Heading-stripping in plain body ───────────────────────────────

    @Test fun `leading short heading line is stripped from plain body`() {
        val md = "# Master Elric\n\nIt was a dark and stormy night."
        val out = renderer.render(info, md)
        // Heading shouldn't be read aloud — title already in ChapterInfo.
        assertFalse(out.plainBody.startsWith("Master Elric"))
        assertTrue(out.plainBody.startsWith("It was a dark"))
        // HTML keeps the heading as a visual landmark.
        assertTrue(out.htmlBody.contains("<h1>Master Elric</h1>"))
    }

    @Test fun `opening sentence with terminal punctuation is preserved`() {
        // Body without a leading heading — first line ends with a period,
        // so it's clearly prose, not a heading. Don't strip it.
        val md = "It was a dark and stormy night.\n\nThunder rolled."
        val out = renderer.render(info, md)
        assertTrue(out.plainBody.startsWith("It was a dark"))
    }

    @Test fun `long first line is preserved as prose`() {
        // Lines over 80 chars are too long to be a heading. Keep them.
        val md = "${"x".repeat(100)}\n\nNext paragraph."
        val out = renderer.render(info, md)
        assertTrue(out.plainBody.startsWith("x".repeat(100)))
    }

    @Test fun `body with only a heading yields heading text in plain`() {
        // Edge case: file is just `# Heading` with no body. Don't strip
        // it — that would yield empty audio. Better to read the heading
        // than nothing.
        val out = renderer.render(info, "# The Brass Gate")
        assertTrue(out.plainBody.contains("The Brass Gate"))
    }

    // ─── Edge cases ────────────────────────────────────────────────────

    @Test fun `empty input yields empty html and plain bodies`() {
        val out = renderer.render(info, "")
        assertEquals("", out.htmlBody)
        assertEquals("", out.plainBody)
    }

    @Test fun `whitespace-only input yields empty bodies after trim`() {
        val out = renderer.render(info, "   \n\n   \n")
        assertEquals("", out.htmlBody)
        assertEquals("", out.plainBody)
    }

    @Test fun `chapter info round-trips unchanged through render`() {
        val customInfo = info.copy(title = "Custom Title", index = 7, wordCount = 1234)
        val out = renderer.render(customInfo, "Body.")
        assertEquals(customInfo, out.info)
    }

    @Test fun `notes fields default to null`() {
        val out = renderer.render(info, "Body.")
        // Author notes are surfaced by sources that have them (Royal Road
        // splits author-notes from chapter-body in HTML); GitHub markdown
        // doesn't have a notes convention, so they're always null here.
        assertEquals(null, out.notesAuthor)
        assertEquals(null, out.notesAuthorPosition)
    }
}
