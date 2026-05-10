package `in`.jphe.storyvox.playback.tts.source

import `in`.jphe.storyvox.playback.SentenceRange
import `in`.jphe.storyvox.playback.tts.Sentence
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : PcmSource {

    /** SAM-style handle so tests can fake the engine without pulling the
     *  VoxSherpa AAR onto the JVM unit-test classpath. EnginePlayer wraps
     *  the singleton VoiceEngine / KokoroEngine in this. */
    interface VoiceEngineHandle {
        val sampleRate: Int
        fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray?
    }

    override val sampleRate: Int = engine.sampleRate

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
    }

    private fun startProducer(fromIndex: Int): Job = scope.launch {
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
