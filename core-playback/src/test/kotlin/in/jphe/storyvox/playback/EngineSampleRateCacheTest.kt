package `in`.jphe.storyvox.playback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #582 — pin the spec for the lock-free sample-rate cache that
 * keeps `Long monitor contention with owner DefaultDispatcher-worker
 * (...) in getSampleRate() for 2.054s` off the main thread.
 *
 * The cache's safety properties we want to lock in:
 *
 *  1. **Default when engine unavailable** — a fresh-process /
 *     no-engine-loaded read returns a sane architectural default
 *     (Piper: 22.05k, Kokoro/Kitten: 24k). The stress test specifically
 *     drove the Piper code path; this guards that no code path returns
 *     a zero or a "load failed" surprise.
 *
 *  2. **Lock-free on second read** — once the cache is populated (by
 *     manual assignment via [clearForTest] + a real read, which in this
 *     test bench can't actually populate because the JNI engine isn't
 *     loaded), the read is a single volatile load. We can't directly
 *     assert "no lock taken" in a unit test, but we can assert the
 *     "JVM-test classpath, no engine, no JNI" path returns deterministic
 *     defaults — which proves the runCatching swallowed any
 *     ExceptionInInitializerError and the reader fell through to the
 *     default branch without throwing.
 *
 *  3. **Idempotent populate** — calling [EngineSampleRateCache.refreshFromEngine]
 *     repeatedly is safe. In JVM tests with no engine it's a no-op (every
 *     runCatching returns 0); we assert the cache stays unchanged.
 */
class EngineSampleRateCacheTest {

    @After
    fun cleanup() {
        // Reset between tests so order-independence holds in the
        // CI matrix. The cache is process-global; without this an
        // earlier test that managed to populate it (won't happen
        // in JVM tests today, but defensive against a future
        // Robolectric variant) would leak into the next test.
        EngineSampleRateCache.clearForTest()
    }

    @Test
    fun `piperRate returns sane default without an engine loaded`() {
        EngineSampleRateCache.clearForTest()
        val rate = EngineSampleRateCache.piperRate()
        // 22050 is the Piper architectural default surfaced by the cache.
        // The exact value matters less than "non-zero & in the audio band"
        // — the AudioTrack constructor in startPlaybackPipeline would
        // throw IllegalArgumentException on 0, so this is the load-bearing
        // assertion: no path produces 0.
        assertEquals(22050, rate)
    }

    @Test
    fun `kokoroRate returns sane default without an engine loaded`() {
        EngineSampleRateCache.clearForTest()
        val rate = EngineSampleRateCache.kokoroRate()
        assertEquals(24000, rate)
    }

    @Test
    fun `kittenRate returns sane default without an engine loaded`() {
        EngineSampleRateCache.clearForTest()
        val rate = EngineSampleRateCache.kittenRate()
        // Issue #119 — Kitten ships at 24 kHz architecturally, same
        // as Kokoro.
        assertEquals(24000, rate)
    }

    @Test
    fun `refreshFromEngine is safe when engine is not loaded`() {
        EngineSampleRateCache.clearForTest()
        // Invoke twice — the JNI getInstance() will throw, runCatching
        // swallows, and the volatile fields stay at 0. The subsequent
        // public reads still produce defaults.
        EngineSampleRateCache.refreshFromEngine()
        EngineSampleRateCache.refreshFromEngine()
        assertEquals(22050, EngineSampleRateCache.piperRate())
        assertEquals(24000, EngineSampleRateCache.kokoroRate())
        assertEquals(24000, EngineSampleRateCache.kittenRate())
    }

    @Test
    fun `consecutive reads are stable`() {
        EngineSampleRateCache.clearForTest()
        // Three reads in a row on each engine — they must all return
        // the same value. Caches the @Volatile, so reads 2-3 are the
        // production-hot path (lock-free volatile load).
        val piperReads = List(3) { EngineSampleRateCache.piperRate() }
        val kokoroReads = List(3) { EngineSampleRateCache.kokoroRate() }
        val kittenReads = List(3) { EngineSampleRateCache.kittenRate() }
        assertTrue(
            "All Piper reads must agree, got $piperReads",
            piperReads.distinct().size == 1,
        )
        assertTrue(
            "All Kokoro reads must agree, got $kokoroReads",
            kokoroReads.distinct().size == 1,
        )
        assertTrue(
            "All Kitten reads must agree, got $kittenReads",
            kittenReads.distinct().size == 1,
        )
    }
}
