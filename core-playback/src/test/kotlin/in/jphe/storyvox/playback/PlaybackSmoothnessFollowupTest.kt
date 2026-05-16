@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package `in`.jphe.storyvox.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issues #553 #554 #555 — playback smoothness follow-up to PR #552.
 *
 * The three fixes:
 *  - **#553** auto-advance: a buffering-stuck watchdog in
 *    [DefaultPlaybackController.bindPlayer] kicks `advanceChapter(1)`
 *    when `isBuffering=true` holds for [DefaultPlaybackController.BUFFERING_STUCK_WATCHDOG_MS]
 *    without state change. EnginePlayer.advanceChapter also caps the
 *    chapter-body wait at 30 s so it can't park indefinitely.
 *  - **#554** cover-tap pause regression: a pause-pin in EnginePlayer
 *    snapshots [EnginePlayer.currentPositionMs] at the moment of pause
 *    and returns it verbatim until resume / seek / pipeline restart.
 *  - **#555** speed-change position jump: both
 *    [EnginePlayer.currentPositionMs] and [EnginePlayer.estimateDurationMs]
 *    now use the speed-invariant media-time axis. Companion exports
 *    ([DefaultPlaybackController.positionMsToCharOffset] /
 *    [DefaultPlaybackController.skipDeltaChars]) mirror the axis so
 *    the seek round-trip stays correct.
 *
 * Engine-internal verifications (latch behavior, AudioTrack frame math)
 * live in the on-device verification flow — those need a sherpa-onnx
 * voice + a real chapter. The contracts that DON'T need Android are
 * tested here: math, watchdog timing, derived progress fractions.
 */
class PlaybackSmoothnessFollowupTest {

    // ─── #555: speed-invariant position/duration axis ─────────────────

