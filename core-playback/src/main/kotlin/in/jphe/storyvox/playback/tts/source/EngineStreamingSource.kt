package `in`.jphe.storyvox.playback.tts.source

import android.os.Process as AndroidProcess
import `in`.jphe.storyvox.playback.SentenceRange
import `in`.jphe.storyvox.playback.tts.Sentence
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drives VoxSherpa's [VoiceEngineHandle] on a worker coroutine, putting
 * generated PCM into a bounded queue. The consumer (EnginePlayer's
 * AudioTrack writer thread) pulls from [nextChunk]. Mirrors the producer
 * half of `EnginePlayer.startPlaybackPipeline` pre-PR-A.
 *
 * Why a [LinkedBlockingQueue] not a coroutine [kotlinx.coroutines.channels.Channel]:
 * the EnginePlayer consumer is a pinned OS thread at URGENT_AUDIO; pulling
 * via Channel would force it through the coroutine dispatcher and lose
 * the priority bump (URGENT_AUDIO is per-OS-thread, not per-coroutine).
 * We bridge via [runInterruptible] so the producer can coexist with
 * structured concurrency for shutdown.
 *
 * @param sentences full sentence list for the chapter; the producer
 *  walks from [startSentenceIndex] to the end.
 * @param startSentenceIndex first sentence to generate. Updated by [seekToCharOffset]
 *  by cancelling the producer and restarting from the new index.
 * @param engine the active VoxSherpa-style engine (Piper or Kokoro) wrapped
 *  in a SAM so unit tests can fake it without pulling the AAR.
 * @param speed playback speed; fed to [VoiceEngineHandle.generateAudioPCM]
 *  AND scales the trailing-cadence pause down at faster speeds so a 2× listener
 *  doesn't sit through 700 ms gaps.
 * @param pitch pitch multiplier, fed to engine.
 * @param punctuationPauseMultiplier scales the inter-sentence silence
 *  spliced after each sentence's PCM (issue #90). 0f = no trailing silence
 *  at all; 1f = the audiobook-tuned default in [trailingPauseMs]; >1f
 *  lengthens proportionally. Applied AFTER the speed scaling so the
 *  semantic is "pause length the user wants to hear" — at 2× playback
 *  with multiplier=1f the listener still gets a sensible 175 ms gap, and
 *  at multiplier=0f they get no gap regardless of speed.
 * @param queueCapacity bounded queue depth; producer back-pressures when full.
 *  Defaults to 8 to match the prior EnginePlayer constant.
 * @param pronunciationDictApply user pronunciation-dictionary substitution
 *  (issue #135). Applied to the *spoken* text passed into
 *  [VoiceEngineHandle.generateAudioPCM]; the underlying [Sentence.text]
 *  is left untouched so the highlight ranges (`startChar..endChar` into
 *  the original chapter body) keep working. Defaults to identity so
 *  callers that don't care about pronunciations (tests, pre-#135
 *  integrations) get the unchanged behavior.
 */
