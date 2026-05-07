package `in`.jphe.storyvox.playback.tts

import android.content.Context
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Looper
import android.os.Process as AndroidProcess
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.CodeBySonu.VoxSherpa.KokoroEngine
import com.CodeBySonu.VoxSherpa.VoiceEngine
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.repository.PlaybackPositionRepository
import `in`.jphe.storyvox.data.repository.playback.PlaybackChapter
import `in`.jphe.storyvox.playback.PlaybackError
import `in`.jphe.storyvox.playback.PlaybackState
import `in`.jphe.storyvox.playback.PlaybackUiEvent
import `in`.jphe.storyvox.playback.SPEED_BASELINE_CHARS_PER_SECOND
import `in`.jphe.storyvox.playback.SentenceRange
import `in`.jphe.storyvox.playback.SleepTimer
import `in`.jphe.storyvox.playback.TtsVolumeRamp
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.VoiceManager
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * In-process Media3 [Player] that bypasses Android's [android.speech.tts.TextToSpeech]
 * framework. Talks directly to VoxSherpa's [VoiceEngine] (via the engine-lib AAR
 * pulled from JitPack) and manages its own [AudioTrack] with a fat ~2-second buffer.
 *
 * Why this exists: the framework path's `audioAvailable()` chunks land in an
 * AudioTrack whose buffer is sized by the framework — typically small enough
 * that on modest hardware (Galaxy Tab A7 Lite) Piper inference for the next
 * sentence can't finish before the current sentence's buffer drains, producing
 * an audible silence between every sentence. With our own AudioTrack we set
 * a buffer big enough that audio of N keeps playing while we generate N+1 on
 * a worker thread. Seek/pause/skip become AudioTrack operations.
 *
 * Producer/consumer model:
 *  - **Producer** ([generationJob]): walks the sentence list from
 *    [currentSentenceIndex], calls [VoiceEngine.generateAudioPCM] for each,
 *    pushes the resulting PCM byte[] onto [pcmQueue] together with the
 *    sentence range. Blocks on [java.util.concurrent.LinkedBlockingQueue.put]
 *    if the queue is full (back-pressure).
 *  - **Consumer** ([consumerThread]): pulls from [pcmQueue], writes PCM to
 *    the AudioTrack via [AudioTrack.write] (blocking write — returns when
 *    the AudioTrack accepts the bytes). Before writing, fires a
 *    `scope.launch` to Main that surfaces the new sentence range to the UI.
 *
 * Pause/seek tear down the AudioTrack and re-create on resume; cheaper than
 * trying to keep state coherent across a `pause()` call.
 */
