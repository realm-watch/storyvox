package `in`.jphe.storyvox.source.arxiv

import `in`.jphe.storyvox.source.arxiv.net.ArxivAtomFeed
import `in`.jphe.storyvox.source.arxiv.net.extractArxivId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #378 — pins the Atom-feed parser against the canonical shape
 * arXiv emits from `export.arxiv.org/api/query`. Two-entry fixture
 * covers the post-2007 new-style id (`2401.12345`) and the pre-2007
 * archive-style id with embedded slash (`cs/0703021`). Both must
 * round-trip through [extractArxivId] without losing the slash.
 */
class ArxivAtomFeedTest {

    /**
     * Two-entry feed. Author and category blocks repeat per entry;
     * version suffixes (`v1`) on the `<id>` URL must be stripped by
     * [extractArxivId].
     */
    private val twoEntryFeed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom"
              xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">
          <title>arXiv Query: cat:cs.AI</title>
          <opensearch:totalResults>12345</opensearch:totalResults>
          <opensearch:startIndex>0</opensearch:startIndex>
          <opensearch:itemsPerPage>50</opensearch:itemsPerPage>
          <entry>
            <id>http://arxiv.org/abs/2401.12345v1</id>
            <updated>2024-01-22T18:00:00Z</updated>
            <published>2024-01-22T18:00:00Z</published>
            <title>A Snappy Title About Transformers &amp; Attention</title>
            <summary>
              We propose a snappy new method for attention. It is good.
              Multiple lines fold to one.
            </summary>
            <author><name>Alice Smith</name></author>
            <author><name>Bob Jones</name></author>
            <link rel="alternate" type="text/html" href="http://arxiv.org/abs/2401.12345v1"/>
            <link title="pdf" rel="related" type="application/pdf"
                  href="http://arxiv.org/pdf/2401.12345v1"/>
            <category term="cs.AI" scheme="http://arxiv.org/schemas/atom"/>
            <category term="cs.LG" scheme="http://arxiv.org/schemas/atom"/>
          </entry>
          <entry>
            <id>http://arxiv.org/abs/cs/0703021v2</id>
            <updated>2007-03-22T12:00:00Z</updated>
            <title>An Old Archive-Style Paper</title>
            <summary>A short paper from the cs/ archive era.</summary>
            <author><name>Carol Tau</name></author>
            <link rel="alternate" type="text/html" href="http://arxiv.org/abs/cs/0703021v2"/>
            <category term="cs.DM" scheme="http://arxiv.org/schemas/atom"/>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `parses both entries with stripped version suffixes`() {
        val feed = ArxivAtomFeed.parse(twoEntryFeed)
        assertEquals(2, feed.entries.size)
        // totalResults from the opensearch extension survives the parse.
        assertEquals(12345L, feed.totalResults)

        val first = feed.entries[0]
        assertEquals("2401.12345", first.arxivId)
        // Title decodes XML entities and collapses internal whitespace.
        assertEquals("A Snappy Title About Transformers & Attention", first.title)
        // Multi-line summary collapses to one line.
        assertTrue(first.summary.startsWith("We propose a snappy new method"))
        assertTrue(first.summary.endsWith("fold to one."))
        // Author list preserves document order.
        assertEquals(listOf("Alice Smith", "Bob Jones"), first.authors)
        // Categories captured in order.
        assertEquals(listOf("cs.AI", "cs.LG"), first.categories)
        assertEquals("http://arxiv.org/abs/2401.12345v1", first.absUrl)
        assertEquals("http://arxiv.org/pdf/2401.12345v1", first.pdfUrl)

        val second = feed.entries[1]
        // Archive-style id with embedded slash survives.
        assertEquals("cs/0703021", second.arxivId)
        assertEquals("An Old Archive-Style Paper", second.title)
        assertEquals(listOf("Carol Tau"), second.authors)
        // PDF link absent on the second entry; nullable.
        assertNull(second.pdfUrl)
    }

    @Test
    fun `empty feed returns empty entry list without throwing`() {
        val emptyFeed = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>arXiv Query: cat:cs.zz.zz</title>
              <opensearch:totalResults xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">0</opensearch:totalResults>
            </feed>
        """.trimIndent()
        val feed = ArxivAtomFeed.parse(emptyFeed)
        assertTrue(feed.entries.isEmpty())
        assertEquals(0L, feed.totalResults)
    }

    @Test
    fun `extractArxivId handles new-style, archive-style, and malformed inputs`() {
        // New-style with version
        assertEquals("2401.12345", extractArxivId("http://arxiv.org/abs/2401.12345v1"))
        // New-style without version
        assertEquals("2401.12345", extractArxivId("http://arxiv.org/abs/2401.12345"))
        // Archive-style with slash and version
        assertEquals("cs/0703021", extractArxivId("http://arxiv.org/abs/cs/0703021v2"))
        // Archive-style without version
        assertEquals("math.AG/0307014", extractArxivId("http://arxiv.org/abs/math.AG/0307014"))
        // Malformed — no /abs/ segment
        assertEquals("", extractArxivId("http://arxiv.org/something-else"))
        assertEquals("", extractArxivId(""))
    }
}
