package `in`.jphe.storyvox.data.repository.playback

import kotlinx.coroutines.flow.Flow

/**
 * Configurable headroom for the streaming PCM source. Maps to
 * `EngineStreamingSource.queueCapacity`. Surfaced as its own contract (rather
 * than reading the bigger `UiSettings`) so `core-playback` can stay free of
 * feature-layer types.
 *
 * Background: the queue depth is the upper bound on outstanding pre-synthesized
 * audio sentences. Default 8 is fine on flagship hardware, but on slow voices
 * (Piper-high) on slow CPUs (Helio P22T) the producer falls behind real-time
 * playback and the user hears mid-sentence underruns. Letting users dial this
 * up in Settings buys them headroom to ride out the slow-synth dips. See
 * issue #84 for the experimental probe of where Android's Low Memory Killer
 * starts marking the app.
 *
 * The flow can re-emit (user moves the slider) but a value change only takes
 * effect on the next pipeline construction (next chapter / seek / voice swap),
 * because the queue is built into [java.util.concurrent.LinkedBlockingQueue]
 * with a fixed capacity. Mid-pipeline changes don't matter for the LMK probe
 * — what we care about is "can the app survive a full queue at this depth?"
 * and that's exercised the next time the pipeline runs.
 */
interface PlaybackBufferConfig {

    /** Live flow of queue depth (sentence-chunks). */
    val playbackBufferChunks: Flow<Int>

    /**
     * Snapshot read for callers (EnginePlayer.startPlaybackPipeline) that
     * need a single value at pipeline-construction time without subscribing.
     * Implementations should return the most recent value, falling back to a
     * sensible default if the underlying store hasn't emitted yet.
     */
    suspend fun currentBufferChunks(): Int
}
