package `in`.jphe.storyvox.source.notion

import `in`.jphe.storyvox.source.notion.config.NotionDefaults
import `in`.jphe.storyvox.source.notion.config.NotionMode
import `in`.jphe.storyvox.source.notion.net.NotionBlockEnvelope
import `in`.jphe.storyvox.source.notion.net.NotionRecordMap
import `in`.jphe.storyvox.source.notion.net.NotionUnofficialApi
import `in`.jphe.storyvox.source.notion.net.contentIds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #393 — focused unit tests for the anonymous-mode parsing
 * surface: recordMap envelope unwrapping, decoration-array title
 * extraction, page-id hyphenation, content[] traversal, chapter
 * spec resolution, and HTML/plain rendering of a TechEmpower-shaped
 * page body.
 *
 * No HTTP mocking — the network layer is exercised at integration
 * level. These tests pin the pure mapping logic that took the most
 * reverse-engineering effort.
 */
class AnonymousNotionDelegateTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ─── page-id hyphenation ──────────────────────────────────────────

    @Test
    fun `hyphenatePageId converts 32-hex to 8-4-4-4-12`() {
        assertEquals(
            "0959e445-9998-4143-acab-c80187305001",
            NotionUnofficialApi.hyphenatePageId("0959e44599984143acabc80187305001"),
        )
    }

    @Test
    fun `hyphenatePageId leaves hyphenated id unchanged`() {
        assertEquals(
            "0959e445-9998-4143-acab-c80187305001",
            NotionUnofficialApi.hyphenatePageId("0959e445-9998-4143-acab-c80187305001"),
        )
    }

    @Test
    fun `hyphenatePageId returns malformed input as-is`() {
        // The API will reject malformed ids; we don't try to be clever.
        assertEquals("not-a-uuid", NotionUnofficialApi.hyphenatePageId("not-a-uuid"))
        assertEquals("", NotionUnofficialApi.hyphenatePageId(""))
    }

    // ─── recordMap envelope unwrapping ────────────────────────────────

    @Test
    fun `findBlock unwraps envelope and accepts compact id`() {
        val rm = recordMapWith(
            "0959e445-9998-4143-acab-c80187305001" to """
                {"value":{"value":{"id":"0959e445-9998-4143-acab-c80187305001","type":"page","properties":{"title":[["Welcome"]]},"content":["a","b"]}}}
            """.trimIndent(),
        )
        // Compact 32-hex form — findBlock must hyphenate before lookup.
        val block = rm.findBlock("0959e44599984143acabc80187305001")
        assertNotNull(block)
        assertEquals("page", block!!.blockType())
        assertEquals("Welcome", readTitle(block))
    }

    @Test
    fun `findBlock returns null for unknown id`() {
        val rm = NotionRecordMap()
        assertNull(rm.findBlock("ffffffff-ffff-ffff-ffff-ffffffffffff"))
    }

    // ─── title + content traversal ────────────────────────────────────

    @Test
    fun `readTitle joins decoration array`() {
        val block = parseBlock("""
            {"properties":{"title":[["Hello ", []],["world", []],["!"]]}}
        """.trimIndent())
        assertEquals("Hello world!", readTitle(block))
    }

    @Test
    fun `readTitle returns null when title array missing`() {
        assertNull(readTitle(parseBlock("""{"properties":{}}""")))
    }

    @Test
    fun `contentIds yields child ids in display order`() {
        val block = parseBlock("""
            {"content":["a","b","c"]}
        """.trimIndent())
        assertEquals(listOf("a", "b", "c"), block.contentIds())
    }

    @Test
    fun `contentIds returns empty list when content missing`() {
        assertTrue(parseBlock("""{"type":"text"}""").contentIds().isEmpty())
    }

    // ─── collection_view metadata ─────────────────────────────────────

    @Test
    fun `collection_view exposes collection_id and first view`() {
        val block = parseBlock("""
            {
              "type":"collection_view",
              "collection_id":"8cb5379d-fe78-4a15-9f3a-d539f5a60387",
              "view_ids":["329a4ee6-9520-8199-bc52-000caf9e1475","329a4ee6-9520-8109-8eea-000c7c8208dc"]
            }
        """.trimIndent())
        assertEquals("8cb5379d-fe78-4a15-9f3a-d539f5a60387", block.collectionId())
        assertEquals("329a4ee6-9520-8199-bc52-000caf9e1475", block.firstViewId())
    }

    @Test
    fun `firstViewId returns null when view_ids missing`() {
        val block = parseBlock("""{"type":"collection_view","collection_id":"x"}""")
        assertNull(block.firstViewId())
    }

    // ─── chapter spec resolution ──────────────────────────────────────

    @Test
    fun `chapterSpecsFor returns TechEmpower chapters when root matches`() {
        val specs = chapterSpecsFor(
            rootPageId = NotionDefaults.TECHEMPOWER_ROOT_PAGE_ID,
            fictionPageId = NotionDefaults.TECHEMPOWER_ROOT_PAGE_ID,
        )
        assertEquals(4, specs.size)
        assertEquals("Guides", specs[0].title)
        assertTrue(specs[0] is ChapterSpec.Page)
        assertEquals("Resources", specs[1].title)
        assertTrue(specs[1] is ChapterSpec.Collection)
        assertEquals("About", specs[2].title)
        assertEquals("Donate", specs[3].title)
    }

    @Test
    fun `chapterSpecsFor tolerates hyphenated and compact id forms`() {
        val hyphenatedRoot = "0959e445-9998-4143-acab-c80187305001"
        val specs = chapterSpecsFor(
            rootPageId = hyphenatedRoot,
            fictionPageId = NotionDefaults.TECHEMPOWER_ROOT_PAGE_ID,
        )
        assertEquals(4, specs.size)
    }

    @Test
    fun `chapterSpecsFor returns generic chapter for non-TechEmpower roots`() {
        val specs = chapterSpecsFor(
            rootPageId = "deadbeefdeadbeefdeadbeefdeadbeef",
            fictionPageId = "deadbeefdeadbeefdeadbeefdeadbeef",
        )
        assertEquals(1, specs.size)
        assertEquals("Contents", specs[0].title)
        assertTrue(specs[0] is ChapterSpec.Page)
    }

    @Test
    fun `chapterSpecsFor returns empty when fiction id doesn't match root`() {
        val specs = chapterSpecsFor(
            rootPageId = NotionDefaults.TECHEMPOWER_ROOT_PAGE_ID,
            fictionPageId = "deadbeefdeadbeefdeadbeefdeadbeef",
        )
        assertTrue(specs.isEmpty())
    }

    // ─── page body rendering ──────────────────────────────────────────

    @Test
    fun `renderPageBody concatenates blocks and skips tombstones`() {
        val rm = recordMapWith(
            "root" to envelope("""{"type":"page","content":["t1","dead","h1","t2"]}"""),
            "t1" to envelope("""{"type":"text","properties":{"title":[["Lead paragraph."]]}}"""),
            "dead" to envelope("""{"type":"text","alive":false,"properties":{"title":[["Tombstone."]]}}"""),
            "h1" to envelope("""{"type":"header","properties":{"title":[["Section heading"]]}}"""),
            "t2" to envelope("""{"type":"text","properties":{"title":[["Second paragraph."]]}}"""),
        )
        val root = rm.findBlock("root")!!
        val (html, plain) = renderPageBody(rm, root)
        assertTrue(html.contains("<p>Lead paragraph.</p>"))
        assertTrue(html.contains("<h1>Section heading</h1>"))
        assertTrue(html.contains("<p>Second paragraph.</p>"))
        assertFalse(html.contains("Tombstone"))
        assertTrue(plain.contains("Lead paragraph."))
        assertTrue(plain.contains("Section heading"))
        assertFalse(plain.contains("Tombstone"))
    }

    @Test
    fun `renderPageBody embeds sub-page titles as bridge paragraphs`() {
        // Sub-pages stay reachable through their authored titles; the
        // chapter doesn't expand them inline (would balloon).
        val rm = recordMapWith(
            "root" to envelope("""{"type":"page","content":["intro","subp"]}"""),
            "intro" to envelope("""{"type":"text","properties":{"title":[["See our guides:"]]}}"""),
            "subp" to envelope("""{"type":"page","properties":{"title":[["How to use TechEmpower"]]}}"""),
        )
        val root = rm.findBlock("root")!!
        val (html, plain) = renderPageBody(rm, root)
        assertTrue(html.contains("<p><strong>How to use TechEmpower</strong></p>"))
        assertTrue(plain.contains("How to use TechEmpower"))
    }

    // ─── collection rendering ────────────────────────────────────────

    @Test
    fun `collectRowTitles pulls titled pages out of a recordMap and sorts them`() {
        val rm = recordMapWith(
            "row1" to envelope("""{"type":"page","properties":{"title":[["Cursor for Students"]]}}"""),
            "row2" to envelope("""{"type":"page","properties":{"title":[["1yr Google AI Pro free"]]}}"""),
            "rowdead" to envelope("""{"type":"page","alive":false,"properties":{"title":[["Expired offer"]]}}"""),
            "rownotitle" to envelope("""{"type":"page","properties":{}}"""),
        )
        val titles = collectRowTitles(rm)
        assertEquals(listOf("1yr Google AI Pro free", "Cursor for Students"), titles)
    }

    @Test
    fun `renderRowsAsHtml emits an unordered list`() {
        val html = renderRowsAsHtml("Resources", listOf("Alpha", "Bravo", "Charlie"))
        assertTrue(html.contains("<ul>"))
        assertTrue(html.contains("<li>Alpha</li>"))
        assertTrue(html.contains("<li>Bravo</li>"))
        assertTrue(html.contains("3 entries"))
    }

    @Test
    fun `renderRowsAsHtml handles empty collection`() {
        val html = renderRowsAsHtml("Resources", emptyList())
        assertTrue(html.contains("is empty"))
        assertFalse(html.contains("<ul>"))
    }

    @Test
    fun `renderRowsAsPlain emits readable text for TTS`() {
        val plain = renderRowsAsPlain("Resources", listOf("Alpha", "Bravo"))
        assertTrue(plain.contains("2 entries"))
        assertTrue(plain.contains("Alpha"))
        assertTrue(plain.contains("Bravo"))
    }

    // ─── block-type → HTML projection ─────────────────────────────────

    @Test
    fun `blockToHtml maps unofficial block types correctly`() {
        val rm = NotionRecordMap()
        assertEquals(
            "<p>Hello.</p>",
            blockToHtml(rm, parseBlock("""{"type":"text","properties":{"title":[["Hello."]]}}""")),
        )
        assertEquals(
            "<h1>Top heading</h1>",
            blockToHtml(rm, parseBlock("""{"type":"header","properties":{"title":[["Top heading"]]}}""")),
        )
        assertEquals(
            "<h2>Subhead</h2>",
            blockToHtml(rm, parseBlock("""{"type":"sub_header","properties":{"title":[["Subhead"]]}}""")),
        )
        assertEquals(
            "<h3>Sub-sub</h3>",
            blockToHtml(rm, parseBlock("""{"type":"sub_sub_header","properties":{"title":[["Sub-sub"]]}}""")),
        )
        assertEquals(
            "<li>Item</li>",
            blockToHtml(rm, parseBlock("""{"type":"bulleted_list","properties":{"title":[["Item"]]}}""")),
        )
        assertEquals(
            "<blockquote>Quoted</blockquote>",
            blockToHtml(rm, parseBlock("""{"type":"quote","properties":{"title":[["Quoted"]]}}""")),
        )
        assertEquals(
            "<aside>Note</aside>",
            blockToHtml(rm, parseBlock("""{"type":"callout","properties":{"title":[["Note"]]}}""")),
        )
        assertEquals(
            "<hr/>",
            blockToHtml(rm, parseBlock("""{"type":"divider"}""")),
        )
        // Unknown types render blank — the unofficial API has dozens
        // of block types and storyvox only narrates the textual subset.
        assertEquals(
            "",
            blockToHtml(rm, parseBlock("""{"type":"embed","properties":{"title":[["x"]]}}""")),
        )
    }

    @Test
    fun `htmlEscape escapes the four chars`() {
        // Sanity check — the existing official-mode helper is reused.
        assertEquals("&amp;&lt;&gt;&quot;", htmlEscape("&<>\""))
    }

    // ─── NotionConfigState mode posture ───────────────────────────────

    @Test
    fun `default NotionConfigState is anonymous`() {
        val s = `in`.jphe.storyvox.source.notion.config.NotionConfigState()
        assertEquals(NotionMode.ANONYMOUS_PUBLIC, s.mode)
        assertTrue(s.isConfigured) // root page id has a baked default
    }

    @Test
    fun `OFFICIAL_PAT mode requires both token and databaseId`() {
        val empty = `in`.jphe.storyvox.source.notion.config.NotionConfigState(
            mode = NotionMode.OFFICIAL_PAT,
            apiToken = "",
            databaseId = "",
        )
        assertFalse(empty.isConfigured)
        val tokenOnly = empty.copy(apiToken = "ntn_token")
        assertFalse(tokenOnly.isConfigured)
        val both = tokenOnly.copy(databaseId = "abc")
        assertTrue(both.isConfigured)
    }

    @Test
    fun `ANONYMOUS_PUBLIC mode requires only rootPageId`() {
        val withDefault = `in`.jphe.storyvox.source.notion.config.NotionConfigState(
            mode = NotionMode.ANONYMOUS_PUBLIC,
            apiToken = "",
        )
        // Default rootPageId is TechEmpower's; isConfigured is true.
        assertTrue(withDefault.isConfigured)
        val blank = withDefault.copy(rootPageId = "")
        assertFalse(blank.isConfigured)
    }

    // ─── test helpers ─────────────────────────────────────────────────

    private fun parseBlock(jsonText: String): JsonObject =
        json.parseToJsonElement(jsonText) as JsonObject

    private fun envelope(blockJson: String): String =
        """{"value":{"value":$blockJson}}"""

    private fun recordMapWith(vararg entries: Pair<String, String>): NotionRecordMap {
        val blocks = entries.associate { (id, envelopeJson) ->
            id to json.decodeFromString<NotionBlockEnvelope>(envelopeJson)
        }
        return NotionRecordMap(block = blocks)
    }
}
