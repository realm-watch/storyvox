package `in`.jphe.storyvox.source.notion

import `in`.jphe.storyvox.source.notion.net.NotionBlock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #233 — focused unit tests for the parsing surface of
 * [NotionSource]: rich-text extraction, heading-split chapter
 * boundaries, summary projection. No HTTP mocking; the API layer is
 * exercised at the integration level via in-app sign-in flows.
 */
class NotionSourceTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ─── fiction id encoding ──────────────────────────────────────────

    @Test
    fun `fiction id strips hyphens from page id`() {
        assertEquals(
            "notion:abcdef1234567890abcdef1234567890",
            notionFictionId("abcdef12-3456-7890-abcd-ef1234567890"),
        )
    }

    @Test
    fun `fiction id round-trips through toPageId`() {
        val fid = notionFictionId("abcdef12-3456-7890-abcd-ef1234567890")
        assertEquals("abcdef1234567890abcdef1234567890", fid.toPageId())
    }

    @Test
    fun `toPageId returns null for non-notion ids`() {
        assertNull("royalroad:12345".toPageId())
        assertNull("github:foo/bar".toPageId())
        assertNull("plain-string".toPageId())
    }

    @Test
    fun `chapter id composes deterministically`() {
        val fid = notionFictionId("abcdef1234567890abcdef1234567890")
        assertEquals(
            "notion:abcdef1234567890abcdef1234567890::section-3",
            chapterIdFor(fid, 3),
        )
    }

    // ─── heading_1 chapter split ──────────────────────────────────────

    @Test
    fun `splitOnHeading1 with no headings returns one Introduction section`() {
        val blocks = listOf(paragraphBlock("Hello world."))
        val sections = splitOnHeading1(blocks)
        assertEquals(1, sections.size)
        assertEquals("Introduction", sections[0].title)
        assertEquals(1, sections[0].blocks.size)
    }

    @Test
    fun `splitOnHeading1 with a leading heading_1 has no Introduction`() {
        val blocks = listOf(
            heading1Block("Chapter One"),
            paragraphBlock("Body."),
        )
        val sections = splitOnHeading1(blocks)
        assertEquals(1, sections.size)
        assertEquals("Chapter One", sections[0].title)
        // The heading_1 itself is dropped from the chapter body.
        assertEquals(1, sections[0].blocks.size)
        assertEquals("paragraph", sections[0].blocks[0].type)
    }

    @Test
    fun `splitOnHeading1 splits on every heading_1 boundary`() {
        val blocks = listOf(
            paragraphBlock("Lead paragraph."),
            heading1Block("Chapter One"),
            paragraphBlock("One body."),
            heading1Block("Chapter Two"),
            paragraphBlock("Two body."),
            paragraphBlock("Two more body."),
        )
        val sections = splitOnHeading1(blocks)
        assertEquals(3, sections.size)
        assertEquals("Introduction", sections[0].title)
        assertEquals(1, sections[0].blocks.size)
        assertEquals("Chapter One", sections[1].title)
        assertEquals(1, sections[1].blocks.size)
        assertEquals("Chapter Two", sections[2].title)
        assertEquals(2, sections[2].blocks.size)
    }

    @Test
    fun `splitOnHeading1 on empty input still produces one section`() {
        val sections = splitOnHeading1(emptyList())
        assertEquals(1, sections.size)
        assertEquals("Introduction", sections[0].title)
        assertTrue(sections[0].blocks.isEmpty())
    }

    // ─── rich-text extraction ────────────────────────────────────────

    @Test
    fun `joinRichText concatenates plain_text segments`() {
        val payload = json.parseToJsonElement(
            """
            {
              "rich_text": [
                {"plain_text": "Hello, "},
                {"plain_text": "world"},
                {"plain_text": "!"}
              ]
            }
            """.trimIndent(),
        )
        assertEquals("Hello, world!", extractRichText(payload))
    }

    @Test
    fun `extractRichText returns null for blank text`() {
        val payload = json.parseToJsonElement(
            """{"rich_text": [{"plain_text": ""}, {"plain_text": ""}]}""",
        )
        assertNull(extractRichText(payload))
    }

    @Test
    fun `extractRichText returns null for missing array`() {
        val payload = json.parseToJsonElement("""{}""")
        assertNull(extractRichText(payload))
    }

    // ─── property extraction ─────────────────────────────────────────

    @Test
    fun `extractTitleProperty finds the title regardless of property name`() {
        val properties = mapOf(
            "Custom Title Field" to json.parseToJsonElement(
                """
                {
                  "type": "title",
                  "title": [{"plain_text": "My Article"}]
                }
                """.trimIndent(),
            ),
        )
        assertEquals("My Article", extractTitleProperty(properties))
    }

    @Test
    fun `extractTitleProperty returns null when no title property exists`() {
        val properties = mapOf(
            "Description" to json.parseToJsonElement(
                """
                {
                  "type": "rich_text",
                  "rich_text": [{"plain_text": "no title here"}]
                }
                """.trimIndent(),
            ),
        )
        assertNull(extractTitleProperty(properties))
    }

    @Test
    fun `extractDescriptionProperty prefers named description property`() {
        val properties = mapOf(
            "Description" to richTextProperty("The right one"),
            "Notes" to richTextProperty("The fallback one"),
        )
        assertEquals("The right one", extractDescriptionProperty(properties))
    }

    @Test
    fun `extractDescriptionProperty falls back to any rich_text property`() {
        val properties = mapOf(
            "Notes" to richTextProperty("Found via fallback"),
        )
        assertEquals("Found via fallback", extractDescriptionProperty(properties))
    }

    @Test
    fun `extractTagsProperty pulls names from multi_select`() {
        val properties = mapOf(
            "Tags" to json.parseToJsonElement(
                """
                {
                  "type": "multi_select",
                  "multi_select": [
                    {"name": "Engineering"},
                    {"name": "Design"}
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("Engineering", "Design"), extractTagsProperty(properties))
    }

    @Test
    fun `extractAuthorProperty handles people property`() {
        val properties = mapOf(
            "Author" to json.parseToJsonElement(
                """
                {
                  "type": "people",
                  "people": [{"name": "Jane Doe"}, {"name": "John Roe"}]
                }
                """.trimIndent(),
            ),
        )
        assertEquals("Jane Doe, John Roe", extractAuthorProperty(properties))
    }

    // ─── html / plain-text rendering ─────────────────────────────────

    @Test
    fun `paragraph renders to p tag`() {
        val block = paragraphBlock("Hello.")
        assertEquals("<p>Hello.</p>", block.toHtml())
        assertEquals("Hello.", block.toPlainText())
    }

    @Test
    fun `divider renders to hr`() {
        val block = NotionBlock(id = "d", type = "divider")
        assertEquals("<hr/>", block.toHtml())
        assertEquals("", block.toPlainText())
    }

    @Test
    fun `unsupported block type renders blank`() {
        val block = NotionBlock(id = "x", type = "embed")
        assertEquals("", block.toHtml())
        assertEquals("", block.toPlainText())
    }

    @Test
    fun `htmlEscape escapes the four chars storyvox renders`() {
        assertEquals("&amp;&lt;&gt;&quot;", htmlEscape("&<>\""))
    }

    // ─── test helpers ────────────────────────────────────────────────

    /** Test fixtures use simple ASCII strings — no embedded quotes or
     *  backslashes — so a naive `"<text>"` interpolation produces valid
     *  JSON. Keeps the test setup free of serializer-API plumbing. */
    private fun paragraphBlock(text: String): NotionBlock = NotionBlock(
        id = "p-$text",
        type = "paragraph",
        paragraph = json.parseToJsonElement(
            """{"rich_text": [{"plain_text": "$text"}]}""",
        ),
    )

    private fun heading1Block(text: String): NotionBlock = NotionBlock(
        id = "h1-$text",
        type = "heading_1",
        heading1 = json.parseToJsonElement(
            """{"rich_text": [{"plain_text": "$text"}]}""",
        ),
    )

    private fun richTextProperty(text: String): JsonElement = json.parseToJsonElement(
        """{"type": "rich_text", "rich_text": [{"plain_text": "$text"}]}""",
    )
}
