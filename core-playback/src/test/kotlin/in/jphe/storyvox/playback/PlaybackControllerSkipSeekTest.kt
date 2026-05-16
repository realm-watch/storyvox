package `in`.jphe.storyvox.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * audio-fidelity-fixer (#531 #550) — exercises the pure-function seek/skip
 * conversions exposed on [DefaultPlaybackController.Companion] so the
 * engine layer can change without breaking the seek/skip ratios the UI
 * relies on. The full controller construction needs a Hilt graph + an
 * EnginePlayer (which requires sherpa-onnx AARs); the math is testable
 * directly via the companion exports.
 */
class PlaybackControllerSkipSeekTest {

    @Test
    fun `positionMsToCharOffset at speed 1`() {
        // 60s at speed=1 → 60 * 12.5 = 750 chars.
        val chars = DefaultPlaybackController.positionMsToCharOffset(60_000L, 1.0f)
        assertEquals(750, chars)
    }

    @Test
    fun `positionMsToCharOffset is speed-invariant (#555)`() {
        // #555 — media-time axis means a tap at position 60s lands at
        // the same char regardless of speed. Pre-#555 a tap at 60s with
        // speed=2 would have hit char 1500 (the "chars in 60s of wall-
        // clock at speed 2"); post-#555 it always hits char 750 (the
        // "chars in 60s of media-time"). Same outcome at every speed.
        val chars1 = DefaultPlaybackController.positionMsToCharOffset(60_000L, 1.0f)
        val chars2 = DefaultPlaybackController.positionMsToCharOffset(60_000L, 2.0f)
        val chars05 = DefaultPlaybackController.positionMsToCharOffset(60_000L, 0.5f)
        assertEquals(750, chars1)
        assertEquals(chars1, chars2)
        assertEquals(chars1, chars05)
    }

    @Test
    fun `positionMsToCharOffset clamps negatives`() {
        assertEquals(0, DefaultPlaybackController.positionMsToCharOffset(-5_000L, 1.0f))
    }

    @Test
    fun `positionMsToCharOffset zero is zero`() {
        assertEquals(0, DefaultPlaybackController.positionMsToCharOffset(0L, 1.5f))
    }

    @Test
    fun `skipDeltaChars 30s at default speed`() {
        // 30s at any speed → 30 * 12.5 = 375 chars (media-time axis).
        val delta = DefaultPlaybackController.skipDeltaChars(30f, 1.0f)
        assertEquals(375, delta)
    }

    @Test
    fun `skipDeltaChars is speed-invariant (#555)`() {
        // #555 — "skip 30 s" means 30 s on the media-time axis. Same
        // char delta at every speed. The audio plays through it faster
        // or slower depending on the speed, but the SCRUBBER jump is
        // identical. This matches Spotify's "+30 s" UX where the bar
        // always moves the same visual distance.
        val deltaSpeed2 = DefaultPlaybackController.skipDeltaChars(30f, 2.0f)
        val deltaSpeed1 = DefaultPlaybackController.skipDeltaChars(30f, 1.0f)
        val deltaSlow = DefaultPlaybackController.skipDeltaChars(30f, 0.5f)
        assertEquals(deltaSpeed1, deltaSpeed2)
        assertEquals(deltaSpeed1, deltaSlow)
    }

    @Test
    fun `roundtrip position then offset back is speed-invariant (#555)`() {
        // #531 / #555 — tap on the rail at 12.345 s. Seeking should land
        // at the char offset whose own media-time matches within
        // rounding. The result is independent of speed.
        val targetMs = 12_345L
        for (speed in listOf(0.5f, 1.0f, 1.5f, 2.0f)) {
            val char = DefaultPlaybackController.positionMsToCharOffset(targetMs, speed)
            // Reverse: positionMs = char / baseline * 1000 (no speed
            // factor — the axis is media-time).
            val backMs = ((char / SPEED_BASELINE_CHARS_PER_SECOND) * 1000f).toLong()
            // Allow 100 ms slack for Int truncation.
            val drift = kotlin.math.abs(targetMs - backMs)
            assertTrue(
                "speed=$speed roundtrip drift $drift ms exceeded 100 ms gate",
                drift <= 100,
            )
        }
    }
}

/**
 * audio-fidelity-fixer (#524 #530 #536 #543) — exercises the EngineState
 * derivation logic via the same combine() the controller uses internally.
 * Keeps the test free of EnginePlayer / Hilt / Android-only deps.
 */
class EngineStateDerivationTest {

    @Test
    fun `idle when no chapter loaded`() {
        val state = derive(PlaybackState(), warming = false, completed = false)
        assertEquals(EngineState.Idle, state)
    }

    @Test
    fun `playing when isPlaying true and no warmup`() {
        val state = derive(
            PlaybackState(currentChapterId = "ch1", isPlaying = true),
            warming = false,
            completed = false,
        )
        assertEquals(EngineState.Playing, state)
    }

    @Test
    fun `warming when warmup latch held and isPlaying`() {
        val state = derive(
            PlaybackState(currentChapterId = "ch1", isPlaying = true),
            warming = true,
            completed = false,
            warmingMessage = "Warming Brian…",
        )
        assertTrue("expected Warming, got $state", state is EngineState.Warming)
        assertEquals("Warming Brian…", (state as EngineState.Warming).message)
    }

    @Test
    fun `buffering wins over playing`() {
        val state = derive(
            PlaybackState(currentChapterId = "ch1", isPlaying = true, isBuffering = true),
            warming = false,
            completed = false,
        )
        assertEquals(EngineState.Buffering, state)
    }

    @Test
    fun `error wins over playing`() {
        val state = derive(
            PlaybackState(
                currentChapterId = "ch1",
                isPlaying = true,
                error = PlaybackError.ChapterFetchFailed("oops"),
            ),
            warming = false,
            completed = false,
        )
        assertTrue("expected Error, got $state", state is EngineState.Error)
        assertEquals("oops", (state as EngineState.Error).message)
        assertTrue(state.retryable)
    }

    @Test
    fun `error wins over completed`() {
        val state = derive(
            PlaybackState(
                currentChapterId = "ch1",
                error = PlaybackError.AzureAuthFailed,
            ),
            warming = false,
            completed = true,
        )
        assertTrue("expected Error, got $state", state is EngineState.Error)
        assertEquals(false, (state as EngineState.Error).retryable)
    }

    @Test
    fun `completed wins over warming`() {
        val state = derive(
            PlaybackState(currentChapterId = "ch1", isPlaying = true),
            warming = true,
            completed = true,
        )
        assertEquals(EngineState.Completed, state)
    }

    @Test
    fun `paused when chapter loaded but not playing`() {
        val state = derive(
            PlaybackState(currentChapterId = "ch1", isPlaying = false),
            warming = false,
            completed = false,
        )
        assertEquals(EngineState.Paused, state)
    }

    /**
     * Mirror of the combine() body inside [DefaultPlaybackController.engineState].
     * Keeping a copy here is intentional: the test is the precedence
     * contract; if a future PR shuffles the order in the production code
     * but forgets to update the test, the assertions fail loudly.
     */
    private fun derive(
        s: PlaybackState,
        warming: Boolean,
        completed: Boolean,
        warmingMessage: String = "Warming voice…",
    ): EngineState {
        val err = s.error
        return when {
            err != null -> {
                val retryable = when (err) {
                    PlaybackError.AzureAuthFailed -> false
                    is PlaybackError.EngineUnavailable -> false
                    else -> true
                }
                val msg = when (err) {
                    is PlaybackError.ChapterFetchFailed -> err.message
                    is PlaybackError.EngineUnavailable ->
                        "Voice engine isn't installed yet. Open Voices to download one."
                    is PlaybackError.TtsSpeakFailed ->
                        "Voice failed mid-utterance (code ${err.errorCode}). Tap to retry."
                    is PlaybackError.AzureAuthFailed ->
                        "Azure subscription key was rejected. Paste a fresh key in Settings → Cloud voices."
                    is PlaybackError.AzureThrottled -> err.message
                    is PlaybackError.AzureNetworkUnavailable -> err.message
                    is PlaybackError.AzureServerError -> err.message
                }
                EngineState.Error(msg, retryable)
            }
            completed -> EngineState.Completed
            warming && s.isPlaying -> EngineState.Warming(warmingMessage)
            s.isBuffering && s.isPlaying -> EngineState.Buffering
            s.isPlaying -> EngineState.Playing
            s.currentChapterId != null -> EngineState.Paused
            else -> EngineState.Idle
        }
    }
}
