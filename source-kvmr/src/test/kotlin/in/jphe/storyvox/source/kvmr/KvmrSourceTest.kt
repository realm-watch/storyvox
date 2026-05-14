package `in`.jphe.storyvox.source.kvmr

import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.SearchQuery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #374 — KVMR source unit tests. The source has zero external
 * I/O, so the test surface is pure data shape: does the live fiction
 * surface in popular(), does the chapter come back with the stream
 * URL, do unknown ids return NotFound, do irrelevant browse calls
 * stay empty.
 */
class KvmrSourceTest {

    private val source = KvmrSource()

    @Test fun `id and displayName match the catalog`() {
        assertEquals(SourceIds.KVMR, source.id)
        assertEquals("KVMR", source.displayName)
    }

    @Test fun `popular returns one live fiction on page 1`() = runTest {
        val result = source.popular(page = 1) as FictionResult.Success
        assertEquals(1, result.value.items.size)
        val item = result.value.items.first()
        assertEquals(KvmrSource.LIVE_FICTION_ID, item.id)
        assertEquals(SourceIds.KVMR, item.sourceId)
        assertEquals(1, item.chapterCount)
        assertEquals(false, result.value.hasNext)
    }

    @Test fun `popular returns empty past page 1`() = runTest {
        val result = source.popular(page = 2) as FictionResult.Success
        assertTrue(result.value.items.isEmpty())
        assertEquals(false, result.value.hasNext)
    }

    @Test fun `fictionDetail surfaces the single Live chapter`() = runTest {
        val result = source.fictionDetail(KvmrSource.LIVE_FICTION_ID) as FictionResult.Success
        assertEquals(1, result.value.chapters.size)
        assertEquals("Live", result.value.chapters.first().title)
    }

    @Test fun `fictionDetail returns NotFound for unknown id`() = runTest {
        val result = source.fictionDetail("kvmr:nonexistent")
        assertTrue(result is FictionResult.NotFound)
    }

    @Test fun `chapter returns the AAC stream URL with empty bodies`() = runTest {
        val result = source.chapter(
            KvmrSource.LIVE_FICTION_ID,
            KvmrSource.LIVE_CHAPTER_ID,
        ) as FictionResult.Success
        val content = result.value
        assertEquals(KvmrSource.STREAM_URL, content.audioUrl)
        assertEquals("", content.htmlBody)
        assertEquals("", content.plainBody)
    }

    @Test fun `chapter returns NotFound for unknown chapter id`() = runTest {
        val result = source.chapter(KvmrSource.LIVE_FICTION_ID, "bogus")
        assertTrue(result is FictionResult.NotFound)
    }

    @Test fun `latestUpdates mirrors popular`() = runTest {
        val popular = (source.popular() as FictionResult.Success).value.items
        val latest = (source.latestUpdates() as FictionResult.Success).value.items
        assertEquals(popular, latest)
    }

    @Test fun `byGenre returns empty`() = runTest {
        val result = source.byGenre("anything", page = 1) as FictionResult.Success
        assertTrue(result.value.items.isEmpty())
    }

    @Test fun `genres returns empty list`() = runTest {
        val result = source.genres() as FictionResult.Success
        assertTrue(result.value.isEmpty())
    }

    @Test fun `search matches kvmr substring case-insensitive`() = runTest {
        val result = source.search(SearchQuery(term = "KVMR")) as FictionResult.Success
        assertEquals(1, result.value.items.size)
    }

    @Test fun `search with empty term returns the live fiction`() = runTest {
        val result = source.search(SearchQuery(term = "")) as FictionResult.Success
        assertEquals(1, result.value.items.size)
    }

    @Test fun `search with unrelated term returns empty`() = runTest {
        val result = source.search(SearchQuery(term = "wattpad")) as FictionResult.Success
        assertTrue(result.value.items.isEmpty())
    }

    @Test fun `followsList is empty (no auth concept)`() = runTest {
        val result = source.followsList() as FictionResult.Success
        assertTrue(result.value.items.isEmpty())
    }

    @Test fun `setFollowed is a no-op success`() = runTest {
        val result = source.setFollowed(KvmrSource.LIVE_FICTION_ID, followed = true)
        assertTrue(result is FictionResult.Success)
    }

    @Test fun `stream URL is HTTPS`() {
        assertTrue(
            "Stream URL must be HTTPS (Media3 doesn't allow cleartext by default)",
            KvmrSource.STREAM_URL.startsWith("https://"),
        )
    }

    @Test fun `user-agent identifies storyvox`() {
        assertTrue(KvmrSource.USER_AGENT.contains("storyvox"))
        assertTrue(KvmrSource.USER_AGENT.contains("github.com/jphein/storyvox"))
    }
}
