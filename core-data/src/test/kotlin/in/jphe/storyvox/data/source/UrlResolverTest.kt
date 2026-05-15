package `in`.jphe.storyvox.data.source

import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePluginDescriptor
import `in`.jphe.storyvox.data.source.plugin.SourcePluginRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #472 — covers the resolver's cascade across:
 *  - legacy [UrlRouter] regex bank (RoyalRoad / GitHub) → confidence 1.0
 *  - per-plugin [UrlMatcher] implementations → confidence 0.7-0.95
 *  - the Readability catch-all → confidence 0.1
 *
 * Each backend is represented by a [FakeFictionSource] that implements
 * a UrlMatcher with the expected regex pattern + confidence. Real source
 * modules can't be linked from `:core-data` tests, but the matcher
 * contract is the same — when this test is green the production
 * Resolver's cascade behaviour is exercised end-to-end.
 */
class UrlResolverTest {

    @Test fun `empty url resolves to empty list`() {
        val resolver = UrlResolver(registryOf())
        assertTrue(resolver.resolve("").isEmpty())
        assertTrue(resolver.resolve("   ").isEmpty())
    }

    @Test fun `royalroad URL hits legacy UrlRouter at confidence 1`() {
        val resolver = UrlResolver(registryOf())
        val result = resolver.resolve("https://www.royalroad.com/fiction/12345")
        assertEquals(1, result.size)
        assertEquals(SourceIds.ROYAL_ROAD, result[0].sourceId)
        assertEquals("12345", result[0].fictionId)
        assertEquals(1.0f, result[0].confidence, 0.0001f)
    }

    @Test fun `github URL hits legacy UrlRouter at confidence 1`() {
        val resolver = UrlResolver(registryOf())
        val result = resolver.resolve("https://github.com/torvalds/linux")
        assertEquals(1, result.size)
        assertEquals(SourceIds.GITHUB, result[0].sourceId)
        assertEquals("github:torvalds/linux", result[0].fictionId)
    }

    @Test fun `plugin UrlMatcher claims its host with the declared confidence`() {
        val arxivPlugin = fakeFictionSource(
            id = SourceIds.ARXIV,
            displayName = "arXiv",
            matchPattern = """^https?://arxiv\.org/abs/([\w./-]+)$""",
            confidence = 0.95f,
            label = "arXiv paper",
            idPrefix = "arxiv:",
        )
        val resolver = UrlResolver(registryOf(arxivPlugin))
        val result = resolver.resolve("https://arxiv.org/abs/2401.12345")
        assertEquals(1, result.size)
        assertEquals(SourceIds.ARXIV, result[0].sourceId)
        assertEquals("arxiv:2401.12345", result[0].fictionId)
        assertEquals(0.95f, result[0].confidence, 0.0001f)
    }

    @Test fun `readability catch-all matches any http URL at lowest confidence`() {
        val readability = readabilityPlugin()
        val resolver = UrlResolver(registryOf(readability))
        val result = resolver.resolve("https://example.com/article/about-x")
        assertEquals(1, result.size)
        assertEquals(SourceIds.READABILITY, result[0].sourceId)
        assertEquals(0.1f, result[0].confidence, 0.0001f)
    }

    @Test fun `specialised plugin always wins over readability catch-all`() {
        val arxiv = fakeFictionSource(
            id = SourceIds.ARXIV,
            displayName = "arXiv",
            matchPattern = """^https?://arxiv\.org/abs/([\w./-]+)$""",
            confidence = 0.95f,
            label = "arXiv paper",
            idPrefix = "arxiv:",
        )
        val readability = readabilityPlugin()
        val resolver = UrlResolver(registryOf(arxiv, readability))
        val matches = resolver.resolve("https://arxiv.org/abs/2401.12345")
        // Both claim it — but arxiv at 0.95 outranks readability at 0.1.
        assertEquals(2, matches.size)
        assertEquals(SourceIds.ARXIV, matches[0].sourceId)
        assertEquals(SourceIds.READABILITY, matches[1].sourceId)
    }

    @Test fun `chooser threshold filters out readability`() {
        val a = fakeFictionSource(
            id = "alpha",
            displayName = "Alpha",
            matchPattern = """^https?://alpha\.com/.*""",
            confidence = 0.8f,
            label = "Alpha",
            idPrefix = "alpha:",
        )
        val b = fakeFictionSource(
            id = "beta",
            displayName = "Beta",
            matchPattern = """^https?://alpha\.com/.*""",
            confidence = 0.75f,
            label = "Beta",
            idPrefix = "beta:",
        )
        val readability = readabilityPlugin()
        val resolver = UrlResolver(registryOf(a, b, readability))
        val chooser = resolver.resolveConfidentMatches("https://alpha.com/abc")
        assertEquals(2, chooser.size)
        // Readability is excluded (under 0.5 threshold).
        assertTrue(chooser.none { it.sourceId == SourceIds.READABILITY })
    }

