package `in`.jphe.storyvox.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #545 — pure-logic regression guard for the play/pause/replay
 * state derivation. The composable's button label was wrong on
 * single-chapter books that hit natural end (read "Pause" while the
 * audio was actually stopped at the end of the only chapter), because
 * the engine left `isPlaying=false` without a Completed signal that
 * reached the UI. [derivePlayPauseIconState] is the seam where the
 * three-state mapping now lives; this test pins each branch.
 */
class PlayPauseIconStateTest {

    @Test
    fun `playing always renders Pause icon`() {
        // isPlaying takes top precedence — even mid-chapter buffer where
        // positionMs happens to be near durationMs, the button must say
        // Pause so the user can stop playback.
        val s = derivePlayPauseIconState(
            isPlaying = true,
            positionMs = 100_000L,
            durationMs = 120_000L,
            chapterId = "ch-1",
        )
        assertEquals(PlayPauseIconState.Pause, s)
    }

    @Test
    fun `playing at the very end still renders Pause`() {
        // Edge case: the producer hasn't pushed BookFinished yet but
        // playbackPosition has caught up to duration. isPlaying is still
        // true (the engine is draining the AudioTrack tail). Pause must
        // win — replay would force-restart a chapter the engine is
        // about to finish on its own.
        val s = derivePlayPauseIconState(
            isPlaying = true,
            positionMs = 120_000L,
            durationMs = 120_000L,
            chapterId = "ch-1",
        )
        assertEquals(PlayPauseIconState.Pause, s)
    }

    @Test
    fun `idle with no chapter renders Play`() {
        // Cold start / picker still up / Reader screen mounting before
        // a chapter loads. positionMs and durationMs are both 0 — the
        // inequality `0 >= -5500` would fold to true without the
        // chapter-id gate, so this test pins that the chapter-id gate
        // is doing real work.
        val s = derivePlayPauseIconState(
            isPlaying = false,
            positionMs = 0L,
            durationMs = 0L,
            chapterId = null,
        )
        assertEquals(PlayPauseIconState.Play, s)
    }

    @Test
    fun `paused mid-chapter renders Play`() {
        // Common case: user paused at 30s into a 120s chapter. Tapping
        // Play should resume — Replay would seek-to-0 and lose the
        // user's listening position. This is the test that gold-plates
        // against an overzealous Replay branch.
        val s = derivePlayPauseIconState(
            isPlaying = false,
            positionMs = 30_000L,
            durationMs = 120_000L,
            chapterId = "ch-1",
        )
        assertEquals(PlayPauseIconState.Play, s)
    }

    @Test
    fun `paused exactly at duration renders Replay`() {
        // #545 root case: single-chapter book, engine stopped at end,
        // isPlaying=false, positionMs == durationMs. Replay icon is the
        // truthful affordance — the button now means "play this again
        // from the start," not "resume."
        val s = derivePlayPauseIconState(
            isPlaying = false,
            positionMs = 120_000L,
            durationMs = 120_000L,
            chapterId = "ch-1",
        )
        assertEquals(PlayPauseIconState.Replay, s)
    }

    @Test
    fun `paused 5 seconds before duration renders Replay because of tolerance`() {
        // #545 — the engine occasionally truncates ~5s of the tail PCM
        // (gapless-investigator is fixing this in parallel). The
        // END_TOLERANCE_MS slack means a playback position 5s short of
        // durationMs still surfaces Replay so the button doesn't lie
        // about "tap to resume the last 5 seconds" (which would
        // immediately auto-finish anyway).
        val s = derivePlayPauseIconState(
            isPlaying = false,
            positionMs = 115_000L,
            durationMs = 120_000L,
            chapterId = "ch-1",
        )
        assertEquals(PlayPauseIconState.Replay, s)
    }

    @Test
    fun `paused 10 seconds before duration renders Play because outside tolerance`() {
        // Tolerance must NOT be unbounded — paused at 110s of 120s
        // (10s of legitimate listening remaining) is a Play, not a
        // Replay. Pinning the bound at END_TOLERANCE_MS = 5500ms means
        // this exact case is the threshold guard.
        val s = derivePlayPauseIconState(
            isPlaying = false,
            positionMs = 110_000L,
            durationMs = 120_000L,
            chapterId = "ch-1",
        )
        assertEquals(PlayPauseIconState.Play, s)
    }

    @Test
    fun `zero duration with chapter does not render Replay`() {
        // Loaded chapter but the engine hasn't reported duration yet
        // (durationEstimateMs is 0 during warmup). Avoid surfacing
        // Replay during this transient: durationMs > 0L gate guards
        // against it.
        val s = derivePlayPauseIconState(
            isPlaying = false,
            positionMs = 0L,
            durationMs = 0L,
            chapterId = "ch-1",
        )
        assertEquals(PlayPauseIconState.Play, s)
    }
}
