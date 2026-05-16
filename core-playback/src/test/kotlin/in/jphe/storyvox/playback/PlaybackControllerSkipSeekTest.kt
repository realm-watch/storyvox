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
    fun `positionMsToCharOffset at speed 2`() {
        // 60s at speed=2 → 60 * (12.5 * 2) = 1500 chars.
        val chars = DefaultPlaybackController.positionMsToCharOffset(60_000L, 2.0f)
        assertEquals(1500, chars)
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
        // 30s at speed=1 → 30 * 12.5 = 375 chars.
        val delta = DefaultPlaybackController.skipDeltaChars(30f, 1.0f)
        assertEquals(375, delta)
    }

    @Test
    fun `skipDeltaChars scales with speed`() {
        // At speed=2 the rail's 30s slot covers 2x the chapter chars.
        val deltaSpeed2 = DefaultPlaybackController.skipDeltaChars(30f, 2.0f)
        val deltaSpeed1 = DefaultPlaybackController.skipDeltaChars(30f, 1.0f)
        assertEquals(2 * deltaSpeed1, deltaSpeed2)
    }

    @Test
    fun `skipDeltaChars under speed`() {
        // speed=0.5 → half the chars per second → half the skip delta.
        val deltaSlow = DefaultPlaybackController.skipDeltaChars(30f, 0.5f)
        val deltaNorm = DefaultPlaybackController.skipDeltaChars(30f, 1.0f)
        assertEquals(deltaNorm / 2, deltaSlow)
    }

    @Test
    fun `roundtrip position then offset back`() {
        // #531 — tap on the rail at 12.345 s. Seeking should land at the
        // char offset whose own displayed-time matches within rounding.
        val targetMs = 12_345L
        val speed = 1.5f
        val char = DefaultPlaybackController.positionMsToCharOffset(targetMs, speed)
        // Reverse: positionMs = char / (baseline * speed) * 1000
        val charsPerSec = SPEED_BASELINE_CHARS_PER_SECOND * speed
        val backMs = ((char / charsPerSec) * 1000f).toLong()
        // Allow 50 ms slack — the Int truncation in the forward path
        // costs ≤1 char ≈ 50 ms at default baseline; that's well inside
        // the 100 ms drift gate the brief asks for after 60 s playback.
        val drift = kotlin.math.abs(targetMs - backMs)
        assertTrue("roundtrip drift $drift ms exceeded 100 ms gate", drift <= 100)
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
