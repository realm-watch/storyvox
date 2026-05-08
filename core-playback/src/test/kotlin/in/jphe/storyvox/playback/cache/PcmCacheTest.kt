package `in`.jphe.storyvox.playback.cache

import android.app.Application
import `in`.jphe.storyvox.playback.tts.Sentence
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * PcmCache exercises Context.cacheDir + DataStore (via PcmCacheConfig);
 * Robolectric supplies both on the JVM unit-test classpath. Same shape as
 * VoiceManagerTest — direct instantiation with the vanilla Application
 * context, no Hilt bootstrap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PcmCacheTest {

    private lateinit var context: Application
    private lateinit var cache: PcmCache
    private lateinit var config: PcmCacheConfig

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        config = PcmCacheConfig(context)
        cache = PcmCache(context, config)
        // Wipe between tests so leftover entries from one don't poison
        // another's eviction count.
        cache.rootDirectory().listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        cache.rootDirectory().listFiles()?.forEach { it.delete() }
    }

    private val key1 = PcmCacheKey("ch1", "cori", 100, 100, 1)
    private val key2 = PcmCacheKey("ch2", "cori", 100, 100, 1)
    private val key3 = PcmCacheKey("ch3", "cori", 100, 100, 1)

    private fun renderInto(key: PcmCacheKey, bytes: Int) {
        val app = cache.appender(key, sampleRate = 22050)
        app.appendSentence(
            Sentence(0, 0, 10, "Sentence."),
            ByteArray(bytes) { 0x44 },
            trailingSilenceMs = 350,
        )
        app.finalize()
    }

    @Test
    fun `isComplete is false until finalize lands`() {
        assertFalse(cache.isComplete(key1))
        val app = cache.appender(key1, sampleRate = 22050)
        app.appendSentence(Sentence(0, 0, 10, "Hi."), ByteArray(50), trailingSilenceMs = 350)
        // pre-finalize: pcm + meta exist, but idx doesn't
        assertFalse(cache.isComplete(key1))
        app.finalize()
        assertTrue(cache.isComplete(key1))
    }

    @Test
    fun `delete removes all three files`() = runBlocking {
        renderInto(key1, bytes = 100)
        assertTrue(cache.isComplete(key1))
        cache.delete(key1)
        assertFalse(cache.isComplete(key1))
        assertFalse(cache.pcmFileFor(key1).exists())
        assertFalse(cache.metaFileFor(key1).exists())
        assertFalse(cache.indexFileFor(key1).exists())
    }

    @Test
    fun `totalSizeBytes sums pcm files only`() = runBlocking {
        renderInto(key1, bytes = 1_000)
        renderInto(key2, bytes = 2_500)
        assertEquals(3_500L, cache.totalSizeBytes())
    }

    @Test
    fun `evictTo removes oldest pcm entries first`() = runBlocking {
        renderInto(key1, bytes = 1_000)
        // Make key1 demonstrably older than key2/key3 so the LRU
        // ordering is unambiguous despite mtime granularity.
        cache.pcmFileFor(key1).setLastModified(System.currentTimeMillis() - 10_000)
        renderInto(key2, bytes = 1_000)
        cache.pcmFileFor(key2).setLastModified(System.currentTimeMillis() - 5_000)
        renderInto(key3, bytes = 1_000)
        cache.pcmFileFor(key3).setLastModified(System.currentTimeMillis())

        // Quota = 2_500, current total = 3_000 → must evict at least 1
        // entry (≥ 500 bytes' worth), and the oldest by mtime is key1.
        val evicted = cache.evictTo(quotaBytes = 2_500L)

        assertTrue("expected at least 1 eviction, got $evicted", evicted >= 1)
        assertFalse("oldest (key1) should be evicted first", cache.isComplete(key1))
        assertTrue("key2 should survive", cache.isComplete(key2))
        assertTrue("key3 should survive", cache.isComplete(key3))
        assertTrue(cache.totalSizeBytes() <= 2_500L)
    }

    @Test
    fun `evictTo skips pinned entries even if they are oldest`() = runBlocking {
        renderInto(key1, bytes = 1_000)
        cache.pcmFileFor(key1).setLastModified(System.currentTimeMillis() - 10_000)
        renderInto(key2, bytes = 1_000)
        cache.pcmFileFor(key2).setLastModified(System.currentTimeMillis() - 5_000)
        renderInto(key3, bytes = 1_000)

        // Pin key1 (the oldest) → eviction must take from key2 instead.
        val evicted = cache.evictTo(
            quotaBytes = 1_500L,
            pinnedBasenames = setOf(key1.fileBaseName()),
        )

        assertTrue(evicted >= 1)
        assertTrue("pinned key1 must survive", cache.isComplete(key1))
    }

    @Test
    fun `evictTo is a no-op when under quota`() = runBlocking {
        renderInto(key1, bytes = 100)
        renderInto(key2, bytes = 100)
        val evicted = cache.evictTo(quotaBytes = 1_000_000L)
        assertEquals(0, evicted)
        assertTrue(cache.isComplete(key1))
        assertTrue(cache.isComplete(key2))
    }

    @Test
    fun `deleteAllForChapter removes every voice variant`() = runBlocking {
        val ch1Cori = PcmCacheKey("ch1", "cori", 100, 100, 1)
        val ch1Amy  = PcmCacheKey("ch1", "amy",  100, 100, 1)
        val ch2Cori = PcmCacheKey("ch2", "cori", 100, 100, 1)
        renderInto(ch1Cori, bytes = 100)
        renderInto(ch1Amy,  bytes = 100)
        renderInto(ch2Cori, bytes = 100)

        cache.deleteAllForChapter("ch1")

        assertFalse(cache.isComplete(ch1Cori))
        assertFalse(cache.isComplete(ch1Amy))
        assertTrue("ch2 unaffected", cache.isComplete(ch2Cori))
    }

    @Test
    fun `touch updates pcm mtime`() = runBlocking {
        renderInto(key1, bytes = 100)
        // Drop mtime back so touch's effect is visible above filesystem
        // mtime granularity (some filesystems round to 1-2 s).
        val oldMtime = System.currentTimeMillis() - 60_000
        cache.pcmFileFor(key1).setLastModified(oldMtime)
        cache.touch(key1)
        val after = cache.pcmFileFor(key1).lastModified()
        assertTrue(
            "touch must move mtime forward; was $oldMtime, now $after",
            after > oldMtime,
        )
    }

    @Test
    fun `clearAll wipes everything under the root`() = runBlocking {
        renderInto(key1, bytes = 100)
        renderInto(key2, bytes = 100)
        cache.clearAll()
        assertEquals(0, cache.rootDirectory().listFiles()?.size ?: 0)
    }

    @Test
    fun `appender for a different sample rate writes that sample rate to meta`() = runBlocking {
        val app = cache.appender(key1, sampleRate = 24_000)
        app.appendSentence(Sentence(0, 0, 10, "Hi."), ByteArray(50), trailingSilenceMs = 350)
        app.finalize()

        val meta = pcmCacheJson.decodeFromString(
            PcmMeta.serializer(),
            cache.metaFileFor(key1).readText(),
        )
        assertEquals(24_000, meta.sampleRate)

        val idx = pcmCacheJson.decodeFromString(
            PcmIndex.serializer(),
            cache.indexFileFor(key1).readText(),
        )
        assertEquals(24_000, idx.sampleRate)
    }
}
