package `in`.jphe.storyvox.source.gutenberg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #442 — Gutenberg chapter playback hangs at 0:00 buffering.
 *
 * Root cause: `String.stripTags()` was a permissive `<[^>]+>` regex
 * that left the *contents* of `<head>`, `<script>`, and `<style>`
 * blocks in the output. PG EPUB spine entries are full HTML documents
 * with embedded stylesheets (PG ships per-book CSS); the first spine
 * entry on most books (`Letter I` on Frankenstein, the title page on
 * many others) carries a substantial inline `<style>` block. That
 * meant the synth queue saw the CSS body text instead of the prose,
 * and Piper sat synthesising punctuation-heavy gibberish while the
 * user saw `state=PLAYING, position=0` indefinitely.
 *
 * These tests pin the stripping contract:
 *  - `<head>`, `<script>`, `<style>`, and HTML comments are removed
 *    along with their contents.
 *  - Visible `<body>` text survives.
 *  - Whitespace is collapsed.
 *  - Case-insensitive (EPUB documents in the wild use both `<HEAD>`
 *    and `<head>`).
 */
class StripTagsTest {

    @Test
    fun `head block contents are stripped not preserved`() {
        // Pre-#442 the head's <style> + <meta> survived as inline
        // text because only the angle brackets were removed.
        val html = """
            <html>
              <head>
                <title>Frankenstein</title>
                <style>body { color: black; font-family: serif; }</style>
                <meta name="generator" content="Project Gutenberg"/>
              </head>
              <body>
                <h1>Letter I</h1>
                <p>To Mrs. Saville, England.</p>
              </body>
            </html>
        """.trimIndent()

        val stripped = html.stripTags()

        // The visible text must be present.
        assertTrue(
            "Body text 'Letter I' missing from stripped output: $stripped",
            stripped.contains("Letter I"),
        )
        assertTrue(stripped.contains("To Mrs. Saville, England."))
        // Title text from <head> must NOT leak through.
        assertFalse(
            "Title 'Frankenstein' should not appear in stripped body: $stripped",
            stripped.contains("Frankenstein"),
        )
        // Style block contents must NOT leak through — this is the
        // #442 regression. CSS like "body { color: black }" being
        // read by Piper produces the synth-stall.
        assertFalse(
            "CSS body { color: black } leaked into stripped text: $stripped",
            stripped.contains("color:"),
        )
        assertFalse(
            "Generator meta leaked into stripped text: $stripped",
            stripped.contains("Project Gutenberg"),
        )
    }

    @Test
    fun `script and style blocks are stripped case-insensitively`() {
        // EPUB documents in the wild use both <STYLE> and <style>.
        val html = """
            <HTML>
              <HEAD>
                <STYLE TYPE="text/css">p { margin: 0; }</STYLE>
                <SCRIPT>alert('hi');</SCRIPT>
              </HEAD>
              <BODY>
                <P>Real text.</P>
              </BODY>
            </HTML>
        """.trimIndent()

        val stripped = html.stripTags()

        assertTrue(stripped.contains("Real text."))
        assertFalse(stripped.contains("margin"))
        assertFalse(stripped.contains("alert"))
    }

    @Test
    fun `HTML comments are stripped`() {
        // Standard Ebooks-derived PG EPUBs sometimes carry editorial
        // comments. Without explicit comment stripping they survive
        // (`<[^>]+>` matches `<!-- … -->` only because of the leading
        // angle bracket; the closing `-->` doesn't always get matched
        // as one token).
        val html = "<body><!-- editor note: see SE issue --><p>The text.</p></body>"

        val stripped = html.stripTags()

        assertTrue(stripped.contains("The text."))
        assertFalse(stripped.contains("editor note"))
    }

    @Test
    fun `body-only document keeps every paragraph`() {
        val html = """
            <body>
              <h1>Chapter 1</h1>
              <p>I was so much pleased.</p>
              <p>I shall depart from Russia.</p>
            </body>
        """.trimIndent()

        val stripped = html.stripTags()

        assertTrue(stripped.contains("Chapter 1"))
        assertTrue(stripped.contains("I was so much pleased."))
        assertTrue(stripped.contains("I shall depart from Russia."))
    }

    @Test
    fun `whitespace is collapsed to single spaces`() {
        val html = "<p>One.</p>\n\n<p>Two.</p>"

        val stripped = html.stripTags()

        // No newline runs, no double spaces, no surrounding whitespace.
        assertFalse(stripped.contains("\n"))
        assertFalse(stripped.contains("  "))
        assertEquals("One. Two.", stripped)
    }

    @Test
    fun `empty input returns empty string`() {
        assertEquals("", "".stripTags())
    }

    @Test
    fun `tag-only input returns empty string`() {
        // Pre-#442 this would have returned " " (a stripped space)
        // which then survived the trim. Some PG spine entries are
        // pure metadata pages with no visible body content; #442's
        // EnginePlayer guard surfaces a typed error when the chunker
        // sees zero sentences, but the stripping contract here pins
        // the upstream "we don't produce whitespace-only text" rule.
        assertEquals("", "<head><title>only</title></head>".stripTags())
    }
}