class EngineStreamingSource(
    private val sentences: List<Sentence>,
    startSentenceIndex: Int,
    private val engine: VoiceEngineHandle,
    private val speed: Float,
    private val pitch: Float,
    /** Shared with EnginePlayer.loadAndPlay so loadModel() can wait for any
     *  in-flight generateAudioPCM to finish before tearing the model down.
     *  Without this shared mutex, a Piper-to-Piper voice swap can call
     *  loadModel().destroy() while the prior source's generator is still
     *  inside the JNI generate(...) call, corrupting native state. */
    private val engineMutex: Mutex,
    private val punctuationPauseMultiplier: Float = 1f,
    private val queueCapacity: Int = 8,
    private val pronunciationDictApply: (String) -> String = { it },
    /**
     * Tier 3 (#88) — list of secondary engine handles for parallel
     * synth. When non-empty, the producer fans out across the
     * primary [engine] PLUS each secondary, so [secondaryEngines.size + 1]
     * sentences can be in flight at once. Empty list = serial path
     * (Tier 2 single-thread URGENT_AUDIO producer).
     *
     * The slider in Settings → Performance & buffering → "Parallel
     * synth" controls how many engines storyvox loads. Each engine
     * has its own onnxruntime session (constructed via VoxSherpa's
     * public constructor in v2.7.8+ for Piper, v2.7.9+ for Kokoro),
     * so calls into them run truly in parallel without serializing
     * on the engineMutex.
     *
     * Memory cost is per-instance: ~150 MB for Piper, ~325 MB for
     * Kokoro. The slider tops out at 8 — beyond that, OS scheduling
     * overhead dominates and instance memory cost becomes pathological.
     *
     * The sequencer in [startParallelProducer] keeps queue order even
     * though completions arrive out-of-order; the consumer side sees
     * the same monotonic stream of sentence indices either way.
     */
    private val secondaryEngines: List<VoiceEngineHandle> = emptyList(),
) : PcmSource {

    /** SAM-style handle so tests can fake the engine without pulling the
     *  VoxSherpa AAR onto the JVM unit-test classpath. EnginePlayer wraps
     *  the singleton VoiceEngine / KokoroEngine in this. */
    interface VoiceEngineHandle {
        val sampleRate: Int
        fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray?
    }

    /**
     * Streaming-capable engine handle — emits PCM chunks as they
     * arrive instead of buffering the full sentence. Today only
     * Azure (HTTP source) implements this; Piper / Kokoro return
     * the full sentence from a synchronous JNI call so streaming
     * adds no value for them. The producer detects this interface
     * and uses the streaming path when present, falling back to
     * the per-sentence ByteArray path otherwise.
     *
     * Implementations MUST emit chunks in arrival order and complete
     * the Flow when the sentence is done. Errors thrown from the
     * Flow halt that sentence (the producer skips and moves on for
     * non-terminal errors; AuthFailed propagates and stops the
     * pipeline). Empty Flow is treated as "skip this sentence" the
     * same way [generateAudioPCM] returning null is.
     */
    interface StreamingVoiceEngineHandle : VoiceEngineHandle {
        fun generateAudioPCMStream(
            text: String,
            speed: Float,
            pitch: Float,
        ): kotlinx.coroutines.flow.Flow<ByteArray>
    }

    override val sampleRate: Int = engine.sampleRate

    /** True when this source will use the streaming producer path —
     *  emit many small chunks per sentence as TLS records arrive.
     *  v0.4.92 — false because streaming dispatch is disabled in
     *  startProducer. Once re-enabled, this should match the dispatch
     *  condition: `engine is StreamingVoiceEngineHandle && secondaryEngines.isEmpty()`. */
    override val isStreaming: Boolean = false

    /**
     * PR-7-bonus / Tier 2 (#87) — dedicated single-thread executor for
     * the producer. Pre-Tier-2 the producer ran on `Dispatchers.IO`,
     * a shared coroutine pool that migrates threads on every suspend.
     * `Process.setThreadPriority(URGENT_AUDIO)` is per-OS-thread, so
     * any priority bump leaked across resumptions when the coroutine
     * landed on a different IO worker.
     *
     * Pinning to a single thread keeps the URGENT_AUDIO priority for
     * the entire pipeline lifetime — same shape as the consumer
     * thread that EnginePlayer's startPlaybackPipeline already pins.
     * Reduces inter-sentence scheduling jitter, which was Hazel's
     * Tier 2 recommendation after the multi-core pass (#86) closed
     * the throughput gap. Closed in [close] so the executor doesn't
     * leak across pipeline rebuilds.
     */
    private val producerExecutor = run {
        // Tier 3 (#88): N parallel workers when secondaries are wired,
        // single thread otherwise (Tier 2 shape). Pool size for the
        // parallel path = workers (1 + secondaries) + 2 for the
        // sequencer + feeder coroutines. Without those extra two
        // slots the sequencer can starve when all worker threads are
        // blocked in JNI generateAudioPCM calls — produced chunks
        // get stuck in the completed map and never reach the
        // consumer queue → zero audio output despite pegged CPU.
        // (Bug surfaced 2026-05-10 on tablet w/ instances=2,2: 2
        // workers occupied both threads, sequencer never dispatched.)
        val workerCount = 1 + secondaryEngines.size
        val poolSize = if (secondaryEngines.isEmpty()) 1 else workerCount + 2
        val counter = java.util.concurrent.atomic.AtomicInteger(0)
        Executors.newFixedThreadPool(poolSize) { r ->
            Thread(r, "storyvox-tts-producer-${counter.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    }
    private val scope = CoroutineScope(
        SupervisorJob() + producerExecutor.asCoroutineDispatcher(),
    )
    private val running = AtomicBoolean(true)
    private val queue = LinkedBlockingQueue<Item>(queueCapacity)

    private val _bufferHeadroomMs = MutableStateFlow(0L)

    /**
     * Total ms of audio currently buffered in the queue (sum of pcm
     * playback duration + cadence-silence duration across queued chunks).
     * Updated atomically on every producer put and consumer take.
     *
     * The EnginePlayer consumer pauses AudioTrack and surfaces a
     * "Buffering..." UI state when this drops below an underrun threshold,
     * resumes at a higher hysteresis threshold. Lets the listener
     * experience the gap as a clean pause rather than dribbling silence
     * out of an underrunning AudioTrack ring buffer.
     */
    val bufferHeadroomMs: StateFlow<Long> = _bufferHeadroomMs.asStateFlow()

    /** ms of audio represented by [bytes] of PCM at this source's sample rate.
     *  16-bit signed mono → 2 bytes per sample, 1 channel. */
    private fun pcmDurationMs(bytes: Int): Long =
        bytes.toLong() * 1000L / (sampleRate.toLong() * 2L)

    private var producerJob: Job = startProducer(startSentenceIndex)

    override suspend fun nextChunk(): PcmChunk? = runInterruptible {
        val item = queue.take()
        if (item === END_PILL) return@runInterruptible null
        // 2026-05-09 — Argus Fix B (#79): the headroom decrement used
        // to fire here at dequeue time, but the listener hasn't yet
        // heard this audio (it's about to enter AudioTrack's hardware
        // ring buffer). Decrementing at dequeue made `bufferHeadroomMs`
        // reflect "audio in the queue" rather than "audio not yet
        // heard," which fired the underrun trigger one chunk-duration
        // earlier than the listener actually needed. The consumer
        // now calls [decrementHeadroomForChunk] after the AudioTrack
        // write loop exits — see [EnginePlayer]'s consumer.
        item.chunk
    }

    /**
     * Argus Fix B (#79) — called by the consumer AFTER it has finished
     * writing this chunk's PCM + trailing silence to AudioTrack. The
     * decrement happens late so [bufferHeadroomMs] reflects "audio the
     * listener hasn't heard yet" (queue + writes-in-flight), not "audio
     * still in the queue."
     *
     * Idempotent guard: if the consumer aborts mid-write (pause /
     * voice swap), it MUST still call this once after exiting the
     * write loop so the headroom doesn't drift upward. The producer-
     * side increment in [startProducer] is the matching counter; if
     * one fires without the other, [bufferHeadroomMs] desyncs and the
     * underrun threshold fires at the wrong time.
     */
    override fun decrementHeadroomForChunk(chunk: PcmChunk) {
        val durMs = pcmDurationMs(chunk.pcm.size) +
            pcmDurationMs(chunk.trailingSilenceBytes)
        _bufferHeadroomMs.update { (it - durMs).coerceAtLeast(0L) }
    }

    override suspend fun seekToCharOffset(charOffset: Int) {
        val target = sentences.indexOfLast { it.startChar <= charOffset }
            .takeIf { it >= 0 } ?: 0
        producerJob.cancel()
        queue.clear()
        producerJob = startProducer(target)
    }

    override suspend fun close() {
        running.set(false)
        producerJob.cancel()
        queue.clear()
        // Wake any consumer blocked in take() so nextChunk returns null.
        queue.offer(END_PILL)
        scope.cancel()
        // Tier 2 (#87) — shut the dedicated producer executor down so
        // the daemon thread exits and isn't leaked across pipeline
        // rebuilds (next chapter / seek / voice swap each spin a
        // fresh EngineStreamingSource → fresh executor).
        producerExecutor.shutdownNow()
        // #89 — block until the executor's threads actually finish.
        // shutdownNow() interrupts but doesn't wait; if a worker is
        // mid-JNI generateAudioPCM the interrupt is queued and the
        // thread keeps running until the JNI call returns. Without
        // awaitTermination, EnginePlayer.loadAndPlay can race ahead
        // and destroy() the secondary engines while a producer
        // thread is still inside generateAudioPCM on them — JNI
        // use-after-free on the native tts pointer.
        //
        // Generous 5s budget covers Piper-high's worst-case sentence
        // synth on Helio P22T (~3.5× realtime → ~7s for a 2s
        // sentence). If we exceed that, the rogue worker thread
        // leaks but at least subsequent state is consistent — the
        // app re-init at next pipeline rebuild gets a fresh executor.
        runCatching {
            producerExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun startProducer(fromIndex: Int): Job =
        when {
            // v0.4.92 — streaming dispatch RE-DISABLED. v0.4.90/.91
            // re-enabled it after diagnostic logs showed the producer/
            // consumer pipeline running correctly (sentences flowing,
            // headroom growing, AudioTrack state=started). But JP
            // confirmed live: no audio output despite all signals
            // green in logs. Without on-device debug capability we
            // can't bisect the silent failure between track.write()
            // and the speaker. Lookahead path (parallelSynthInstances
            // >= 2) and buffered serial path (instances == 1) both
            // produce real audio; streaming code is preserved on disk
            // for offline reproduction. To re-enable when fixed:
            //   engine is StreamingVoiceEngineHandle -> startStreamingSerialProducer(fromIndex)
            secondaryEngines.isNotEmpty() -> startParallelProducer(fromIndex)
            else -> startSerialProducer(fromIndex)
        }

    /**
     * Streaming serial producer — for engines that implement
     * [StreamingVoiceEngineHandle] (today: Azure). Per-sentence loop
     * is the same shape as [startSerialProducer]; the difference is
     * the inner work: instead of buffering the full PCM ByteArray
     * before queuing, we collect chunks from the engine's Flow and
     * queue each chunk as its own [PcmChunk].
     *
     * Each chunk shares the sentence's [SentenceRange] so the
     * highlight UI keeps tracking through the streamed sentence;
     * [trailingSilenceBytes] lands on a final empty-PCM chunk after
     * the flow completes (so the inter-sentence pause survives
     * without corrupting individual streamed chunks).
     *
     * Result: AudioTrack.write() can fire on the first 8 KB chunk
     * (~165 ms of audio) instead of waiting for the entire sentence
     * body to land — the perceived "press play → first audio"
     * latency drops from ~2-4 s (Dragon HD full render) to ~200-400 ms
     * (TLS + first frame).
     */
    private fun startStreamingSerialProducer(fromIndex: Int): Job = scope.launch {
        runCatching {
            AndroidProcess.setThreadPriority(
                AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO,
            )
        }
        val streaming = engine as StreamingVoiceEngineHandle
        try {
            for (i in fromIndex until sentences.size) {
                if (!running.get()) return@launch
                val s = sentences[i]
                val spokenText = pronunciationDictApply(s.text)
                val range = SentenceRange(s.index, s.startChar, s.endChar)
                val mult = punctuationPauseMultiplier.coerceAtLeast(0f)
                val pauseMs =
                    (trailingPauseMs(s.text) * mult) / speed.coerceAtLeast(0.5f)
                val silenceBytes = silenceBytesFor(pauseMs.toInt(), sampleRate)

                var emittedAny = false
                try {
                    engineMutex.withLock {
                        if (!running.get()) return@withLock
                        streaming.generateAudioPCMStream(
                            spokenText, speed, pitch,
                        ).collect { bytes ->
                            if (!running.get()) {
                                throw kotlinx.coroutines.CancellationException(
                                    "pipeline stopped mid-stream",
                                )
                            }
                            if (bytes.isEmpty()) return@collect
                            emittedAny = true
                            val chunk = PcmChunk(
                                sentenceIndex = i,
                                range = range,
                                pcm = bytes,
                                trailingSilenceBytes = 0,
                            )
                            runInterruptible { queue.put(Item(chunk)) }
                            _bufferHeadroomMs.update {
                                it + pcmDurationMs(bytes.size)
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Non-terminal stream error — skip this sentence's
                    // trailing silence and move on.
                    continue
                }

                if (!running.get()) return@launch
                if (emittedAny && silenceBytes > 0) {
                    // Final silence-only chunk seals the sentence's
                    // breathing room. Empty PCM is fine — the consumer's
                    // writer handles 0-byte payloads.
                    val tail = PcmChunk(
                        sentenceIndex = i,
                        range = range,
                        pcm = ByteArray(0),
                        trailingSilenceBytes = silenceBytes,
                    )
                    runInterruptible { queue.put(Item(tail)) }
                    _bufferHeadroomMs.update { it + pcmDurationMs(silenceBytes) }
                }
            }
            runInterruptible { queue.put(END_PILL) }
        } catch (_: Throwable) {
            // Cancelled (close, seek, voice swap) — silent.
        }
    }

    /**
     * Tier 3 (#88) — two-engine parallel producer. Sentences fan out
     * via a [Channel]; two workers each grab the next index and synth
     * concurrently. A sequencer drains a `ConcurrentHashMap<Int,
     * PcmChunk>` in monotonic sentence order to the existing
     * LinkedBlockingQueue, so consumers see the same in-order stream
     * they would in serial mode.
     *
     * Headroom accounting fires only when the sequencer pushes a
     * chunk to the queue — same point the serial producer increments
     * — so the underrun threshold sees consistent values regardless
     * of mode.
     */
    private fun startParallelProducer(fromIndex: Int): Job = scope.launch {
        val jobChan = kotlinx.coroutines.channels.Channel<Int>(
            kotlinx.coroutines.channels.Channel.UNLIMITED,
        )
        val completed = java.util.concurrent.ConcurrentHashMap<Int, PcmChunk>()
        val signal = MutableStateFlow(0L)

        // Feeder: walks sentence indices into the worker channel.
        val feeder = scope.launch {
            try {
                for (i in fromIndex until sentences.size) {
                    if (!running.get()) break
                    jobChan.send(i)
                }
            } finally {
                jobChan.close()
            }
        }

        // Tier 3 N-instance: 1 primary + N-1 secondaries. Primary
        // acquires engineMutex (so EnginePlayer.loadAndPlay's voice-
        // swap can safely destroy the model); secondaries don't —
        // each owns its own VoxSherpa instance with an independent
        // synchronized monitor at the JVM level (VoxSherpa v2.7.8+
        // for VoiceEngine, v2.7.9+ for KokoroEngine).
        val workers = mutableListOf<Job>()
        workers += scope.launch {
            runParallelWorker(engine, jobChan, completed, signal, useEngineMutex = true)
        }
        secondaryEngines.forEach { secondary ->
            workers += scope.launch {
                runParallelWorker(secondary, jobChan, completed, signal, useEngineMutex = false)
            }
        }

        // Sequencer: drain in order. Blocks on [signal] until the
        // next-expected sentence index has been completed.
        try {
            var next = fromIndex
            while (next < sentences.size && running.get()) {
                while (running.get() && !completed.containsKey(next)) {
                    signal.first { it != signal.value || completed.containsKey(next) }
                }
                if (!running.get()) break
                val chunk = completed.remove(next) ?: continue
                runInterruptible { queue.put(Item(chunk)) }
                _bufferHeadroomMs.update {
                    it + pcmDurationMs(chunk.pcm.size) +
                        pcmDurationMs(chunk.trailingSilenceBytes)
                }
                next++
            }
            // Natural end — push pill once all workers are drained.
            runInterruptible { queue.put(END_PILL) }
        } catch (_: Throwable) {
            // Cancelled (close, seek, voice swap) — silent.
        } finally {
            feeder.cancel()
            workers.forEach { it.cancel() }
        }
    }

    private suspend fun runParallelWorker(
        workerEngine: VoiceEngineHandle,
        jobChan: kotlinx.coroutines.channels.ReceiveChannel<Int>,
        completed: java.util.concurrent.ConcurrentHashMap<Int, PcmChunk>,
        signal: MutableStateFlow<Long>,
        useEngineMutex: Boolean,
    ) {
        // Bump priority on the worker's OS thread. The fixed-thread
        // pool guarantees this thread is dedicated; calls into the
        // engine don't suspend (they're synchronized JNI calls), so
        // the priority sticks for the worker's lifetime.
        runCatching {
            AndroidProcess.setThreadPriority(
                AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO,
            )
        }
        try {
            for (i in jobChan) {
                if (!running.get()) break
                val s = sentences[i]
                val spokenText = pronunciationDictApply(s.text)
                val pcm = if (useEngineMutex) {
                    engineMutex.withLock {
                        if (!running.get()) return@withLock null
                        workerEngine.generateAudioPCM(spokenText, speed, pitch)
                    }
                } else {
                    workerEngine.generateAudioPCM(spokenText, speed, pitch)
                } ?: continue
                if (!running.get()) break
                val mult = punctuationPauseMultiplier.coerceAtLeast(0f)
                val pauseMs = (trailingPauseMs(s.text) * mult) / speed.coerceAtLeast(0.5f)
                val silenceBytes = silenceBytesFor(pauseMs.toInt(), sampleRate)
                completed[i] = PcmChunk(
                    sentenceIndex = i,
                    range = SentenceRange(s.index, s.startChar, s.endChar),
                    pcm = pcm,
                    trailingSilenceBytes = silenceBytes,
                )
                signal.update { it + 1 }
            }
        } catch (_: Throwable) {
            // Cancelled — silent.
        }
    }

    private fun startSerialProducer(fromIndex: Int): Job = scope.launch {
        // Tier 2 (#87) — bump priority on the dedicated producer
        // thread. setThreadPriority sticks because the dispatcher is
        // single-threaded; coroutine suspends resume on the same OS
        // thread so the priority survives the engineMutex.withLock
        // and queue.put suspension points.
        runCatching {
            AndroidProcess.setThreadPriority(
                AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO,
            )
        }
        try {
            for (i in fromIndex until sentences.size) {
                if (!running.get()) return@launch
                val s = sentences[i]
                // Issue #135: substitute *only* the text fed to the
                // engine. `s.text` and the highlight char-range stay
                // unchanged — the user sees the original sentence in
                // the reader while the synthesizer reads the
                // phonetic respelling.
                val spokenText = pronunciationDictApply(s.text)
                val pcm = engineMutex.withLock {
                    if (!running.get()) return@withLock null
                    engine.generateAudioPCM(spokenText, speed, pitch)
                } ?: continue
                if (!running.get()) return@launch
                // Issue #90: the user-facing punctuation-pause selector
                // (Off/Normal/Long) lands here as a multiplier. 0× kills
                // the silence entirely, 1× preserves the pre-#90 default,
                // 1.75× ("Long") stretches it. Speed scaling still applies
                // on top so a 2× listener doesn't sit through long gaps
                // even on Long. coerceAtLeast(0f) defends against a
                // negative slipping in from a bad caller — silenceBytesFor
                // already coerces non-positive durations to 0 but we also
                // want the toInt() floor to behave.
                val mult = punctuationPauseMultiplier.coerceAtLeast(0f)
                val pauseMs =
                    (trailingPauseMs(s.text) * mult) / speed.coerceAtLeast(0.5f)
                val silenceBytes = silenceBytesFor(pauseMs.toInt(), sampleRate)
                val chunk = PcmChunk(
                    sentenceIndex = i,
                    range = SentenceRange(s.index, s.startChar, s.endChar),
                    pcm = pcm,
                    trailingSilenceBytes = silenceBytes,
                )
                runInterruptible { queue.put(Item(chunk)) }
                _bufferHeadroomMs.update {
                    it + pcmDurationMs(pcm.size) + pcmDurationMs(silenceBytes)
                }
            }
            // Natural end-of-chapter: push the pill so the consumer's
            // next nextChunk() returns null.
            runInterruptible { queue.put(END_PILL) }
        } catch (_: Throwable) {
            // Cancelled (close, seek, voice swap) — silent. A subsequent
            // nextChunk() either gets the pill from close() or the next
            // chunk from a restarted producer (seek).
        }
    }

    /** Wrapper so the END_PILL identity check via `===` is type-safe and
     *  the data class equals isn't tempted to compare the empty pcm. */
    private class Item(val chunk: PcmChunk)

    private companion object {
        val END_PILL = Item(PcmChunk(-1, SentenceRange(-1, -1, -1), ByteArray(0), 0))
    }
}

/**
 * Length of trailing silence to splice after a sentence's PCM, picked
 * by terminal punctuation. Neural TTS engines barely breathe between
 * sentences; without padding the listener hears one continuous block
 * of speech. Tuned for audiobook-style cadence at 1.0× speed; the
 * caller scales by 1/speed so a 2× listener doesn't sit through 700 ms
 * gaps.
 *
 * Robust against:
 *  - **Closing punctuation** wrapping a sentence (`"He left."`, `(yes!)`)
 *    — strip trailing closers + whitespace before looking at terminal char.
 *  - **Multi-character ellipsis** (`...` written as three dots, common in
 *    HTML-decoded chapter text) — mapped to the single-`…` bucket.
 *  - **Dash variants** — en-dash, em-dash, ASCII hyphen as cesura.
 *
 * Lifted verbatim from the pre-PR-A `EnginePlayer.trailingPauseMs`; this
 * is a refactor commit, no logic changes.
 */
internal fun trailingPauseMs(sentenceText: String): Int {
    val closePunct: Set<Char> = setOf('"', '\'', ')', ']', '}', '”', '’', '»', '」')
    var end = sentenceText.length
    while (end > 0 && (sentenceText[end - 1].isWhitespace() ||
            sentenceText[end - 1] in closePunct)) end--
    if (end == 0) return 60
    // Ellipsis (three-dot ASCII or Unicode U+2026) gets a longer pause than
    // a plain '.' — narrators audibly trail off on "Wait..." vs "Wait."
    // (Thalia's VoxSherpa P0 #3, 2026-05-08; matches AudioEmotionHelper's
    // 380 ms ellipsis bucket.)
    if (end >= 3 && sentenceText.regionMatches(end - 3, "...", 0, 3)) return 380
    return when (sentenceText[end - 1]) {
        '…' -> 380
        '.', '!', '?' -> 350
        ';', ':' -> 200
        ',', '—', '–', '-' -> 120
        // Sentences ending in a closer like ')' get the closer + whitespace
        // stripped above, so a parenthetical like "(yes)" falls through to
        // the inner content's terminal char ('s' here → 60ms fallback).
        // Documented behavior; if narrators want a longer parenthetical
        // pause, that's a separate enhancement (e.g. detect outermost
        // closer before strip).
        else -> 60
    }
}

/** Bytes of zero-PCM = `durationMs × sampleRate × 2` (16-bit mono).
 *  Lifted from EnginePlayer.silenceBytesFor; refactor commit. */
internal fun silenceBytesFor(durationMs: Int, sampleRate: Int): Int {
    if (durationMs <= 0) return 0
    return ((sampleRate.toLong() * durationMs / 1000L).toInt() * 2).coerceAtLeast(0)
}
