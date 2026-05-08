package `in`.jphe.storyvox.source.github

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.github.net.GitHubApi
import `in`.jphe.storyvox.source.github.registry.Registry
import `in`.jphe.storyvox.source.github.registry.RegistryEntry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests cover:
 *  - Stable contract surface (id, displayName) since both leak to
 *    UrlRouter routing and UI surface strings.
 *  - Browse calls (popular/latestUpdates/byGenre/genres) wired to the
 *    [Registry] as of step 3c — sort/filter/distinct semantics.
 *  - Stubs that *should* still throw (search, fictionDetail, chapter,
 *    followsList, setFollowed) — accidental Hilt binding before the
 *    next steps fill these in must fail loudly, not return empty.
 */
class GitHubSourceTest {

    @Test fun `sourceId is the stable github key`() {
        assertEquals("github", source().id)
    }

    @Test fun `displayName surfaces in UI strings and is stable`() {
        assertEquals("GitHub", source().displayName)
    }

    @Test fun `popular returns featured entries first, registry order within bands`() {
        val src = source(
            entry("github:o/a", title = "A", featured = false),
            entry("github:o/b", title = "B", featured = true),
            entry("github:o/c", title = "C", featured = false),
            entry("github:o/d", title = "D", featured = true),
        )
        val r = runBlocking { src.popular() } as FictionResult.Success
        val ids = r.value.items.map { it.id }
        // Featured (B, D) before non-featured (A, C); within each band
        // the curator's authored order is preserved.
        assertEquals(listOf("github:o/b", "github:o/d", "github:o/a", "github:o/c"), ids)
        assertEquals(1, r.value.page)
        assertEquals(false, r.value.hasNext)
    }

    @Test fun `latestUpdates sorts by addedAt descending, missing dates last`() {
        val src = source(
            entry("github:o/a", title = "A", addedAt = "2026-01-01"),
            entry("github:o/b", title = "B", addedAt = "2026-05-06"),
            entry("github:o/c", title = "C", addedAt = null),
            entry("github:o/d", title = "D", addedAt = "2026-03-15"),
        )
        val r = runBlocking { src.latestUpdates() } as FictionResult.Success
        val ids = r.value.items.map { it.id }
        assertEquals(listOf("github:o/b", "github:o/d", "github:o/a", "github:o/c"), ids)
    }

    @Test fun `byGenre matches tags case-insensitively and trims input`() {
        val src = source(
            entry("github:o/a", title = "A", tags = listOf("fantasy", "litrpg")),
            entry("github:o/b", title = "B", tags = listOf("sci-fi")),
            entry("github:o/c", title = "C", tags = listOf("FANTASY")),
        )
        val r = runBlocking { src.byGenre("  Fantasy  ") } as FictionResult.Success
        assertEquals(setOf("github:o/a", "github:o/c"), r.value.items.map { it.id }.toSet())
    }

    @Test fun `byGenre with blank input returns the full registry`() {
        val src = source(
            entry("github:o/a", title = "A", tags = listOf("fantasy")),
            entry("github:o/b", title = "B", tags = listOf("sci-fi")),
        )
        val r = runBlocking { src.byGenre("   ") } as FictionResult.Success
        assertEquals(2, r.value.items.size)
    }

    @Test fun `genres returns deduplicated lowercase union of tags`() {
        val src = source(
            entry("github:o/a", title = "A", tags = listOf("Fantasy", "litrpg")),
            entry("github:o/b", title = "B", tags = listOf("FANTASY", "Sci-Fi")),
        )
        val r = runBlocking { src.genres() } as FictionResult.Success
        assertEquals(listOf("fantasy", "litrpg", "sci-fi"), r.value)
    }

    @Test fun `page 2 short-circuits to empty hasNext-false page`() {
        val src = source(entry("github:o/a", title = "A"))
        val r = runBlocking { src.popular(page = 2) } as FictionResult.Success
        assertTrue(r.value.items.isEmpty())
        assertEquals(2, r.value.page)
        assertEquals(false, r.value.hasNext)
    }

    @Test fun `registry failure surfaces unchanged from popular`() {
        val src = sourceWithFailure(FictionResult.NetworkError(message = "no internet"))
        val r = runBlocking { src.popular() }
        assertTrue(r is FictionResult.NetworkError)
    }

    // ─── Still-stubbed surfaces ────────────────────────────────────────

    @Test fun `search still throws NotImplementedError`() {
        val src = source()
        assertThrows(NotImplementedError::class.java) {
            runBlocking {
                src.search(`in`.jphe.storyvox.data.source.model.SearchQuery(term = "anything"))
            }
        }
    }

    @Test fun `fictionDetail still throws NotImplementedError`() {
        val src = source()
        assertThrows(NotImplementedError::class.java) {
            runBlocking { src.fictionDetail("github:o/r") }
        }
    }

    @Test fun `chapter still throws NotImplementedError`() {
        val src = source()
        assertThrows(NotImplementedError::class.java) {
            runBlocking { src.chapter("github:o/r", "github:o/r:src/01.md") }
        }
    }

    @Test fun `followsList and setFollowed still throw NotImplementedError`() {
        val src = source()
        assertThrows(NotImplementedError::class.java) { runBlocking { src.followsList() } }
        assertThrows(NotImplementedError::class.java) {
            runBlocking { src.setFollowed("github:o/r", true) }
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    private fun source(vararg entries: RegistryEntry): GitHubSource =
        GitHubSource(
            api = GitHubApi(OkHttpClient()),
            registry = StaticRegistry(entries.toList()),
        )

    private fun sourceWithFailure(failure: FictionResult.Failure): GitHubSource =
        GitHubSource(
            api = GitHubApi(OkHttpClient()),
            registry = FailingRegistry(failure),
        )

    private fun entry(
        id: String,
        title: String,
        author: String = "an-author",
        featured: Boolean = false,
        addedAt: String? = null,
        tags: List<String> = emptyList(),
    ) = RegistryEntry(
        id = id,
        title = title,
        author = author,
        featured = featured,
        addedAt = addedAt,
        tags = tags,
    )

    /**
     * Test double: returns a fixed list, no network. Subclasses
     * [Registry] (which is `internal open` for exactly this reason)
     * so the constructor still accepts an OkHttpClient even though
     * we never use it.
     */
    private class StaticRegistry(
        private val entries: List<RegistryEntry>,
    ) : Registry(httpClient = OkHttpClient()) {
        override suspend fun entries(): FictionResult<List<RegistryEntry>> =
            FictionResult.Success(entries)
    }

    private class FailingRegistry(
        private val failure: FictionResult.Failure,
    ) : Registry(httpClient = OkHttpClient()) {
        override suspend fun entries(): FictionResult<List<RegistryEntry>> = failure
    }
}
