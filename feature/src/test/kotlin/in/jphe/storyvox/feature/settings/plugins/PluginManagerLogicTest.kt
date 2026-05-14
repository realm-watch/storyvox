package `in`.jphe.storyvox.feature.settings.plugins

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePluginDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plugin manager (#404) — unit tests for the pure-function pieces of
 * the manager view-model (search/chip filter + category grouping).
 * Compose UI rendering is verified via the registry-iteration tests
 * in [PluginManagerCategoryRenderTest]; this file covers the data
 * transformation layer that doesn't need a Compose host.
 */
class PluginManagerLogicTest {

    private val fictionA = row(id = "rr", displayName = "Royal Road", description = "Web fiction", enabled = true)
    private val fictionB = row(id = "github", displayName = "GitHub fiction", description = "Repo READMEs", enabled = false)
    private val fictionC = row(
        id = "wikipedia",
        displayName = "Wikipedia",
        description = "Articles narrated",
        enabled = true,
    )
    private val audio = row(
        id = "kvmr",
        displayName = "KVMR",
        description = "Community radio",
        enabled = true,
        category = SourceCategory.AudioStream,
    )

    private val all = listOf(fictionA, fictionB, fictionC, audio)

    // ─── filterPlugins ──────────────────────────────────────────────

    @Test fun `On chip keeps only enabled plugins`() {
        val filtered = filterPlugins(all, query = "", chip = PluginFilterChip.On)
        assertEquals(listOf("rr", "wikipedia", "kvmr"), filtered.map { it.descriptor.id })
    }

    @Test fun `Off chip keeps only disabled plugins`() {
        val filtered = filterPlugins(all, query = "", chip = PluginFilterChip.Off)
        assertEquals(listOf("github"), filtered.map { it.descriptor.id })
    }

    @Test fun `All chip preserves the full list`() {
        val filtered = filterPlugins(all, query = "", chip = PluginFilterChip.All)
        assertEquals(all, filtered)
    }

    @Test fun `search matches display name substring case-insensitively`() {
        val filtered = filterPlugins(all, query = "WIKI", chip = PluginFilterChip.All)
        assertEquals(listOf("wikipedia"), filtered.map { it.descriptor.id })
    }

    @Test fun `search matches description substring`() {
        val filtered = filterPlugins(all, query = "radio", chip = PluginFilterChip.All)
        assertEquals(listOf("kvmr"), filtered.map { it.descriptor.id })
    }

    @Test fun `search matches plugin id`() {
        val filtered = filterPlugins(all, query = "github", chip = PluginFilterChip.All)
        assertEquals(listOf("github"), filtered.map { it.descriptor.id })
    }

    @Test fun `blank search keeps everything`() {
        val filtered = filterPlugins(all, query = "   ", chip = PluginFilterChip.All)
        assertEquals(all.size, filtered.size)
    }

    @Test fun `chip and search compose - On + radio gives only kvmr`() {
        val filtered = filterPlugins(all, query = "radio", chip = PluginFilterChip.On)
        assertEquals(listOf("kvmr"), filtered.map { it.descriptor.id })
    }

    @Test fun `chip and search compose - Off + radio gives empty`() {
        val filtered = filterPlugins(all, query = "radio", chip = PluginFilterChip.Off)
        assertTrue(filtered.isEmpty())
    }

    // ─── groupPluginsForManager ──────────────────────────────────────

    @Test fun `grouping splits by category`() {
        val sections = groupPluginsForManager(all)
        assertEquals(listOf("rr", "github", "wikipedia"), sections.fiction.map { it.descriptor.id })
        assertEquals(listOf("kvmr"), sections.audio.map { it.descriptor.id })
        assertTrue(sections.voiceBundles.isEmpty())
    }

    @Test fun `grouping preserves order within each category`() {
        val sections = groupPluginsForManager(all)
        assertEquals(
            "fiction should keep input order",
            listOf("rr", "github", "wikipedia"),
            sections.fiction.map { it.descriptor.id },
        )
    }

    @Test fun `grouping empty input yields empty buckets`() {
        val sections = groupPluginsForManager(emptyList())
        assertTrue(sections.fiction.isEmpty())
        assertTrue(sections.audio.isEmpty())
        assertTrue(sections.voiceBundles.isEmpty())
    }

    // ─── fixtures ────────────────────────────────────────────────────

    private fun row(
        id: String,
        displayName: String,
        description: String,
        enabled: Boolean,
        category: SourceCategory = SourceCategory.Text,
    ): PluginManagerRow = PluginManagerRow(
        descriptor = SourcePluginDescriptor(
            id = id,
            displayName = displayName,
            defaultEnabled = false,
            category = category,
            supportsFollow = false,
            supportsSearch = false,
            description = description,
            sourceUrl = "",
            source = FakeFictionSource(id),
        ),
        enabled = enabled,
    )

    private class FakeFictionSource(override val id: String) : FictionSource {
        override val displayName: String = id
        override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
            FictionResult.Success(ListPage(items = emptyList(), page = page, hasNext = false))
        override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> = popular(page)
        override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> = popular(page)
        override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> = popular(1)
        override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> = FictionResult.NotFound("fake")
        override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> = FictionResult.NotFound("fake")
        override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> = popular(page)
        override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> = FictionResult.Success(Unit)
        override suspend fun genres(): FictionResult<List<String>> = FictionResult.Success(emptyList())
    }
}
