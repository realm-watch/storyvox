package `in`.jphe.storyvox.playback

/**
 * High-level state of the playback engine — the single source of truth the
 * UI observes to render Spotify/Apple-Music-style controls.
 *
 * Why a new type alongside the existing [PlaybackState]:
 *  - [PlaybackState] is the engine's view of "the world right now" (charOffset,
 *    isPlaying, isBuffering, error, sentence range, etc.).
 *  - [EngineState] collapses those orthogonal bits into one Spotify-grade
 *    UI status (Idle / Warming / Playing / Buffering / Paused / Completed /
 *    Error). The reader UI no longer has to reason about
 *    `isPlaying && currentSentenceRange == null && !isBuffering` to decide
 *    whether to show the "Warming Brian…" toast.
 *
 * Derived from [PlaybackState] inside [DefaultPlaybackController]; emitted as
 * a `StateFlow<EngineState>` so subscribers always see the latest value
 * without missing transitions.
 *
 * Sibling-agent contract (audio-fidelity-fixer / player-ux-polish) lives in
 * `scratch/audio-fidelity-fixer/CONTRACT.md`.
 */
sealed interface EngineState {
    /** No chapter loaded or playback stopped. */
    data object Idle : EngineState

    /**
     * Engine is loading a voice model and/or hasn't produced the first PCM
     * frame yet. `message` is render-ready ("Warming Brian…"); `progress` is
     * 0..1 if the engine can self-report (sherpa-onnx cannot — it stays null
     * for the in-process path).
     */
    data class Warming(val message: String, val progress: Float? = null) : EngineState

    /** AudioTrack is draining PCM. The listener is hearing sound. */
    data object Playing : EngineState

    /**
     * Pipeline is alive but the producer fell behind the consumer; the
     * AudioTrack is parked waiting for the queue to refill past the
     * resume threshold.
     */
    data object Buffering : EngineState

    /** User paused. AudioTrack is held; resume picks up mid-PCM. */
    data object Paused : EngineState

    /** Chapter ended naturally and no successor available (book finished). */
    data object Completed : EngineState

    /**
     * Surfaceable error. `retryable=true` means the UI may render a retry
     * button — calling [PlaybackController.play] with the last-known fiction
     * + chapter + offset is the canonical retry action.
     */
    data class Error(val message: String, val retryable: Boolean = true) : EngineState
}
