package `in`.jphe.storyvox.data.repository.playback

import kotlinx.coroutines.flow.Flow

/**
 * Tier 3 (#88) — toggles the two-engine parallel synth path in
 * `core-playback`'s `EngineStreamingSource`. When the toggle is ON
 * AND the active engine is Piper, EnginePlayer constructs a SECOND
 * `VoiceEngine` instance via VoxSherpa v2.7.8+'s public constructor
 * and passes it through as the source's `secondaryEngine`. The
 * producer fans out across both, doubling effective throughput.
 *
 * Mirrors the [PlaybackBufferConfig] / [PlaybackModeConfig] pattern
 * so `core-playback` can read user UX prefs without depending on the
 * feature-layer `SettingsRepositoryUi`. SettingsRepositoryUiImpl in
 * `:app` implements this against the same DataStore.
 */
interface ParallelSynthConfig {
    /** Hot flow of the (instances, threadsPerInstance) pair.
     *  - instances: 1..8, default 1 (serial = single primary engine)
     *  - threadsPerInstance: 0 (auto via getOptimalThreadCount) or 1..8
     *
     *  EnginePlayer collects this once at startup, then snapshots
     *  into volatile fields at pipeline-construction time inside
     *  loadAndPlay/startPlaybackPipeline. */
    val parallelSynthState: Flow<ParallelSynthState>

    /** Snapshot read at pipeline-construction time. */
    suspend fun currentParallelSynthState(): ParallelSynthState
}

data class ParallelSynthState(
    val instances: Int = 1,
    /** numThreads override passed to sherpa-onnx at loadModel time.
     *  0 = "Auto" (VoxSherpa's getOptimalThreadCount heuristic). */
    val threadsPerInstance: Int = 0,
)
