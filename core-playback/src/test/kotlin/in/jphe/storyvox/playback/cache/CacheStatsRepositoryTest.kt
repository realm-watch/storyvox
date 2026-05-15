package `in`.jphe.storyvox.playback.cache

import android.app.Application
import `in`.jphe.storyvox.playback.tts.Sentence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * PCM cache PR-G (#86) — emission semantics of the polling cache-stats
 * flow that backs Settings → Performance & buffering's "Currently used"
 * indicator.
 *
 * Same Robolectric shape as [PcmCacheTest]: direct instantiation against
 * the vanilla Application context, no Hilt bootstrap. We exercise the
 * flow over a `runTest` virtual scheduler so the 5 s production poll
 * tick becomes a 50-100 ms test tick without sleeping real time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CacheStatsRepositoryTest {

    private lateinit var context: Application
    private lateinit var cache: PcmCache
    private lateinit var config: PcmCacheConfig
    private lateinit var stats: CacheStatsRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        config = PcmCacheConfig(context)
        cache = PcmCache(context, config)
        stats = CacheStatsRepository(cache, config)
        // Wipe leftover entries from prior tests sharing the same
        // cacheDir — same hygiene as PcmCacheTest.
        cache.rootDirectory().listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        cache.rootDirectory().listFiles()?.forEach { it.delete() }
    }

    private val key1 = PcmCacheKey("ch1", "cori", 100, 100, 1, 0)
    private val key2 = PcmCacheKey("ch2", "cori", 100, 100, 1, 0)

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
    fun `snapshot reflects current cache size and quota`() = runBlocking {
        // Explicitly seed the default quota — Robolectric reuses the
        // pcm_cache_config DataStore across tests in the same class,
        // so a prior test that mutated quotaBytes could leak its value
        // here. Set it deterministically.
        config.setQuotaBytes(2L * 1024 * 1024 * 1024)

        val before = stats.snapshot()
        assertEquals(0L, before.usedBytes)
        assertEquals(2L * 1024 * 1024 * 1024, before.quotaBytes)

        renderInto(key1, bytes = 1_000)
        val after = stats.snapshot()
        assertEquals(1_000L, after.usedBytes)
    }

    @Test
    fun `observe emits initial snapshot immediately`() = runBlocking {
        // first() doesn't need any real waiting — the cold flow emits
        // synchronously on subscribe.
        renderInto(key1, bytes = 500)
        val firstEmission = stats.observe(pollIntervalMs = 50).first()
        assertEquals(500L, firstEmission.usedBytes)
    }

    @Test
    fun `observe re-emits when cache content changes across a poll boundary`() = runBlocking {
        // Uses real time (not runTest virtual scheduler) because
        // PcmCache.totalSizeBytes() suspends on Dispatchers.IO; those
        // suspensions don't advance under virtual time. A short real
        // poll (60 ms) keeps the test fast while exercising the
        // distinct-after-mutation contract.
        renderInto(key1, bytes = 500)

        val emissions = mutableListOf<CacheStatsRepository.CacheStats>()
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = collectorScope.launch {
            stats.observe(pollIntervalMs = 60).collect { emission ->
                emissions += emission
            }
        }
        // Allow initial emission to land + a couple of polls to fire.
        delay(40)
        renderInto(key2, bytes = 700)
        delay(120)
        job.cancel()
        collectorScope.cancel()

        assertTrue(
            "expected at least 2 emissions, got ${emissions.size}",
            emissions.size >= 2,
        )
        val last = emissions.last()
        // 500 + 700 = 1200 by the time the second emission lands.
        assertEquals(1_200L, last.usedBytes)
    }

    @Test
    fun `observe is distinct — stable state collapses to one emission`() = runBlocking {
        // Real-time variant (see runBlocking note above). Three poll
        // ticks worth of real-time wait with no mutation → expect just
        // one emission via distinctUntilChanged.
        renderInto(key1, bytes = 500)

        val emissions = mutableListOf<CacheStatsRepository.CacheStats>()
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = collectorScope.launch {
            stats.observe(pollIntervalMs = 30).collect { emissions += it }
        }
        delay(150)  // 5 poll ticks worth
        job.cancel()
        collectorScope.cancel()

        assertEquals(1, emissions.size)
        assertEquals(500L, emissions.single().usedBytes)
    }

    @Test
    fun `quota changes surface on the next poll`() = runBlocking {
        // Real-time variant. Seed a known starting quota so the test
        // is self-contained when run after another test that mutated
        // it; then tighten and verify the next poll emits the change.
        config.setQuotaBytes(2L * 1024 * 1024 * 1024)

        val emissions = mutableListOf<CacheStatsRepository.CacheStats>()
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = collectorScope.launch {
            stats.observe(pollIntervalMs = 60).collect { emissions += it }
        }
        delay(40)
        config.setQuotaBytes(500L * 1024 * 1024)
        delay(120)
        job.cancel()
        collectorScope.cancel()

        assertTrue(
            "expected at least 2 emissions, got ${emissions.size}",
            emissions.size >= 2,
        )
        assertEquals(500L * 1024 * 1024, emissions.last().quotaBytes)
    }
}
