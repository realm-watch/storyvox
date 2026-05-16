package `in`.jphe.storyvox.feature.debug

import `in`.jphe.storyvox.feature.api.DebugPlayback
import `in`.jphe.storyvox.feature.api.DebugSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #537 — engine state "idle" displayed when player is paused.
 *
 * Pre-#537 [pipelineStateText] mapped a paused-but-loaded chapter
 * (`pipelineRunning = true` but `isPlaying = false`) to "idle" — the
 * else-branch fall-through. The audit (R5CRB0W66MK, 2026-05-15) flagged
 * this as a semantic mismatch: a paused chapter looks indistinguishable
 * from a fresh app launch.
 *
 * These tests pin the new mapping:
 *  - paused (loaded chapter, not playing, not warming/buffering, no
 *    error) → "paused"
 *  - truly idle (no pipeline running) → "" (empty string, not "idle")
 *  - all other states (warming / buffering / playing / error)
 *    unchanged.
 *
 * Issue #529 — these are pure-logic tests; the DebugOverlay composable
 * is gated at the call site (HybridReaderScreen) by `BuildConfig.DEBUG &&
 * pref_show_debug_overlay`. The label mapping still has to be right
 * because the DebugScreen surface (Settings → Advanced → Debug) renders
 * the same state label in debug builds, and we want the developer view
 * to be accurate too.
 */
class DebugOverlayStateLabelTest {

    private fun snapshotWith(
        pipelineRunning: Boolean = false,
        isPlaying: Boolean = false,
        isBuffering: Boolean = false,
        isWarmingUp: Boolean = false,
        lastErrorTag: String? = null,
    ): DebugSnapshot = DebugSnapshot.EMPTY.copy(
        playback = DebugPlayback(
            pipelineRunning = pipelineRunning,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            isWarmingUp = isWarmingUp,
            lastErrorTag = lastErrorTag,
        ),
    )

    /**
     * #537 — the regression case. A user paused mid-chapter:
     * pipeline is still bound to the chapter, but the consumer
     * thread isn't pushing audio. Pre-fix this showed "idle"; the
     * fix returns "paused".
     */
    @Test
    fun `pipeline running but not playing maps to paused`() {
        val snap = snapshotWith(pipelineRunning = true, isPlaying = false)
        assertEquals("paused", pipelineStateText(snap))
    }

    /**
     * Cold launch / no chapter loaded — the literal word "idle" is
     * worse than nothing: it reads as "the engine is stuck". The fix
     * surfaces an empty label so the strip stays clean.
     */
    @Test
    fun `no pipeline running maps to empty string not idle`() {
        val snap = snapshotWith(pipelineRunning = false, isPlaying = false)
        assertEquals(
            "Cold-launch / no chapter loaded should surface an empty " +
                "label — the literal word 'idle' read as 'the engine is " +
                "stuck' during the 2026-05-15 audit.",
            "",
            pipelineStateText(snap),
        )
    }

    @Test
    fun `playing state still maps to playing`() {
        val snap = snapshotWith(pipelineRunning = true, isPlaying = true)
        assertEquals("playing", pipelineStateText(snap))
    }

    @Test
    fun `warming up state preempts playing`() {
        // Warming-up can technically coexist with isPlaying=true (the
        // user hit play, the pipeline is warming the voice but no PCM
        // has reached AudioTrack). The warming label is more useful
        // than "playing" during that window because the listener
        // hears silence.
        val snap = snapshotWith(
            pipelineRunning = true,
            isPlaying = true,
            isWarmingUp = true,
        )
        assertEquals("warming up", pipelineStateText(snap))
    }

    @Test
    fun `buffering state preempts playing`() {
        val snap = snapshotWith(
            pipelineRunning = true,
            isPlaying = true,
            isBuffering = true,
        )
        assertEquals("buffering", pipelineStateText(snap))
    }

    @Test
    fun `error state preempts every other state`() {
        val snap = snapshotWith(
            pipelineRunning = true,
            isPlaying = true,
            isBuffering = true,
            isWarmingUp = true,
            lastErrorTag = "ChapterFetchFailed",
        )
        assertEquals("ERROR", pipelineStateText(snap))
    }
}
