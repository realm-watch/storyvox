package `in`.jphe.storyvox.source.rss

import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.rss.config.RssConfig
import `in`.jphe.storyvox.source.rss.config.RssSubscription
import `in`.jphe.storyvox.source.rss.net.RssFetcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #464 — magic-link integration: a pasted Craigslist URL should
 * be claimable by `RssSource.matchUrl` even when the URL doesn't carry
 * any of the generic feed markers (`/feed`, `?format=rss`, `.xml`,
 * etc). The matcher is the cheapest path for the user — paste any CL
 * search URL, get routed to the RSS pipeline.
 *
 * These tests don't need Android (no `android.util.Xml`); matchUrl is
 * pure-string. Kept separate from the fixture-parsing test so this
 * one runs without Robolectric overhead.
 */
class RssSourceCraigslistMatcherTest {

    /**
     * Minimal in-memory `RssConfig` so we can instantiate the real
     * `RssSource` without DataStore. Matcher logic doesn't touch
     * subscriptions, so the flow stays empty.
     */
    private class FakeConfig : RssConfig {
        override val subscriptions: Flow<List<RssSubscription>> = MutableStateFlow(emptyList())
        override suspend fun snapshot(): List<RssSubscription> = emptyList()
        override suspend fun addFeed(url: String) { /* no-op */ }
        override suspend fun removeFeed(fictionId: String) { /* no-op */ }
    }

    private val source = RssSource(
        config = FakeConfig(),
        // The matcher path doesn't invoke the fetcher; safe to pass an
        // okhttp client built from defaults via the same constructor
        // the prod DI binding uses. We hand-build one rather than
        // pull in Hilt for a test.
        fetcher = RssFetcher(okhttp3.OkHttpClient()),
    )

    @Test
    fun `matches a curated craigslist search URL even without format=rss`() {
        val m = source.matchUrl("https://sfbay.craigslist.org/search/zip")
        assertNotNull("Craigslist search URL must produce a route match", m)
        assertEquals(SourceIds.RSS, m!!.sourceId)
        // 0.7 = the documented "looks like a feed" tier.
        assertEquals(0.7f, m.confidence, 0.001f)
        // Label calls out Craigslist + region so the chooser modal is
        // distinguishable from a generic RSS hit.
        assertTrue(
            "label should mention Craigslist (got: ${m.label})",
            m.label.startsWith("Craigslist"),
        )
        assertTrue(
            "label should include region label (got: ${m.label})",
            m.label.contains("SF Bay Area"),
        )
    }

    @Test
    fun `matches a craigslist URL that already carries format=rss`() {
        val m = source.matchUrl("https://sacramento.craigslist.org/search/sss?format=rss")
        assertNotNull(m)
        assertTrue(m!!.label.contains("Sacramento"))
    }

    @Test
    fun `matches nevada city — JP's home region`() {
        // Per issue body — Nevada City is JP's home region; the
        // matcher must claim it. This is a smoke test that the
        // curated catalogue is wired to the matcher.
        val m = source.matchUrl("https://nevadacity.craigslist.org/search/apa")
        assertNotNull(m)
        assertTrue(m!!.label.contains("Nevada City"))
    }

    @Test
    fun `does not match an unknown craigslist subdomain`() {
        // We only claim curated regions — if the subdomain isn't on
        // our catalogue list we let the generic Readability fallback
        // (or another backend) handle it.
        val m = source.matchUrl("https://smallville.craigslist.org/search/sss")
        // smallville isn't curated — but the URL also doesn't match
        // the generic feed-pattern path. Expect null.
        assertNull(m)
    }

    @Test
    fun `still matches generic feed URLs at the original confidence`() {
        // Regression guard: the Craigslist branch must not break the
        // existing #472 path for non-CL feed URLs.
        val m = source.matchUrl("https://example.com/feed.xml")
        assertNotNull(m)
        assertEquals(0.7f, m!!.confidence, 0.001f)
        assertEquals("RSS / Atom feed", m.label)
    }

    @Test
    fun `non http URLs are still rejected`() {
        assertNull(source.matchUrl("ftp://sfbay.craigslist.org/search/zip"))
        assertNull(source.matchUrl("not a url"))
        assertNull(source.matchUrl(""))
    }

    @Test
    fun `fictionId is stable for the same URL across calls`() {
        val a = source.matchUrl("https://sfbay.craigslist.org/search/zip")!!
        val b = source.matchUrl("https://sfbay.craigslist.org/search/zip")!!
        assertEquals(a.fictionId, b.fictionId)
    }

    @Test
    fun `fictionId differs for different URLs`() {
        val a = source.matchUrl("https://sfbay.craigslist.org/search/zip")!!
        val b = source.matchUrl("https://sfbay.craigslist.org/search/sss")!!
        assertTrue("fictionIds should differ across categories", a.fictionId != b.fictionId)
    }
}