@UnstableApi
class EnginePlayer @AssistedInject constructor(
    @Assisted private val context: Context,
    private val chunker: SentenceChunker,
    private val chapterRepo: ChapterRepository,
    private val positionRepo: PlaybackPositionRepository,
    private val sleepTimer: SleepTimer,
    private val voiceManager: VoiceManager,
    private val volumeRamp: TtsVolumeRamp,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    @AssistedFactory
    interface Factory {
        fun create(context: Context): EnginePlayer
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var sentences: List<Sentence> = emptyList()
    private var currentSentenceIndex: Int = 0
    private var currentSpeed: Float = 1.0f
    private var currentPitch: Float = 1.0f

    /** Engine type for the currently-loaded voice. Set in [loadAndPlay]
     *  after a successful model load; read by the producer in
     *  [startPlaybackPipeline] to decide which engine drives generation. */
    private var activeEngineType: EngineType? = null

    /** Voice id whose model is currently loaded. Used by the active-voice
     *  watcher (see [observeActiveVoice]) to decide whether a DataStore
     *  emission represents a real change worth reloading for, and by
     *  [resume] to detect a "voice changed while paused" situation that
     *  needs a full reload before playback can continue with the new model. */
    private var loadedVoiceId: String? = null

    /** Flagged when the user picks a different voice while playback is
     *  paused. The flag tells [resume] to route through [loadAndPlay] for
     *  a fresh model load instead of restarting the existing pipeline. */
    private var voiceReloadPending: Boolean = false

    init {
        observeActiveVoice()
    }

    /**
     * Watches [VoiceManager.activeVoice] and reacts to user-driven voice
     * changes. The catalog of cases:
     *
     *  - Active voice changes mid-playback → reload the engine and resume
     *    from the current char offset so the listener hears the new voice
     *    immediately (issue #8).
     *  - Active voice changes while paused → flag a pending reload and stop
     *    the existing pipeline; the next [resume] call will do a full
     *    [loadAndPlay] with the new voice.
     *  - Active voice changes before any chapter is loaded → no-op; the
     *    next [loadAndPlay] reads activeVoice itself.
     *
     *  De-dup: we track [loadedVoiceId] so re-emissions of the same id
     *  (e.g. after [setActive] writes the same value, or first-launch
     *  hydration) are filtered out.
     */
    private fun observeActiveVoice() {
        scope.launch {
            voiceManager.activeVoice.collect { active ->
                val newId = active?.id ?: return@collect
                if (newId == loadedVoiceId) {
                    // No-op flip — typically the user re-activated the same
                    // voice, or DataStore re-emitted the persisted value. If
                    // a previous flip had armed [voiceReloadPending] for a
                    // different id and the user has now flipped *back* to
                    // the loaded voice before resuming, clear the pending
                    // flag so we don't force a needless model reload (a
                    // 30 s Kokoro warm-up) on the next [resume].
                    voiceReloadPending = false
                    return@collect
                }
                val s = _observableState.value
                val fictionId = s.currentFictionId
                val chapterId = s.currentChapterId
                if (fictionId == null || chapterId == null) return@collect
                if (s.isPlaying) {
                    // Live swap. Tear down the current pipeline FIRST so the
                    // old generator can't keep pushing old-voice PCM into
                    // the (about-to-be-replaced) queue while loadAndPlay
                    // sits in loadModel for ~30 s on Kokoro. Without this,
                    // the user hears 5–10 s of stale audio before silence
                    // and finally the new voice.
                    stopPlaybackPipeline()
                    loadAndPlay(fictionId, chapterId, s.charOffset)
                } else {
                    voiceReloadPending = true
                    stopPlaybackPipeline()
                    activeEngineType = null
                }
            }
        }
    }

    /** AudioTrack for the active chapter. Recreated on play/seek/sample-rate change. */
    private var audioTrack: AudioTrack? = null

    /** Producer-consumer plumbing. PCM is tagged with its sentence so the
     *  consumer knows what range to surface when playback actually reaches it.
     *  [trailingSilenceBytes] is appended **after** [pcm] is written so the
     *  consumer can spool zeros out of a shared buffer instead of every
     *  sentence carrying a freshly-allocated copy of pcm + silence (issue #3). */
    private data class SentencePcm(
        val sentenceIndex: Int,
        val pcm: ByteArray,
        val trailingSilenceBytes: Int,
        val range: SentenceRange,
    )

    private var pcmQueue: LinkedBlockingQueue<SentencePcm>? = null
    private var generationJob: Job? = null

    /** Dedicated playback thread. The agent that gets URGENT_AUDIO and never
     *  yields back to a coroutine pool — see the comment on [startPlaybackPipeline]
     *  for why this can't be a coroutine. */
    private var consumerThread: Thread? = null

    /** Per-pipeline run flag. Flipped to false by [stopPlaybackPipeline]; the
     *  consumer checks it inside both the inter-sentence and intra-sentence
     *  loops so it can bail out of long [AudioTrack.write] sequences without
     *  waiting for the buffer to drain. */
    private val pipelineRunning = AtomicBoolean(false)

    /** Serializes [VoiceEngine.generateAudioPCM] / [KokoroEngine.generateAudioPCM]
     *  calls. The VoxSherpa engines are process-singletons and the underlying
     *  Sonic/onnxruntime state isn't safe across concurrent threads. Every
     *  pipeline-restart event ([setSpeed], [setPitch], [seekToCharOffset],
     *  voice swap) cancels the old generator coroutine, but cancellation only
     *  fires at suspension points — a JNI call already in flight runs to
     *  completion. Without this mutex, the new pipeline's generator can call
     *  the engine *while the old one is still inside it*, corrupting the
     *  internal state and producing garbled PCM. */
    private val engineMutex = Mutex()

    private val _observableState = MutableStateFlow(PlaybackState())
    val observableState: StateFlow<PlaybackState> = _observableState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<PlaybackUiEvent>(extraBufferCapacity = 4)
    val uiEvents: SharedFlow<PlaybackUiEvent> = _uiEvents.asSharedFlow()

    override fun getState(): State {
        val s = _observableState.value
        return State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_GET_METADATA,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_SET_SPEED_AND_PITCH,
                    )
                    .build(),
            )
            .setPlayWhenReady(s.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (s.currentChapterId != null) Player.STATE_READY else Player.STATE_IDLE)
            .setPlaylist(buildPlaylist(s))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs { (s.charOffset * 1000L) / charsPerSecondLong() }
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    // CONTENT_TYPE_MUSIC, not _SPEECH — see createAudioTrack().
                    // Mirrors the AudioTrack-level fix; otherwise the
                    // MediaSession descriptor still advertises speech to
                    // AudioFlinger and Samsung's session-metadata routing
                    // can apply speech-DSP independently of the AudioTrack.
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setPlaybackParameters(PlaybackParameters(currentSpeed, currentPitch))
            .build()
    }

    private fun buildPlaylist(s: PlaybackState): ImmutableList<MediaItemData> {
        val chapterId = s.currentChapterId ?: return ImmutableList.of()
        return ImmutableList.of(
            MediaItemData.Builder(chapterId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(s.chapterTitle ?: "Chapter")
                        .setAlbumTitle(s.bookTitle)
                        .setArtworkUri(s.coverUri?.let { android.net.Uri.parse(it) })
                        .build(),
                )
                .setDurationUs(s.durationEstimateMs * 1000L)
                .build(),
        )
    }

    private fun charsPerSecondLong(): Long {
        val v = SPEED_BASELINE_CHARS_PER_SECOND * currentSpeed
        return v.toLong().coerceAtLeast(1L)
    }

    // ----- Storyvox-internal API -----

    suspend fun loadAndPlay(fictionId: String, chapterId: String, charOffset: Int) {
        val chapter: PlaybackChapter = chapterRepo.getChapter(chapterId) ?: run {
            _observableState.update {
                it.copy(
                    isPlaying = false,
                    error = PlaybackError.ChapterFetchFailed(
                        "Chapter not ready (id=$chapterId). Download may still be queued.",
                    ),
                )
            }
            return
        }

        val active = voiceManager.activeVoice.first()
        if (active == null) {
            _observableState.update {
                it.copy(isPlaying = false, error = PlaybackError.EngineUnavailable)
            }
            return
        }

        // Surface the chapter + isPlaying=true BEFORE the model loads so the
        // UI's "warming up" state (sentenceEnd == 0 && isPlaying) shows the
        // brass spinner immediately. Sherpa-onnx Kokoro init can take 30+s
        // on modest hardware; without this the screen sits blank that long.
        val text = chapter.text
        sentences = chunker.chunk(text)
        currentSentenceIndex = sentences.indexOfFirst { charOffset <= it.endChar }
            .takeIf { it >= 0 } ?: 0
        _observableState.update {
            it.copy(
                currentFictionId = fictionId,
                currentChapterId = chapterId,
                charOffset = charOffset,
                isPlaying = true,
                bookTitle = chapter.bookTitle,
                chapterTitle = chapter.title,
                coverUri = chapter.coverUrl,
                durationEstimateMs = estimateDurationMs(text),
                currentSentenceRange = null,
                error = null,
            )
        }
        invalidateState()

        val loadResult: String = withContext(Dispatchers.IO) {
            // Critical: serialize loadModel against in-flight generateAudioPCM
            // by holding engineMutex (issue #11). Without it, a Piper-to-Piper
            // swap can call loadModel().destroy() and free the native `tts`
            // pointer while the prior generator's JNI generate(...) is still
            // dereferencing it on another thread → SIGSEGV. The producer
            // coroutine takes engineMutex around every generateAudioPCM, so
            // withLock here waits for the in-flight call to finish before
            // we tear the model down.
            engineMutex.withLock {
                when (active.engineType) {
                    EngineType.Piper -> {
                        val voiceDir = voiceManager.voiceDirFor(active.id)
                        val onnx = File(voiceDir, "model.onnx").absolutePath
                        val tokens = File(voiceDir, "tokens.txt").absolutePath
                        VoiceEngine.getInstance().loadModel(context, onnx, tokens)
                            ?: "Error: load returned null"
                    }
                    is EngineType.Kokoro -> {
                        // All 53 Kokoro speakers share a single ~325MB fp32
                        // multi-speaker model. Switching speakers reuses the
                        // loaded engine; first load takes 30+s as sherpa-onnx
                        // builds the onnxruntime session and runs a warm-up
                        // generate.
                        val sharedDir = voiceManager.kokoroSharedDir()
                        val onnx = File(sharedDir, "model.onnx").absolutePath
                        val tokens = File(sharedDir, "tokens.txt").absolutePath
                        val voicesBin = File(sharedDir, "voices.bin").absolutePath
                        KokoroEngine.getInstance().setActiveSpeakerId(
                            (active.engineType as EngineType.Kokoro).speakerId,
                        )
                        KokoroEngine.getInstance().loadModel(context, onnx, tokens, voicesBin)
                            ?: "Error: load returned null"
                    }
                }
            }
        }
        if (loadResult != "Success") {
            _observableState.update {
                it.copy(
                    isPlaying = false,
                    error = PlaybackError.ChapterFetchFailed("Voice load failed: $loadResult"),
                )
            }
            invalidateState()
            return
        }
        activeEngineType = active.engineType
        loadedVoiceId = active.id
        voiceReloadPending = false

        // (state was already pushed above so the spinner could show during
        // model load — refresh durationEstimate now that the active engine
        // type is known but otherwise leave state alone.)
        _observableState.update {
            it.copy(
                error = null,
            )
        }
        startPlaybackPipeline()
        invalidateState()
    }

    /**
     * Spin up the producer/consumer pair. Mirrors VoxSherpa standalone's
     * playback shape exactly because deviating from it has measurably
     * fuzzy output on Tab A7 Lite (issue #6):
     *
     *  - **Consumer = pinned [Thread]**, not a coroutine. `Process.set-`
     *    `ThreadPriority(URGENT_AUDIO)` is per-OS-thread; coroutines on
     *    [Dispatchers.IO] migrate threads on every suspend, so any priority
     *    bump leaks across resumptions. A dedicated thread keeps URGENT_AUDIO
     *    for the entire pipeline lifetime.
     *  - **Queue = [LinkedBlockingQueue]**, not [kotlinx.coroutines.channels.Channel].
     *    `take()` blocks the OS thread directly, no coroutine state-machine
     *    overhead, no risk of dispatcher work-stealing introducing scheduling
     *    jitter between sentences.
     *  - **Buffer = [AudioTrack.getMinBufferSize]** (set in [createAudioTrack]).
     *    Larger buffers route through AudioFlinger's deep-buffer mixer, which
     *    on Samsung tablets uses a different sample-rate-conversion path than
     *    the fast-track mixer used for `minBufferSize` tracks. Empirically the
     *    deep-buffer path adds the residual fuzz that survives the legacy
     *    `STREAM_MUSIC` constructor swap.
     *  - **[engineMutex] around `generateAudioPCM`** so a stop-then-start
     *    sequence (slider drag, voice swap, seek) never has two threads
     *    inside the singleton VoxSherpa engine at once.
     *
     * Pre-fetching is implicit via the queue's capacity — generation runs
     * ahead of playback by however many slots are free.
     */
    private fun startPlaybackPipeline() {
        // Make sure any previous run is fully stopped.
        stopPlaybackPipeline()

        val engineType = activeEngineType
        val sampleRate = when (engineType) {
            is EngineType.Kokoro -> KokoroEngine.getInstance().sampleRate
            else -> VoiceEngine.getInstance().sampleRate
        }.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
        val track = createAudioTrack(sampleRate)
        audioTrack = track

        val queue = LinkedBlockingQueue<SentencePcm>(QUEUE_CAPACITY)
        pcmQueue = queue
        pipelineRunning.set(true)

        generationJob = ioScope.launch {
            // Inference is CPU-heavy on modest hardware (Tab A7 Lite); without
            // an audio-priority bump the producer can lag the AudioTrack drain
            // and we underrun. VoxSherpa standalone does the same on its
            // generator thread.
            AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                for (i in currentSentenceIndex until sentences.size) {
                    // Re-apply URGENT_AUDIO each iteration: engineMutex.withLock
                    // and runInterruptible (below) are suspension points, and
                    // Dispatchers.IO can resume us on a different worker thread
                    // that's at default priority.
                    AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)
                    if (!pipelineRunning.get()) return@launch
                    val s = sentences[i]
                    val pcm = engineMutex.withLock {
                        AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)
                        if (!pipelineRunning.get()) return@withLock null
                        when (engineType) {
                            is EngineType.Kokoro -> KokoroEngine.getInstance()
                                .generateAudioPCM(s.text, currentSpeed, currentPitch)
                            else -> VoiceEngine.getInstance()
                                .generateAudioPCM(s.text, currentSpeed, currentPitch)
                        }
                    } ?: continue
                    // Neural TTS engines compress the natural inter-sentence
                    // pause down to almost nothing — instruct the consumer
                    // to spool N zero-bytes of silence after this sentence,
                    // sized by the sentence's terminal punctuation. We don't
                    // splice the silence into a fresh ByteArray here because
                    // each sentence's PCM is already 50–500 KB and a copy
                    // per sentence is avoidable GC pressure.
                    val pauseMs = trailingPauseMs(s.text) / currentSpeed.coerceAtLeast(0.5f)
                    val silenceBytes = silenceBytesFor(pauseMs.toInt(), sampleRate)
                    val item = SentencePcm(
                        sentenceIndex = i,
                        pcm = pcm,
                        trailingSilenceBytes = silenceBytes,
                        range = SentenceRange(s.index, s.startChar, s.endChar),
                    )
                    // runInterruptible turns Thread.interrupt (sent by
                    // stopPlaybackPipeline → cancel) into a coroutine
                    // CancellationException, so a blocked put() doesn't
                    // wedge the producer when shutdown races a full queue.
                    runInterruptible { queue.put(item) }
                }
                // Natural end — let the consumer finish draining and then
                // run the chapter-done handler.
                runInterruptible { queue.put(NATURAL_END_PILL) }
            } catch (_: Throwable) {
                // Cancelled or engine fault — silent. The consumer is shut
                // down by stopPlaybackPipeline directly, no pill needed.
            }
        }

        consumerThread = Thread({
            AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)
            var naturalEnd = false
            var firstSentence = true
            // Live track volume mirror. Scoped to the consumer thread (not
            // per-sentence) so the per-write change-detection skips the
            // setVolume JNI call when the ramp is idle, which is the steady
            // state. Seeded in the firstSentence block below.
            var lastVol = -1f
            try {
                while (pipelineRunning.get()) {
                    val item = try {
                        queue.take()
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (item === NATURAL_END_PILL) { naturalEnd = true; break }

                    // Surface the new sentence range BEFORE writing. Fire-
                    // and-forget on Main — withContext would force this
                    // thread to coordinate with the coroutine dispatcher,
                    // which is the whole reason we left coroutines.
                    scope.launch {
                        currentSentenceIndex = item.sentenceIndex
                        _observableState.update {
                            it.copy(
                                currentSentenceRange = item.range,
                                charOffset = item.range.startCharInChapter,
                            )
                        }
                    }

                    if (firstSentence) {
                        val v = volumeRamp.current
                        runCatching { track.setVolume(v) }
                        lastVol = v
                        runCatching { track.play() }
                        firstSentence = false
                    }

                    // Apply the SleepTimer fade-out ramp to the live track.
                    // Polled per write iteration; AudioTrack.setVolume is a
                    // cheap JNI call but the lastVol guard skips it entirely
                    // when the ramp is idle (steady state).
                    var written = 0
                    while (written < item.pcm.size && pipelineRunning.get()) {
                        val v = volumeRamp.current
                        if (v != lastVol) {
                            runCatching { track.setVolume(v) }
                            lastVol = v
                        }
                        val n = track.write(item.pcm, written, item.pcm.size - written)
                        if (n < 0) break // error code from AudioTrack
                        written += n
                    }
                    // Spool trailing silence from a shared zero-filled
                    // buffer (no per-sentence allocation).
                    var remaining = item.trailingSilenceBytes
                    while (remaining > 0 && pipelineRunning.get()) {
                        val v = volumeRamp.current
                        if (v != lastVol) {
                            runCatching { track.setVolume(v) }
                            lastVol = v
                        }
                        val chunk = remaining.coerceAtMost(SILENCE_CHUNK.size)
                        val n = track.write(SILENCE_CHUNK, 0, chunk)
                        if (n < 0) break
                        remaining -= n
                    }
                }
            } finally {
                // Release the AudioTrack from the same thread that owns
                // its write() loop. Doing this from the main thread (as
                // [stopPlaybackPipeline] used to) raced with whatever
                // write() was in flight and could JNI-crash. Now release
                // happens *after* the write loop has definitely exited.
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.release() }
                if (naturalEnd && pipelineRunning.get()) {
                    scope.launch {
                        sleepTimer.signalChapterEnd()
                        handleChapterDone()
                    }
                }
            }
        }, "storyvox-audio-out").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopPlaybackPipeline() {
        // Shutdown handshake:
        //  1. Signal stop via the run flag.
        //  2. Pause + flush the AudioTrack so any currently-blocked
        //     write() returns immediately (ring buffer has space again).
        //  3. Cancel the producer coroutine; runInterruptible turns the
        //     thread interrupt into a CancellationException for blocked
        //     queue.put() calls.
        //  4. Interrupt + join the consumer thread (so a queue.take()
        //     blocked on an empty queue wakes up).
        //  5. The consumer's own finally block does the AudioTrack
        //     release — *not* main thread — so we can't race a write().
        //     If join times out we still don't release ourselves; the
        //     consumer will finish its current write and clean up
        //     whenever it gets there.
        pipelineRunning.set(false)

        val track = audioTrack
        audioTrack = null
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
        }

        generationJob?.cancel()
        generationJob = null

        val t = consumerThread
        consumerThread = null
        if (t != null && t !== Thread.currentThread()) {
            t.interrupt()
            // 2 s upper bound — plenty for a paused+flushed track to
            // finish its current write iteration. If we somehow exceed
            // this the consumer keeps living until it's done; the OLD
            // track's release is its responsibility.
            try { t.join(2_000) } catch (_: InterruptedException) {}
        }

        pcmQueue = null
    }

    /** Build an AudioTrack that mirrors the standalone VoxSherpa demo exactly:
     *  legacy `STREAM_MUSIC` constructor, `getMinBufferSize()` buffer (no
     *  multiplier), `MODE_STREAM`. This is deliberate even though it costs us
     *  the seconds-long pre-render cushion the bigger buffer provided.
     *
     *  Why minBuffer specifically: AudioFlinger has two media mixer paths.
     *  Tracks created with a buffer ≈ minBufferSize qualify for the
     *  fast-track (low-latency) mixer; larger buffers route through the
     *  deep-buffer mixer. On Samsung tablets the deep-buffer mixer uses a
     *  different sample-rate-conversion chain that introduces audible
     *  distortion on 22050/24000Hz mono speech PCM. The bigger buffer
     *  bought us ~2 seconds of generator headroom but lost us clean output;
     *  the producer now keeps the generator queue full ([QUEUE_CAPACITY]
     *  sentences ahead) so the small buffer never empties between writes.
     *
     *  We also keep the legacy constructor (vs `AudioTrack.Builder`) because
     *  on Samsung the Builder path with `USAGE_MEDIA + CONTENT_TYPE_MUSIC`
     *  goes through the AudioAttributes routing layer, which on some firmware
     *  applies SoundAlive/Atmos. The legacy ctor advertises `STREAM_MUSIC`
     *  directly to AudioFlinger and bypasses those effects on the affected
     *  devices. VoxSherpa standalone does the same. */
    @Suppress("DEPRECATION")
    private fun createAudioTrack(sampleRate: Int): AudioTrack {
        val channelMask = AndroidAudioFormat.CHANNEL_OUT_MONO
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        return AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelMask,
            encoding,
            bufferSize,
            AudioTrack.MODE_STREAM,
        )
    }

    fun pauseTts() {
        _observableState.update { it.copy(isPlaying = false) }
        stopPlaybackPipeline()
        scope.launch { persistPosition() }
        invalidateState()
    }

    fun resume() {
        if (sentences.isEmpty()) return
        // If the user activated a different voice while paused (#8), the
        // existing engine model is the wrong one — route through loadAndPlay
        // to swap it before any audio comes out.
        if (voiceReloadPending) {
            val s = _observableState.value
            val fictionId = s.currentFictionId
            val chapterId = s.currentChapterId
            if (fictionId != null && chapterId != null) {
                voiceReloadPending = false
                scope.launch { loadAndPlay(fictionId, chapterId, s.charOffset) }
                return
            }
            voiceReloadPending = false
        }
        _observableState.update { it.copy(isPlaying = true, error = null) }
        startPlaybackPipeline()
        invalidateState()
    }

    fun seekToCharOffset(offset: Int) {
        if (sentences.isEmpty()) return
        val clamped = offset.coerceAtLeast(0)
        val target = sentences.indexOfLast { it.startChar <= clamped }
            .takeIf { it >= 0 } ?: 0
        currentSentenceIndex = target
        val s = sentences[target]
        // Issue #7: also update currentSentenceRange so the brass underline
        // and auto-scroll move to the tapped sentence — previously only
        // charOffset moved, leaving the visual highlight stale.
        _observableState.update {
            it.copy(
                charOffset = s.startChar,
                currentSentenceRange = SentenceRange(s.index, s.startChar, s.endChar),
            )
        }
        if (_observableState.value.isPlaying) startPlaybackPipeline()
        invalidateState()
    }

    suspend fun advanceChapter(direction: Int) {
        val current = _observableState.value.currentChapterId ?: return
        val fiction = _observableState.value.currentFictionId ?: return
        val nextId = if (direction >= 0) {
            chapterRepo.getNextChapterId(current)
        } else {
            chapterRepo.getPreviousChapterId(current)
        } ?: run {
            if (direction >= 0) _uiEvents.tryEmit(PlaybackUiEvent.BookFinished)
            return
        }
        chapterRepo.queueChapterDownload(fiction, nextId, requireUnmetered = false)
        chapterRepo.observeChapter(nextId).filterNotNull().first()
        loadAndPlay(fiction, nextId, charOffset = 0)
        _uiEvents.tryEmit(PlaybackUiEvent.ChapterChanged(nextId))
    }

    private suspend fun handleChapterDone() {
        val chapterId = _observableState.value.currentChapterId
        persistPosition()
        if (chapterId != null) chapterRepo.markChapterPlayed(chapterId)
        advanceChapter(direction = 1)
    }

    private suspend fun persistPosition() {
        val s = _observableState.value
        val fictionId = s.currentFictionId ?: return
        val chapterId = s.currentChapterId ?: return
        positionRepo.save(
            fictionId = fictionId,
            chapterId = chapterId,
            charOffset = s.charOffset,
            durationEstimateMs = s.durationEstimateMs,
        )
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed
        _observableState.update { it.copy(speed = speed) }
        if (_observableState.value.isPlaying) startPlaybackPipeline() // rebuild with new speed
        invalidateState()
    }

    fun setPitch(pitch: Float) {
        currentPitch = pitch
        _observableState.update { it.copy(pitch = pitch) }
        if (_observableState.value.isPlaying) startPlaybackPipeline()
        invalidateState()
    }

    fun setVoice(@Suppress("UNUSED_PARAMETER") voiceId: String) {
        // Voice changes require re-loading the engine model. Punted to a
        // VoiceEngineLoader the EnginePlayer will gain in v0.4.x — for now
        // we use whichever model VoiceEngine.loadModel was last called with.
    }

    fun setTtsVolume(v: Float) {
        volumeRamp.set(v)
        runCatching { audioTrack?.setVolume(v.coerceIn(0f, 1f)) }
    }

    private fun estimateDurationMs(text: String): Long {
        val charsPerSec = SPEED_BASELINE_CHARS_PER_SECOND * currentSpeed
        if (charsPerSec <= 0f) return 0L
        return ((text.length / charsPerSec) * 1000f).toLong()
    }

    // ----- SimpleBasePlayer command handlers -----

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) resume() else pauseTts()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        stopPlaybackPipeline()
        _observableState.update { it.copy(isPlaying = false, currentSentenceRange = null) }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        setSpeed(playbackParameters.speed)
        setPitch(playbackParameters.pitch)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        releaseEngine()
        return Futures.immediateVoidFuture()
    }

    fun releaseEngine() {
        kotlinx.coroutines.runBlocking { persistPosition() }
        stopPlaybackPipeline()
        scope.cancel()
        ioScope.cancel()
    }

    /**
     * Length of trailing silence to splice after a sentence's PCM, picked
     * by the punctuation it ends with. Neural TTS engines barely breathe
     * between sentences; without these padding gaps the listener hears one
     * continuous block of speech. Values are tuned to match audiobook-style
     * cadence at 1.0× speed; the producer scales them down at higher speeds
     * so a 2× listener doesn't sit through 700ms gaps.
     *
     * The tail-detection is robust against:
     *  - **Closing punctuation** wrapping a sentence: `"He left."`, `(yes!)`,
     *    `'maybe?'` — we strip trailing closers and whitespace before
     *    looking at the real terminal char.
     *  - **Multi-character ellipsis** (`...` written as three dots, common
     *    in HTML-decoded chapter text where the real `…` is rare) — the
     *    "..." literal is mapped to the same bucket as `…`.
     *  - **Dash variants** — en-dash, em-dash, and ASCII hyphen used as a
     *    sentence-internal cesura.
     */
    private fun trailingPauseMs(sentenceText: String): Int {
        // Strip trailing whitespace + closing punctuation/quotes so we look
        // at the terminal *speech* character, not the visual close-mark.
        var end = sentenceText.length
        while (end > 0 && (sentenceText[end - 1].isWhitespace() ||
                sentenceText[end - 1] in CLOSE_PUNCT)) end--
        if (end == 0) return 60
        // Multi-char ellipsis takes precedence over the single-char switch.
        if (end >= 3 && sentenceText.regionMatches(end - 3, "...", 0, 3)) return 350
        return when (sentenceText[end - 1]) {
            '.', '!', '?', '…' -> 350
            ';', ':' -> 200
            ',', '—', '–', '-' -> 120
            else -> 60
        }
    }

    private fun silenceBytesFor(durationMs: Int, sampleRate: Int): Int {
        if (durationMs <= 0) return 0
        // 16-bit signed PCM = 2 bytes per sample, mono = 1 channel.
        return ((sampleRate.toLong() * durationMs / 1000L).toInt() * 2).coerceAtLeast(0)
    }

    private companion object {
        /** Buffered slots ahead of the playback consumer. Each slot holds one
         *  sentence's PCM (~50–500KB). At 8 slots × ~250KB avg ≈ 2MB peak.
         *  This is the *only* generator headroom we have now that the
         *  AudioTrack itself uses minBufferSize (~130 ms at 22 kHz) — the
         *  queue has to stay full or the buffer underruns and we hear clicks. */
        const val QUEUE_CAPACITY = 8

        /** Fallback when the engine reports a non-positive sample rate (model
         *  not loaded yet). Piper voices are 22050Hz; Kokoro is 24000Hz. */
        const val DEFAULT_SAMPLE_RATE = 22050

        /** Closing-punctuation characters stripped before deciding the
         *  terminal punctuation for trailing-silence sizing. Covers ASCII
         *  + curly + bracketed close marks. */
        val CLOSE_PUNCT: Set<Char> = setOf(
            '"', '\'', ')', ']', '}', '”', '’', '»', '」',
        )

        /** Shared zero-filled buffer the consumer writes from to spool
         *  inter-sentence silence. Sized for one chunk @ 24 kHz mono 16-bit
         *  ≈ 350 ms, which is the longest silence we ever emit; longer
         *  silences chain multiple writes from the same buffer. Static so
         *  every sentence reuses the same allocation. */
        val SILENCE_CHUNK: ByteArray = ByteArray(24_000 * 2 * 350 / 1000)

        /** Sentinel pushed by the producer onto the queue when it has run
         *  out of sentences naturally (i.e. the chapter is finished). The
         *  consumer thread checks for object identity (`===`) and triggers
         *  the chapter-done handler instead of treating it like real audio.
         *  A cancellation-driven shutdown does NOT enqueue this — it just
         *  flips [pipelineRunning] to false and interrupts the consumer. */
        val NATURAL_END_PILL: SentencePcm = SentencePcm(
            sentenceIndex = -1,
            pcm = ByteArray(0),
            trailingSilenceBytes = 0,
            range = SentenceRange(-1, -1, -1),
        )
    }
}
