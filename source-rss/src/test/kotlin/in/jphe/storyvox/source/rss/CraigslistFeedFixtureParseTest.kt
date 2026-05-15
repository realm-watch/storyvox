package `in`.jphe.storyvox.source.rss

import `in`.jphe.storyvox.source.rss.parse.RssParser
import `in`.jphe.storyvox.source.rss.templates.CraigslistTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #464 — integration test: a real-shaped Craigslist `?format=rss`
 * response (RDF / RSS 1.0) must round-trip through [RssParser] and
 * produce items whose titles read cleanly when narrated by TTS.
 *
 * Why Robolectric: the parser uses `android.util.Xml.newPullParser()`,
 * which isn't on the plain-JUnit classpath. The fixture lives at
 * `src/test/resources/craigslist-free-stuff.rss` so feed-shape drift
 * (Craigslist ever changing their RDF emission) surfaces at CI rather
 * than on the user's tablet at 2 AM.
 */
@RunWith(RobolectricTestRunner::class)
class CraigslistFeedFixtureParseTest {

    private val fixture: String = readFixture("craigslist-free-stuff.rss")

    @Test
    fun `parses RDF feed title and items from a Craigslist-shaped fixture`() {
        val feed = RssParser.parse(fixture)

        // Channel title surfaces verbatim — the source uses this as the
        // fiction title when hydrating (see RssSource.fictionDetail).
        assertTrue(
            "feed title should mention 'Bay Area' (got: ${feed.title})",
            feed.title.contains("Bay Area"),
        )
        assertEquals("https://sfbay.craigslist.org/search/zip", feed.link)
        assertEquals(3, feed.items.size)
    }

    @Test
    fun `each item has a non-blank title, link, and parseable description`() {
        val feed = RssParser.parse(fixture)
        for ((i, item) in feed.items.withIndex()) {
            assertTrue("item[$i] has blank title", item.title.isNotBlank())
            assertTrue("item[$i] has blank link", !item.link.isNullOrBlank())
            // Description / htmlBody must include the listing body — the
            // RDF feed wraps it in <![CDATA[]]> and embeds basic HTML
            // tags, which we keep for the htmlBody field (the source
            // does the strip-to-plaintext on the TTS path).
            assertTrue("item[$i] htmlBody is blank", item.htmlBody.isNotBlank())
        }
    }

    @Test
    fun `dc colon date parses to a non-null epoch`() {
        // RDF feeds use <dc:date> (Dublin Core ISO 8601). The parser
        // tries pubDate first, then dc:date — both routes must populate
        // publishedAtEpochMs.
        val feed = RssParser.parse(fixture)
        val firstItem = feed.items.first()
        assertNotNull(
            "First item's <dc:date> should parse — got null",
            firstItem.publishedAtEpochMs,
        )
    }

    @Test
    fun `item rdf about resolves to the canonical chapter id`() {
        // The fixture sets rdf:about on each <item>. The parser falls
        // back to it for the item id when <link> is absent; verify the
        // happy path where both are present picks the <link> value.
        val feed = RssParser.parse(fixture)
        for (item in feed.items) {
            assertTrue(
                "Item id (${item.id}) should be the link URL",
                item.id.startsWith("https://sfbay.craigslist.org/"),
            )
        }
    }

    @Test
    fun `composeFeedUrl for sfbay free-stuff matches the fixture's channel URL`() {
        // Ties the curated catalogue to the fixture — if the URL
        // composition rule changes, the integration test fails fast.
        val sfbay = CraigslistTemplates.REGIONS.first { it.slug == "sfbay" }
        val free = CraigslistTemplates.CATEGORIES.first { it.slug == "zip" }
        val url = CraigslistTemplates.composeFeedUrl(sfbay, free)
        assertEquals("https://sfbay.craigslist.org/search/zip?format=rss", url)
    }

    private fun readFixture(name: String): String {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
            ?: error("Fixture not on test classpath: $name")
        return stream.bufferedReader().use { it.readText() }
    }
}
