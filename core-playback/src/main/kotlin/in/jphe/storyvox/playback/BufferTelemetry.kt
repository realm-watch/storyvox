package `in`.jphe.storyvox.playback

/**
 * Issue #290 — point-in-time snapshot of the playback pipeline's
 * producer-consumer state. Surfaced in the Debug overlay's Audio
 * section so JP can spot "producer can't keep up" (queue near 0)
 * vs "consumer is paused" (queue near capacity, buffered ms high)
 * vs "everything's idle" (both zero).
 *
 * Polled at the 1Hz debug snapshot cadence; not allocated on the
 * hot path. The producer-side fields come from the active
 * `PcmSource` (cache sources report 0 / 0 since they have no
 * queue); audioBufferMs comes from the consumer-side AudioTrack
 * write tally minus its current playback-head position.
 */
data class BufferTelemetry(
    /** Chunks currently waiting in the producer's bounded queue. */
    val producerQueueDepth: Int = 0,
    /** Configured queue capacity (the bound). UI renders `depth/capacity`. */
    val producerQueueCapacity: Int = 0,
    /** Estimated ms of audio that the AudioTrack has accepted but
     *  not yet played out the speaker. 0 when no track is bound. */
    val audioBufferMs: Long = 0L,
)
