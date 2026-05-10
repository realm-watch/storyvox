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
    /** Hot flow of the parallel-synth toggle. EnginePlayer collects
     *  it once at startup and snapshots into a volatile field that's
     *  read at pipeline-construction time. */
    val parallelSynth: Flow<Boolean>

    /** Snapshot read at pipeline-construction time inside
     *  EnginePlayer.startPlaybackPipeline; first() is fine since
     *  pipeline construction is already a suspend boundary. */
    suspend fun currentParallelSynth(): Boolean
}
