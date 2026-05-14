package `in`.jphe.storyvox.data.source.plugin

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plugin-seam Phase 1 (#384) — unit tests for [SourcePluginRegistry].
 *
 * Verifies the public contract documented in the registry's kdoc:
 * - Empty input → empty `all`, `byId` returns null, `byCategory` empty.
 * - Sort order is `category.ordinal` then `displayName` case-insensitive.
 * - `byCategory` filters correctly.
 * - `byId` resolves to the right descriptor.
 *
 * Constructed directly with fakes — no Hilt / no KSP — per the
 * "test hook" pattern documented on [SourcePluginRegistry].
 */
class SourcePluginRegistryTest {

    @Test fun `empty registry surfaces no plugins`() {
        val registry = SourcePluginRegistry(emptySet())

        assertTrue(registry.all.isEmpty())
        assertEquals(emptyList<String>(), registry.ids)
        assertFalse(registry.isNotEmpty)
        assertNull(registry.byId("anything"))
        assertTrue(registry.byCategory(SourceCategory.Text).isEmpty())
    }

    @Test fun `single descriptor is surfaced and lookable by id`() {
        val descriptor = descriptor(id = "kvmr", displayName = "KVMR", category = SourceCategory.AudioStream)
        val registry = SourcePluginRegistry(setOf(descriptor))

        assertEquals(listOf(descriptor), registry.all)
        assertEquals(listOf("kvmr"), registry.ids)
        assertTrue(registry.isNotEmpty)
        assertEquals(descriptor, registry.byId("kvmr"))
        assertNull(registry.byId("notion"))
        assertEquals(listOf(descriptor), registry.byCategory(SourceCategory.AudioStream))
        assertTrue(registry.byCategory(SourceCategory.Text).isEmpty())
    }

    @Test fun `sort order is by category ordinal then displayName case-insensitive`() {
        // Text category comes BEFORE AudioStream (enum order).
        // Within Text, "ao3" < "GitHub" < "Royal Road" lowercased.
        val ao3 = descriptor(id = "ao3", displayName = "ao3", category = SourceCategory.Text)
        val github = descriptor(id = "github", displayName = "GitHub", category = SourceCategory.Text)
        val rr = descriptor(id = "rr", displayName = "Royal Road", category = SourceCategory.Text)
        val kvmr = descriptor(id = "kvmr", displayName = "KVMR", category = SourceCategory.AudioStream)

        // Intentionally feed in non-sorted order — registry should sort.
        val registry = SourcePluginRegistry(setOf(kvmr, rr, ao3, github))

        assertEquals(listOf("ao3", "github", "rr", "kvmr"), registry.ids)
    }

    @Test fun `byCategory respects sort within category`() {
        val a = descriptor(id = "a", displayName = "Apple", category = SourceCategory.Ebook)
        val b = descriptor(id = "b", displayName = "banana", category = SourceCategory.Ebook)
        val c = descriptor(id = "c", displayName = "Cherry", category = SourceCategory.Ebook)

        val registry = SourcePluginRegistry(setOf(c, a, b))

        assertEquals(listOf("a", "b", "c"), registry.byCategory(SourceCategory.Ebook).map { it.id })
    }

    @Test fun `descriptor carries the live FictionSource instance`() {
        val source = FakeFictionSource(id = "fake")
        val descriptor = SourcePluginDescriptor(
            id = "fake",
            displayName = "Fake",
            defaultEnabled = false,
            category = SourceCategory.Other,
            supportsFollow = false,
            supportsSearch = false,
            source = source,
        )

        val registry = SourcePluginRegistry(setOf(descriptor))

        val found = registry.byId("fake")
        assertNotNull(found)
        assertEquals(source, found!!.source)
    }

    // ─── fixtures ─────────────────────────────────────────────────

    private fun descriptor(
        id: String,
        displayName: String,
        category: SourceCategory,
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
        override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
            popular(page)
        override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> =
            popular(page)
        override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> =
            popular(1)
        override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> =
            FictionResult.NotFound("fake")
        override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> =
            FictionResult.NotFound("fake")
        override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> = popular(page)
        override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
            FictionResult.Success(Unit)
        override suspend fun genres(): FictionResult<List<String>> = FictionResult.Success(emptyList())
    }
}
