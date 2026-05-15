package `in`.jphe.storyvox.data.repository.playback

import kotlinx.coroutines.flow.Flow

/**
 * Accessibility scaffold (Phase 2, v0.5.43, #486 / #488) — extra
 * inter-sentence silence applied by [`EnginePlayer`] when TalkBack is
 * active.
 *
 * Surfaced as its own contract so `core-playback` stays free of
 * feature-layer types (matches the pattern used by
 * [PlaybackBufferConfig], [AzureFallbackConfig], etc.). The `:app`
 * binding wires the underlying Flow from the
 * `AccessibilityStateBridge` (TalkBack active?) and the user's
 * `pref_a11y_screen_reader_pause_ms` slider; if either signal is off,
 * [extraSilenceMs] emits 0 and the producer's existing
 * `punctuationPauseMultiplier` path keeps the audiobook-tuned default.
 *
 * Design: rather than multiplying the existing
 * `currentPunctuationPauseMultiplier` (which lives on the engine and
 * is per-voice tuning), we ADD a constant millisecond pad — the slider
 * stores "extra pause length the user wants to hear" in ms, not as a
 * dimensionless multiplier. At playback speeds > 1× the pad scales
 * with speed inside the producer (same as the punctuation pause) so a
 * 2× listener doesn't sit through proportionally longer gaps.
 *
 * The pad is silent (0 ms) whenever:
 *  - TalkBack isn't active (the bridge reports `isTalkBackActive = false`)
 *  - the user's slider is at 0 ms
 *
 * Outside TalkBack the slider is inert — pasting a paragraph into the
 * pref doesn't slow chapter playback for sighted users.
 */
interface A11yPacingConfig {

    /**
     * Live flow of extra silence (ms) to splice after each sentence's
     * PCM. Already gated by `isTalkBackActive` and clamped to the
     * [0..1500] range the slider surfaces.
     *
     * Re-emits when the user moves the slider OR TalkBack flips state.
     * EnginePlayer's producer reads this at pipeline-construction time
     * and on each sentence boundary; mid-sentence flips don't disrupt
     * the active sentence.
     */
    val extraSilenceMs: Flow<Int>

    /**
     * Snapshot read for sites that construct a producer without
     * collecting a flow first. Returns the most recent emission, or 0
     * if the underlying store hasn't hydrated yet.
     */
    suspend fun currentExtraSilenceMs(): Int
}
