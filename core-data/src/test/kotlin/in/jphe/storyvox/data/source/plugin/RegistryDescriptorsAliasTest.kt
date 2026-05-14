package `in`.jphe.storyvox.data.source.plugin

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plugin-seam Phase 3 (#384) — verifies the new `descriptors` alias
 * on [SourcePluginRegistry] returns the same sorted list as the
 * historical `all` accessor and exposes the new `description` /
 * `sourceUrl` fields when present.
 */
class RegistryDescriptorsAliasTest {

    @Test fun `descriptors alias mirrors all in registry sort order`() {
        val a = descriptor(id = "a", displayName = "alpha")
        val b = descriptor(id = "b", displayName = "beta")
        val c = descriptor(id = "c", displayName = "gamma")
        val registry = SourcePluginRegistry(setOf(c, a, b))

        assertEquals(registry.all, registry.descriptors)
        assertEquals(listOf("a", "b", "c"), registry.descriptors.map { it.id })
    }

    @Test fun `descriptors carries description and sourceUrl when set`() {
        val descriptor = SourcePluginDescriptor(
            id = "rr",
            displayName = "Royal Road",
            defaultEnabled = true,
            category = SourceCategory.Text,
            supportsFollow = true,
            supportsSearch = true,
            description = "Web fiction · cookie sign-in for Follows + premium chapters",
            sourceUrl = "https://www.royalroad.com",
            source = FakeFictionSource("rr"),
        )
        val registry = SourcePluginRegistry(setOf(descriptor))

        val resolved = registry.byId("rr")
        assertSame(descriptor, resolved)
        assertEquals("Web fiction · cookie sign-in for Follows + premium chapters", resolved!!.description)
        assertEquals("https://www.royalroad.com", resolved.sourceUrl)
    }

    @Test fun `descriptor defaults description and sourceUrl to empty strings`() {
        val descriptor = SourcePluginDescriptor(
            id = "x",
            displayName = "X",
            defaultEnabled = false,
            category = SourceCategory.Text,
            supportsFollow = false,
            supportsSearch = false,
            source = FakeFictionSource("x"),
        )

        assertEquals("", descriptor.description)
        assertEquals("", descriptor.sourceUrl)
    }

    @Test fun `descriptors set is empty when no plugins are registered`() {
        val registry = SourcePluginRegistry(emptySet())
        assertTrue(registry.descriptors.isEmpty())
    }

    private fun descriptor(
        id: String,
        displayName: String,
        category: SourceCategory = SourceCategory.Text,
    ): SourcePluginDescriptor = SourcePluginDescriptor(
        id = id,
        displayName = displayName,
        defaultEnabled = false,
        category = category,
        supportsFollow = false,
        supportsSearch = false,
        source = FakeFictionSource(id),
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