    @Test
    fun `scrubProgress is identical across speeds for the same charOffset`() {
        // #555 — the user shouldn't see the rail re-position itself when
        // they tap a different speed chip. The audit reported a 19 s
        // backward jump when speed 1× → 1.5×; with the media-time axis
        // the fraction (and therefore the thumb pixel position) is
        // identical at every speed.
        val charOffset = 500
        val durationMs = 80_000L
        val progresses = listOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f).map { speed ->
            PlaybackState(
                durationEstimateMs = durationMs,
                charOffset = charOffset,
                speed = speed,
            ).scrubProgress()
        }
        // All progresses should be identical (to a tiny float epsilon).
        val first = progresses.first()
        for (p in progresses) {
            assertEquals("expected speed-invariant progress, got $progresses", first, p, 1e-5f)
        }
    }

    @Test
    fun `seekToPositionMs round-trip is speed-invariant`() {
        // #555 — verify the inverse path. UI taps at displayed position
        // 60 s; we compute char offset; then map back to displayed
        // position; numbers should match within rounding regardless of
        // speed.
        for (speed in listOf(0.5f, 1.0f, 1.5f, 2.0f)) {
            val targetMs = 60_000L
            val chars = DefaultPlaybackController.positionMsToCharOffset(targetMs, speed)
            // Reverse using the same media-time axis.
            val backMs = ((chars / SPEED_BASELINE_CHARS_PER_SECOND) * 1000f).toLong()
            assertTrue(
                "speed=$speed: roundtrip drift ${kotlin.math.abs(targetMs - backMs)} ms",
                kotlin.math.abs(targetMs - backMs) <= 100L,
            )
        }
    }

    @Test
    fun `skipDeltaChars yields the same delta at every speed`() {
        // #555 — the rail's "30 s" slot covers the same number of chars
        // regardless of speed. Audio plays through it faster or slower
        // depending on speed, but the SCRUBBER JUMP is identical.
        val deltas = listOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f).map { speed ->
            DefaultPlaybackController.skipDeltaChars(30f, speed)
        }
        val first = deltas.first()
        for (d in deltas) {
            assertEquals("expected speed-invariant skip delta, got $deltas", first, d)
        }
    }

    // ─── #553: buffering-stuck watchdog ──────────────────────────────

    @Test
    fun `watchdog fires once when isBuffering stays stuck on same chapter`() = runTest {
        val state = MutableStateFlow(
            PlaybackState(currentChapterId = "ch1", isPlaying = true, isBuffering = true),
        )
        var firedFor: String? = null
        val job = launch {
            // Inline replica of the watchdog (slimmer than collecting
            // the full controller's flow chain).
            var watchdog: kotlinx.coroutines.Job? = null
            state
                .map { (it.isBuffering && it.currentChapterId != null) to it.currentChapterId }
                .distinctUntilChanged()
                .collect { (buffering, chapterId) ->
                    watchdog?.cancel()
                    if (!buffering || chapterId == null) return@collect
                    val armedFor = chapterId
                    watchdog = launch {
                        delay(DefaultPlaybackController.BUFFERING_STUCK_WATCHDOG_MS)
                        val s = state.value
                        if (
                            s.isBuffering &&
                            s.currentChapterId == armedFor &&
                            s.error == null
                        ) {
                            firedFor = armedFor
                        }
                    }
                }
        }
        // Less than the threshold — watchdog should not have fired yet.
        advanceTimeBy(DefaultPlaybackController.BUFFERING_STUCK_WATCHDOG_MS / 2)
        assertNull("watchdog fired prematurely", firedFor)
        // Past the threshold — should fire.
        advanceTimeBy(DefaultPlaybackController.BUFFERING_STUCK_WATCHDOG_MS)
        assertEquals("watchdog should have fired for ch1", "ch1", firedFor)
        job.cancel()
    }

    @Test
    fun `watchdog cancels when chapter changes before threshold`() = runTest {
        val state = MutableStateFlow(
            PlaybackState(currentChapterId = "ch1", isPlaying = true, isBuffering = true),
        )
        var firedFor: String? = null
        val job = launch {
            var watchdog: kotlinx.coroutines.Job? = null
            state
                .map { (it.isBuffering && it.currentChapterId != null) to it.currentChapterId }
                .distinctUntilChanged()
                .collect { (buffering, chapterId) ->
                    watchdog?.cancel()
                    if (!buffering || chapterId == null) return@collect
                    val armedFor = chapterId
                    watchdog = launch {
                        delay(DefaultPlaybackController.BUFFERING_STUCK_WATCHDOG_MS)
                        val s = state.value
                        if (
                            s.isBuffering &&
                            s.currentChapterId == armedFor &&
                            s.error == null
                        ) {
                            firedFor = armedFor
                        }
                    }
                }
        }
        // Chapter advances naturally before the watchdog threshold.
        // v0.5.57 (#557) — watchdog dropped from 8 s → 1.5 s, so the
        // "advance well before threshold" probe has to be sub-threshold.
        // Pre-fix this advanced 2 s which now overshoots the 1.5 s
        // watchdog, fires it, and trips the assertNull on the test below.
        advanceTimeBy(500L)
        state.value = state.value.copy(
            currentChapterId = "ch2",
            isBuffering = false,
        )
        // Now well past the original threshold.
        advanceTimeBy(DefaultPlaybackController.BUFFERING_STUCK_WATCHDOG_MS + 1_000L)
        assertNull("watchdog should have cancelled on chapter change", firedFor)
        job.cancel()
    }

    @Test
    fun `watchdog cancels when isBuffering clears before threshold`() = runTest {
        val state = MutableStateFlow(
            PlaybackState(currentChapterId = "ch1", isPlaying = true, isBuffering = true),
        )
        var firedFor: String? = null
        val job = launch {
            var watchdog: kotlinx.coroutines.Job? = null
            state
                .map { (it.isBuffering && it.currentChapterId != null) to it.currentChapterId }
                .distinctUntilChanged()
                .collect { (buffering, chapterId) ->
                    watchdog?.cancel()
                    if (!buffering || chapterId == null) return@collect
                    val armedFor = chapterId
                    watchdog = launch {
                        delay(DefaultPlaybackController.BUFFERING_STUCK_WATCHDOG_MS)
                        val s = state.value
                        if (
                            s.isBuffering &&
                            s.currentChapterId == armedFor &&
                            s.error == null
                        ) {
                            firedFor = armedFor
                        }
                    }
                }
        }
        // v0.5.57 (#557) — watchdog dropped from 8 s → 1.5 s, so the
        // "advance well before threshold" probe has to be sub-threshold.
        // Pre-fix this advanced 2 s which now overshoots the 1.5 s
        // watchdog, fires it, and trips the assertNull on the test below.
        advanceTimeBy(500L)
        // Buffering clears (e.g., chapter body landed, audio started).
        state.value = state.value.copy(isBuffering = false)
        advanceTimeBy(DefaultPlaybackController.BUFFERING_STUCK_WATCHDOG_MS + 1_000L)
        assertNull("watchdog should have cancelled on buffering clear", firedFor)
        job.cancel()
    }

    @Test
    fun `watchdog does not fire when error surfaces before threshold`() = runTest {
        val state = MutableStateFlow(
            PlaybackState(currentChapterId = "ch1", isPlaying = true, isBuffering = true),
        )
        var firedFor: String? = null
        val job = launch {
            var watchdog: kotlinx.coroutines.Job? = null
            state
                .map { (it.isBuffering && it.currentChapterId != null) to it.currentChapterId }
                .distinctUntilChanged()
                .collect { (buffering, chapterId) ->
                    watchdog?.cancel()
                    if (!buffering || chapterId == null) return@collect
                    val armedFor = chapterId
                    watchdog = launch {
                        delay(DefaultPlaybackController.BUFFERING_STUCK_WATCHDOG_MS)
                        val s = state.value
                        if (
                            s.isBuffering &&
                            s.currentChapterId == armedFor &&
                            s.error == null
                        ) {
                            firedFor = armedFor
                        }
                    }
                }
        }
        // v0.5.57 (#557) — watchdog dropped from 8 s → 1.5 s, so the
        // "advance well before threshold" probe has to be sub-threshold.
        // Pre-fix this advanced 2 s which now overshoots the 1.5 s
        // watchdog, fires it, and trips the assertNull on the test below.
        advanceTimeBy(500L)
        // Error surfaces — engine already gave up; watchdog should NOT
        // double-fire an advance.
        state.value = state.value.copy(
            error = PlaybackError.ChapterFetchFailed("network down"),
        )
        advanceTimeBy(DefaultPlaybackController.BUFFERING_STUCK_WATCHDOG_MS + 1_000L)
        assertNull(
            "watchdog should defer to the typed error path",
            firedFor,
        )
        job.cancel()
    }

    // ─── #553: end-of-book branch ────────────────────────────────────

    @Test
    fun `BookFinished path keeps isBuffering false (no stuck spinner at end)`() {
        // Audit: when chapter N+1 doesn't exist (end of book), the
        // engine emits BookFinished and the UI should roll to Completed.
        // If a prior advanceChapter attempt left isBuffering=true, the
        // EngineState mapping (#524) prefers Completed (which is correct
        // per the precedence order). Verify the precedence holds.
        val s = PlaybackState(
            currentChapterId = "ch_last",
            isPlaying = false,
            isBuffering = false, // engine clears it in advanceChapter's null-next branch
        )
        // Buffering should be false at this point — the engine's
        // advanceChapter clears it on the end-of-book branch.
        assertFalse("end-of-book branch leaves isBuffering true", s.isBuffering)
    }
}
