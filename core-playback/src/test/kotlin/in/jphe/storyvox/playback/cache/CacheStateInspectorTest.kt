package `in`.jphe.storyvox.playback.cache

import android.app.Application
import `in`.jphe.storyvox.playback.tts.Sentence
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * PR-H (#86) — Robolectric coverage of [CacheStateInspector]. Mirrors
 * the setup of [PcmCacheTest] (direct instantiation with the vanilla
 * Application context, wipe between tests) so the two test files can
 * share intuitions about the cache surface.
 *
 * Verifies:
 *  - chapterStateFor classifies Complete / Partial / None correctly
 *  - chapterStatesFor batch returns the same classifications, defaults
 *    missing chapters to None
 *  - bytesUsedByVoice sums pcm sizes whose meta.voiceId matches
 *  - bytesUsedByEveryVoice returns the full per-voice map
 *  - in-flight (Partial) entries also count toward bytesUsedByVoice —
 *    "disk used by this voice today" includes incomplete renders that
 *    are nonetheless occupying space on the user's device
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CacheStateInspectorTest {

    private lateinit var context: Application
    private lateinit var cache: PcmCache
    private lateinit var inspector: CacheStateInspector
    private val chunkerVersion = 1
    private val dictHash = 0

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        cache = PcmCache(context, PcmCacheConfig(context))
        inspector = CacheStateInspector(cache)
        cache.rootDirectory().listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        cache.rootDirectory().listFiles()?.forEach { it.delete() }
    }

    private fun keyFor(chapterId: String, voiceId: String) = PcmCacheKey(
        chapterId = chapterId,
        voiceId = voiceId,
        speedHundredths = 100,
        pitchHundredths = 100,
        chunkerVersion = chunkerVersion,
        pronunciationDictHash = dictHash,
    )

    /** Write meta + pcm + idx for [chapterId] under [voiceId] — leaves
     *  the cache in [ChapterCacheState.Complete] for the key. */
    private fun renderComplete(chapterId: String, voiceId: String, bytes: Int) {
        val app = cache.appender(keyFor(chapterId, voiceId), sampleRate = 22050)
        app.appendSentence(
            Sentence(0, 0, 5, "S."),
            ByteArray(bytes) { 0x42 },
            trailingSilenceMs = 350,
        )
        app.complete()
    }

    /** Write meta + pcm (no finalize) — leaves the cache in
     *  [ChapterCacheState.Partial] for the key. Simulates either an
     *  in-flight render (PR-D's tee or PR-F's worker) or an abandoned
     *  one. */
    private fun renderPartial(chapterId: String, voiceId: String, bytes: Int) {
        val app = cache.appender(keyFor(chapterId, voiceId), sampleRate = 22050)
        app.appendSentence(
            Sentence(0, 0, 5, "S."),
            ByteArray(bytes) { 0x42 },
            trailingSilenceMs = 350,
        )
        // intentionally no finalize() — meta + pcm exist, idx does not
    }

    @Test
    fun `chapterStateFor returns Complete for finalized entries`() = runBlocking {
        renderComplete("ch1", "cori", 100)
        val state = inspector.chapterStateFor("ch1", "cori", chunkerVersion, dictHash)
        assertEquals(ChapterCacheState.Complete, state)
    }

    @Test
    fun `chapterStateFor returns Partial when meta exists but idx does not`() = runBlocking {
        renderPartial("ch1", "cori", 100)
        val state = inspector.chapterStateFor("ch1", "cori", chunkerVersion, dictHash)
        assertEquals(ChapterCacheState.Partial, state)
    }

    @Test
    fun `chapterStateFor returns None for unknown keys`() = runBlocking {
        val state = inspector.chapterStateFor("ch-nonexistent", "cori", chunkerVersion, dictHash)
        assertEquals(ChapterCacheState.None, state)
    }

    @Test
    fun `chapterStateFor differentiates by voice`() = runBlocking {
        renderComplete("ch1", "cori", 100)
        // ch1 is cached under "cori" but not under "amy" → asking for
        // amy returns None even though the chapterId matches.
        val coriState = inspector.chapterStateFor("ch1", "cori", chunkerVersion, dictHash)
        val amyState = inspector.chapterStateFor("ch1", "amy", chunkerVersion, dictHash)
        assertEquals(ChapterCacheState.Complete, coriState)
        assertEquals(ChapterCacheState.None, amyState)
    }

    @Test
    fun `chapterStatesFor batches mixed states correctly`() = runBlocking {
        renderComplete("ch1", "cori", 100)
        renderPartial("ch2", "cori", 100)

        val states = inspector.chapterStatesFor(
            chapterIds = listOf("ch1", "ch2", "ch3-missing"),
            voiceId = "cori",
            chunkerVersion = chunkerVersion,
            pronunciationDictHash = dictHash,
        )
        assertEquals(ChapterCacheState.Complete, states["ch1"])
        assertEquals(ChapterCacheState.Partial, states["ch2"])
        assertEquals(ChapterCacheState.None, states["ch3-missing"])
        assertEquals(3, states.size)
    }

    @Test
    fun `chapterStatesFor returns empty map for empty input`() = runBlocking {
        val states = inspector.chapterStatesFor(
            chapterIds = emptyList(),
            voiceId = "cori",
            chunkerVersion = chunkerVersion,
            pronunciationDictHash = dictHash,
        )
        assertEquals(emptyMap<String, ChapterCacheState>(), states)
    }

    @Test
    fun `bytesUsedByVoice sums pcm files matching the voice`() = runBlocking {
        renderComplete("ch1", "cori", 1_000)
        renderComplete("ch2", "cori", 2_500)
        renderComplete("ch3", "amy", 500)

        assertEquals(3_500L, inspector.bytesUsedByVoice("cori"))
        assertEquals(500L, inspector.bytesUsedByVoice("amy"))
        assertEquals(0L, inspector.bytesUsedByVoice("nonexistent"))
    }

    @Test
    fun `bytesUsedByEveryVoice returns map of all voices`() = runBlocking {
        renderComplete("ch1", "cori", 1_000)
        renderComplete("ch2", "cori", 2_500)
        renderComplete("ch3", "amy", 500)

        val all = inspector.bytesUsedByEveryVoice()
        assertEquals(3_500L, all["cori"])
        assertEquals(500L, all["amy"])
        assertEquals(2, all.size)
    }

    @Test
    fun `bytesUsedByEveryVoice returns empty map when cache is empty`() = runBlocking {
        val all = inspector.bytesUsedByEveryVoice()
        assertEquals(emptyMap<String, Long>(), all)
    }

    @Test
    fun `partial entries also count in bytesUsedByVoice`() = runBlocking {
        // PR-H (#86) — disk consumed is disk consumed. A render still
        // in flight (or abandoned) is occupying bytes on the user's
        // device; the "X MB cached" label should reflect that even
        // though playback wouldn't yet be cache-hit-eligible.
        renderPartial("ch1", "cori", 1_000)
        assertEquals(1_000L, inspector.bytesUsedByVoice("cori"))
    }
}
