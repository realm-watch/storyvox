package `in`.jphe.storyvox.playback.tts.source

import android.app.Application
import `in`.jphe.storyvox.playback.cache.PcmCache
import `in`.jphe.storyvox.playback.cache.PcmCacheConfig
import `in`.jphe.storyvox.playback.cache.PcmCacheKey
import `in`.jphe.storyvox.playback.tts.CHUNKER_VERSION
import `in`.jphe.storyvox.playback.tts.Sentence
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.FileOutputStream

/**
 * PR-E (#86) — read-side semantics for [CacheFileSource].
 *
 * Robolectric-backed because [PcmCache] touches `Context.cacheDir`
 * (same shape as PR-D's [EngineStreamingSourceCacheTeeTest]).
 *
 * Verifies:
 *  - Sequential [CacheFileSource.nextChunk] returns sentences in
 *    order with byte-for-byte equality to what [PcmAppender] wrote.
 *  - [PcmIndexEntry.trailingSilenceMs] propagates to [PcmChunk.trailingSilenceBytes].
 *  - [CacheFileSource.seekToCharOffset] jumps to the correct sentence
 *    (with edge cases: before first, past last, mid-sentence).
 *  - [CacheFileSource.bufferHeadroomMs] reports [Long.MAX_VALUE]
 *    (cached chapters can't underrun).
 *  - [CacheFileSource.close] releases the file descriptor.
 *  - Truncated `.pcm` causes [CacheFileSource.open] to throw
 *    [java.io.IOException] (corrupt cache → re-render, not crash).
 *  - `startSentenceIndex` resumes mid-chapter (post-seek path).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CacheFileSourceTest {

    private lateinit var context: Application
    private lateinit var cache: PcmCache
    private lateinit var config: PcmCacheConfig

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        config = PcmCacheConfig(context)
        cache = PcmCache(context, config)
        cache.rootDirectory().listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        cache.rootDirectory().listFiles()?.forEach { it.delete() }
    }

    private val key = PcmCacheKey(
        chapterId = "ch1",
        voiceId = "cori",
        speedHundredths = 100,
        pitchHundredths = 100,
        chunkerVersion = CHUNKER_VERSION,
        pronunciationDictHash = 0,
    )

    private val sentences = listOf(
        Sentence(0, 0, 10, "First."),
        Sentence(1, 11, 20, "Second."),
        Sentence(2, 21, 30, "Third."),
    )

    /** Render three sentences with distinct deterministic PCM payloads
     *  so the read-back can verify the right bytes came out. */
    private fun renderCache() {
        val app = cache.appender(key, sampleRate = 22050)
        app.appendSentence(sentences[0], ByteArray(100) { 0xA1.toByte() }, trailingSilenceMs = 350)
        app.appendSentence(sentences[1], ByteArray(80)  { 0xB2.toByte() }, trailingSilenceMs = 200)
        app.appendSentence(sentences[2], ByteArray(120) { 0xC3.toByte() }, trailingSilenceMs = 350)
        app.finalize()
    }

    @Test
    fun `sequential nextChunk yields sentences in order with correct bytes`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )

        val c0 = source.nextChunk()
        assertEquals(0, c0?.sentenceIndex)
        assertEquals(100, c0?.pcm?.size)
        assertArrayEquals(ByteArray(100) { 0xA1.toByte() }, c0?.pcm)

        val c1 = source.nextChunk()
        assertEquals(1, c1?.sentenceIndex)
        assertEquals(80, c1?.pcm?.size)
        assertArrayEquals(ByteArray(80) { 0xB2.toByte() }, c1?.pcm)

        val c2 = source.nextChunk()
        assertEquals(2, c2?.sentenceIndex)
        assertEquals(120, c2?.pcm?.size)
        assertArrayEquals(ByteArray(120) { 0xC3.toByte() }, c2?.pcm)

        // Source exhausted.
        assertNull(source.nextChunk())

        source.close()
    }

    @Test
    fun `trailingSilenceBytes propagates from index`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )

        // sentence 0: trailingSilenceMs=350 → 22050 Hz mono 16-bit
        // = (22050 * 350 / 1000).toInt() * 2 = 7717 * 2 = 15434 bytes.
        val c0 = source.nextChunk()!!
        assertEquals(15434, c0.trailingSilenceBytes)

        // sentence 1: trailingSilenceMs=200 → (22050*200/1000).toInt()*2
        // = 4410 * 2 = 8820 bytes.
        val c1 = source.nextChunk()!!
        assertEquals(8820, c1.trailingSilenceBytes)

        source.close()
    }

    @Test
    fun `seekToCharOffset jumps to the correct sentence (mid-range)`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )

        // Seek into char 15 — sentence 0 spans [0,10], sentence 1
        // spans [11,20]. indexOfLast { start <= 15 } → sentence 1
        // (start=11). Matches EngineStreamingSource.seekToCharOffset.
        source.seekToCharOffset(15)
        val c = source.nextChunk()
        assertEquals(1, c?.sentenceIndex)
        assertEquals(80, c?.pcm?.size)
        source.close()
    }

    @Test
    fun `seek before first sentence yields sentence 0`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )
        source.seekToCharOffset(-100)
        val c = source.nextChunk()
        assertEquals(0, c?.sentenceIndex)
        source.close()
    }

    @Test
    fun `seek past last sentence yields last sentence then exhausts`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )
        source.seekToCharOffset(10_000)
        // Last sentence whose start <= 10000 is sentence 2 (start=21).
        val c = source.nextChunk()
        assertEquals(2, c?.sentenceIndex)
        // Then exhausted.
        assertNull(source.nextChunk())
        source.close()
    }

    @Test
    fun `truncated pcm file fails to open with IOException`() = runBlocking {
        renderCache()
        val pcmFile = cache.pcmFileFor(key)
        // Truncate the .pcm to half its size while leaving the index
        // intact. open() must detect the mismatch via the length check.
        FileOutputStream(pcmFile, true).channel.use { ch ->
            ch.truncate(pcmFile.length() / 2)
        }
        var threw = false
        try {
            CacheFileSource.open(pcmFile = pcmFile, indexFile = cache.indexFileFor(key))
        } catch (e: java.io.IOException) {
            threw = true
            assertTrue(
                "truncation message should mention truncated, got: ${e.message}",
                e.message?.contains("truncated") == true,
            )
        }
        assertTrue("CacheFileSource.open should throw on truncated pcm", threw)
    }

    @Test
    fun `bufferHeadroomMs reports MAX_VALUE for cache source`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )
        assertEquals(Long.MAX_VALUE, source.bufferHeadroomMs.value)
        // Pull a chunk; headroom unchanged (cache is never underrunning).
        source.nextChunk()
        assertEquals(Long.MAX_VALUE, source.bufferHeadroomMs.value)
        source.close()
    }

    @Test
    fun `producer queue depth and capacity report zero for cache source`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )
        assertEquals(0, source.producerQueueDepth())
        assertEquals(0, source.producerQueueCapacity())
        source.close()
    }

    @Test
    fun `close releases file descriptor allowing delete`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )
        source.close()
        // After close, the .pcm file should be deletable on Linux
        // (the only platform we run JVM tests on). Windows holds
        // file locks differently but we don't ship there.
        val deleted = cache.pcmFileFor(key).delete()
        assertTrue("file delete after close should succeed", deleted)
    }

    @Test
    fun `start sentence index defaults to zero`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
            // no startSentenceIndex param
        )
        val c = source.nextChunk()
        assertEquals(0, c?.sentenceIndex)
        source.close()
    }

    @Test
    fun `start sentence index resumes mid-chapter`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
            startSentenceIndex = 1,
        )
        val c = source.nextChunk()
        assertEquals(1, c?.sentenceIndex)
        assertEquals(80, c?.pcm?.size)
        source.close()
    }

    @Test
    fun `start sentence index past end exhausts immediately`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
            startSentenceIndex = 99,
        )
        // The cursor is coerced into [0, sentences.size]; passing
        // size means "exhausted on first call" without throwing.
        assertNull(source.nextChunk())
        source.close()
    }

    @Test
    fun `finalizeCache on cache source is a no-op`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )
        // finalizeCache is the natural-end branch in EnginePlayer's
        // consumer; on cache source it must be a no-op (the file is
        // already finalized — that's the precondition for opening it).
        // Verify by calling and then checking the index is unchanged.
        val idxBytesBefore = cache.indexFileFor(key).readBytes()
        source.finalizeCache()
        val idxBytesAfter = cache.indexFileFor(key).readBytes()
        assertArrayEquals(idxBytesBefore, idxBytesAfter)
        source.close()
    }

    @Test
    fun `pcm sample rate is read from index`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )
        assertEquals(22050, source.sampleRate)
        source.close()
    }

    @Test
    fun `sentence ranges round-trip through the chunk`() = runBlocking {
        renderCache()
        val source = CacheFileSource.open(
            pcmFile = cache.pcmFileFor(key),
            indexFile = cache.indexFileFor(key),
        )
        val c0 = source.nextChunk()!!
        // Sentence 0 was Sentence(0, 0, 10, "First.") — the index
        // entry's start/end propagate into the chunk's range.
        assertEquals(0, c0.range.sentenceIndex)
        assertEquals(0, c0.range.startCharInChapter)
        assertEquals(10, c0.range.endCharInChapter)
        source.close()
    }
}
