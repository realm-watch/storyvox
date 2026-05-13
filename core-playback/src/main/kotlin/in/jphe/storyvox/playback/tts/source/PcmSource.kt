package `in`.jphe.storyvox.playback.tts.source

import `in`.jphe.storyvox.playback.SentenceRange

/**
 * Source of PCM chunks for the EnginePlayer consumer to write to AudioTrack.
 *
 * Two impls (PR-A only ships [EngineStreamingSource]; `CacheFileSource`
 * follows in PR-E):
 *  - [EngineStreamingSource] runs the VoxSherpa engine on a worker
 *    coroutine, putting generated PCM into a queue. [nextChunk] blocks
 *    on `queue.take`. Subject to producer-can't-keep-up underrun on
 *    slow voice + slow device combos (Piper-high on Tab A7 Lite).
 *  - `CacheFileSource` (PR-E) mmap-reads a pre-rendered chapter PCM file.
 *    Never blocks for long.
 *
 * The consumer treats both uniformly. When the source is exhausted
 * (chapter end), [nextChunk] returns null.
 */
sealed interface PcmSource {

    /** Sample rate of every chunk this source yields. Stable for the
     *  source's lifetime; the consumer sizes its AudioTrack from this. */
    val sampleRate: Int

    /**
     * Pull the next chunk. Suspends if the source has no chunk ready
     * (streaming impl: producer hasn't generated the next sentence yet;
     * cache impl: never blocks meaningfully). Returns null when the
     * chapter is fully drained.
     *
     * Cancellation: if the calling coroutine is cancelled, blocked
     * impls must throw [kotlinx.coroutines.CancellationException]
     * promptly so the consumer can shut down. The streaming impl
     * achieves this via [kotlinx.coroutines.runInterruptible] around
     * the underlying `queue.take`.
     */
    suspend fun nextChunk(): PcmChunk?

    /**
     * Re-anchor the source to the sentence containing [charOffset].
     * Streaming impl cancels the producer and restarts at the new
     * sentence index. Cache impl seeks the underlying file via the
     * sidecar index.
     */
    suspend fun seekToCharOffset(charOffset: Int)

    /** Release any resources (cancel producer, close mmap, etc).
     *  Wakes any consumer blocked in [nextChunk] so it can exit. */
    suspend fun close()

    /**
     * Argus Fix B (#79) — called by the consumer AFTER it has finished
     * writing this chunk's PCM + trailing silence to AudioTrack. The
     * streaming impl uses this to decrement its `bufferHeadroomMs`
     * counter so the underrun trigger fires when audio actually runs
     * out, not one chunk earlier (the dequeue-time decrement was
     * pessimistic by ~one chunk duration).
     *
     * The cache-file impl has no queue or headroom; it overrides this
     * to a no-op. Default empty body keeps the seam non-breaking for
     * future PcmSource implementations.
     */
    fun decrementHeadroomForChunk(chunk: PcmChunk) = Unit

    /**
     * v0.4.85 — true when this source produces audio incrementally via
     * the engine's streaming path (today: Azure with parallelSynthInstances
     * == 1). Streaming sources emit many small PcmChunks per sentence
     * as TLS records arrive, which makes the consumer's catchup-pause
     * thresholds (7s underrun / 10s resume — sized for full-sentence
     * Piper / Kokoro chunks) fire spuriously: each streamed chunk is
     * ~165 ms, so headroom never crosses the resume threshold without
     * dozens of in-flight sentences.
     *
     * The consumer reads this and bypasses the catch-up-pause logic
     * when set. Streaming sources don't suffer the sub-realtime
     * producer problem catchup-pause was designed for — Azure's
     * server-side render is roughly real-time, and OkHttp's connection
     * pool keeps subsequent sentences low-latency. Default false
     * preserves the buffered behavior for Piper / Kokoro / cache.
     */
    val isStreaming: Boolean get() = false

    /**
     * Issue #290 — current count of chunks waiting in the producer-side
     * queue. Streaming sources back-fill this from their bounded queue;
     * cache sources have no queue and report 0. Read by the Debug
     * overlay's Audio section. Default 0 keeps the seam non-breaking
     * for sources that have no queue concept.
     */
    fun producerQueueDepth(): Int = 0

    /** Companion: configured queue capacity (the bound). Streaming
     *  sources report their `queueCapacity`; cache sources report 0
     *  (no queue). The overlay renders `depth/capacity`. */
    fun producerQueueCapacity(): Int = 0
}

/**
 * One chunk of PCM tagged with its sentence range. The trailing
 * silence is intentional cadence; the consumer spools this many bytes
 * of zero-PCM after the audible PCM to give sentences breathing
 * room. See `EngineStreamingSource.trailingPauseMs` for the
 * punctuation-driven sizing.
 */
data class PcmChunk(
    val sentenceIndex: Int,
    val range: SentenceRange,
    val pcm: ByteArray,
    val trailingSilenceBytes: Int,
) {
    /** Equality is identity-based to keep equals cheap; the consumer
     *  never compares chunks, and the default equals on a data class
     *  with a ByteArray field is structurally wrong (compares array
     *  references) AND an O(N) cliff if we ever fix it to compare
     *  contents. Identity is correct and trivial. */
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = sentenceIndex
}