    @Test fun `resolveBest picks the highest confidence`() {
        val plugin = fakeFictionSource(
            id = SourceIds.PLOS,
            displayName = "PLOS",
            matchPattern = """^https?://journals\.plos\.org/.*""",
            confidence = 0.95f,
            label = "PLOS article",
            idPrefix = "plos:",
        )
        val readability = readabilityPlugin()
        val resolver = UrlResolver(registryOf(plugin, readability))
        val best = resolver.resolveBest("https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0000000")
        assertNotNull(best)
        assertEquals(SourceIds.PLOS, best!!.sourceId)
    }

    @Test fun `non-http garbage resolves to empty even with readability registered`() {
        val readability = readabilityPlugin()
        val resolver = UrlResolver(registryOf(readability))
        assertTrue(resolver.resolve("just typing words here").isEmpty())
        assertTrue(resolver.resolve("ftp://nope").isEmpty())
    }

    @Test fun `plugin matcher returning null does not contribute`() {
        val plugin = fakeFictionSource(
            id = SourceIds.AO3,
            displayName = "AO3",
            matchPattern = """^https?://archiveofourown\.org/works/(\d+).*""",
            confidence = 0.95f,
            label = "AO3 work",
            idPrefix = "ao3:",
        )
        val resolver = UrlResolver(registryOf(plugin))
        // URL doesn't match — plugin's matchUrl returns null.
        val result = resolver.resolve("https://example.com/article")
        assertTrue(result.isEmpty())
    }

    @Test fun `legacy duplicate is deduped against plugin matcher`() {
        // RoyalRoad already claims via UrlRouter at confidence 1.0 —
        // if RoyalRoadSource someday implements UrlMatcher too, the
        // resolver dedupes by sourceId and keeps the legacy entry.
        val rrPlugin = fakeFictionSource(
            id = SourceIds.ROYAL_ROAD,
            displayName = "Royal Road",
            matchPattern = """^https?://www\.royalroad\.com/fiction/(\d+).*""",
            confidence = 0.9f,
            label = "Royal Road fiction (plugin)",
            idPrefix = "rr:",
        )
        val resolver = UrlResolver(registryOf(rrPlugin))
        val result = resolver.resolve("https://www.royalroad.com/fiction/12345")
        assertEquals(1, result.size)
        // Legacy entry at confidence 1.0 wins; the plugin's would-be
        // entry is suppressed by the dedupe-by-sourceId rule.
        assertEquals(1.0f, result[0].confidence, 0.0001f)
        assertEquals("12345", result[0].fictionId)
    }

    // ─── helpers ──────────────────────────────────────────────────────

    private fun registryOf(vararg sources: FakeFictionSource): SourcePluginRegistry =
        SourcePluginRegistry(
            sources.map { s ->
                SourcePluginDescriptor(
                    id = s.id,
                    displayName = s.displayName,
                    defaultEnabled = true,
                    category = SourceCategory.Text,
                    supportsFollow = false,
                    supportsSearch = false,
                    source = s,
                )
            }.toSet(),
        )

    private fun fakeFictionSource(
        id: String,
        displayName: String,
        matchPattern: String,
        confidence: Float,
        label: String,
        idPrefix: String,
    ): FakeFictionSource {
        val regex = Regex(matchPattern, RegexOption.IGNORE_CASE)
        return FakeFictionSource(id, displayName) { url ->
            val m = regex.matchEntire(url) ?: return@FakeFictionSource null
            val captured = m.groupValues.getOrNull(1).orEmpty().ifBlank { "x" }
            RouteMatch(
                sourceId = id,
                fictionId = "$idPrefix$captured",
                confidence = confidence,
                label = label,
            )
        }
    }

    private fun readabilityPlugin(): FakeFictionSource =
        FakeFictionSource(SourceIds.READABILITY, "Readability") { url ->
            if (!url.startsWith("http://") && !url.startsWith("https://")) return@FakeFictionSource null
            RouteMatch(
                sourceId = SourceIds.READABILITY,
                fictionId = "readability:${url.hashCode().toString(16)}",
                confidence = 0.1f,
                label = "Article via Readability",
            )
        }
}

/**
 * Stand-in [FictionSource] + [UrlMatcher] implementation that lets
 * tests construct registries without needing the real source modules
 * on the classpath. All FictionSource methods return empty/no-op
 * results — the resolver only ever reaches for `matchUrl`.
 */
private class FakeFictionSource(
    override val id: String,
    override val displayName: String,
    private val matcher: (String) -> RouteMatch?,
) : FictionSource, UrlMatcher {

    override fun matchUrl(url: String): RouteMatch? = matcher(url)

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(emptyList(), 1, false))

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        popular(page)

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> = popular(page)

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> =
        popular(1)

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> =
        FictionResult.NotFound("fake")

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> = FictionResult.NotFound("fake")

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        popular(page)

    override suspend fun setFollowed(
        fictionId: String,
        followed: Boolean,
    ): FictionResult<Unit> = FictionResult.Success(Unit)

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())
}
