package `in`.jphe.storyvox.data.repository.playback

import kotlinx.coroutines.flow.Flow

/**
 * Issue #98 — Performance & buffering mode toggles for the streaming pipeline.
 *
 * Sibling of [PlaybackBufferConfig]: surfaced as its own contract so
 * `core-playback` can stay free of feature-layer types. The implementation
 * lives in `:app`'s `SettingsRepositoryUiImpl`, which already implements
 * [PlaybackBufferConfig]; one DataStore, three contracts.
 *
 * **Mode A — Warm-up Wait** ([warmupWait]). When true (default), UI shows a
 * spinner + freezes scrubber while the voice engine loads + the first
 * sentence is generated. When false, the UI behaves as if playback started
 * immediately; listener accepts silence at chapter start in exchange for
 * visible "playing" feedback. EnginePlayer doesn't behave differently for
 * Mode A — it's a UI-side gate consumed at the `PlaybackState → UiPlaybackState`
 * boundary.
 *
 * **Mode B — Catch-up Pause** ([catchupPause]). When true (default), the
 * consumer thread pauses AudioTrack on mid-stream underrun (PR #77's
 * pause-buffer-resume) and re-raises after the queue refills past the resume
 * threshold. When false, the consumer drains through the underrun without
 * pausing — listener may hear dead air mid-chapter but never sees the
 * "Buffering…" spinner. EngineStreamingSource is untouched; only the two
 * pause/resume branches in `EnginePlayer.startPlaybackPipeline()`'s consumer
 * loop are gated.
 *
 * Both flows can re-emit at any time. For Mode A the value is read at UI
 * binding time (every `toUi(nowMs)` mapping). For Mode B the value is read
 * by the consumer thread on every iteration via a volatile mirror in
 * EnginePlayer; flips take effect on the next consumer loop iteration with
 * no pipeline rebuild.
 */
interface PlaybackModeConfig {

    /** Live flow of Mode A — Warm-up Wait. Default true. */
    val warmupWait: Flow<Boolean>

    /** Live flow of Mode B — Catch-up Pause. Default true. */
    val catchupPause: Flow<Boolean>

    /**
     * Snapshot reads for callers that need a single value without subscribing.
     * Implementations should return the most recent value, falling back to the
     * documented default (true / true) if the underlying store hasn't emitted yet.
     */
    suspend fun currentWarmupWait(): Boolean
    suspend fun currentCatchupPause(): Boolean
}
