package `in`.jphe.storyvox.playback.tts

import android.content.Context
import android.media.AudioAttributes as AndroidAudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Looper
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
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.VoiceManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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
 *    sentence range. Suspends if the queue is full (back-pressure).
 *  - **Consumer** ([playbackJob]): pulls from [pcmQueue], writes PCM to the
 *    AudioTrack via [AudioTrack.write] (blocking write — returns when the
 *    AudioTrack accepts the bytes; usually fast because the AudioTrack's
 *    own buffer absorbs them). Before writing, schedules a frame-position
 *    callback that fires when the framework actually starts playing this
 *    sentence — that's when we surface `currentSentenceRange` to the UI.
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

    /** AudioTrack for the active chapter. Recreated on play/seek/sample-rate change. */
    private var audioTrack: AudioTrack? = null

    /** Producer-consumer plumbing. PCM is tagged with its sentence so the
     *  consumer knows what range to surface when playback actually reaches it. */
    private data class SentencePcm(
        val sentenceIndex: Int,
        val pcm: ByteArray,
        val range: SentenceRange,
    )

    private var pcmQueue: Channel<SentencePcm>? = null
    private var generationJob: Job? = null
    private var playbackJob: Job? = null

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
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
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

        val loadResult: String = when (active.engineType) {
            EngineType.Piper -> {
                val voiceDir = voiceManager.voiceDirFor(active.id)
                val onnx = File(voiceDir, "model.onnx").absolutePath
                val tokens = File(voiceDir, "tokens.json").absolutePath
                VoiceEngine.getInstance().loadModel(context, onnx, tokens) ?: "Error: load returned null"
            }
            is EngineType.Kokoro -> {
                // Shared Kokoro model bundling is punted to a future task —
                // the catalog only carries speaker IDs, not on-disk model
                // paths. Until that lands, refuse to play Kokoro voices.
                _observableState.update {
                    it.copy(isPlaying = false, error = PlaybackError.EngineUnavailable)
                }
                return
            }
        }
        if (loadResult != "Success") {
            _observableState.update {
                it.copy(
                    isPlaying = false,
                    error = PlaybackError.ChapterFetchFailed("Voice load failed: $loadResult"),
                )
            }
            return
        }
        activeEngineType = active.engineType

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
                error = null,
            )
        }
        startPlaybackPipeline()
        invalidateState()
    }

    /**
     * Spin up the producer/consumer pair. The generation worker walks the
     * sentence list from currentSentenceIndex onward, pushing PCM onto a
     * buffered channel; the playback worker pulls and writes to AudioTrack.
     * Pre-fetching is implicit via the channel's capacity — generation runs
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
        audioTrack = createAudioTrack(sampleRate).also { it.play() }

        val queue = Channel<SentencePcm>(QUEUE_CAPACITY)
        pcmQueue = queue

        generationJob = ioScope.launch {
            try {
                for (i in currentSentenceIndex until sentences.size) {
                    val s = sentences[i]
                    val pcm = when (engineType) {
                        is EngineType.Kokoro -> KokoroEngine.getInstance()
                            .generateAudioPCM(s.text, currentSpeed, currentPitch)
                        else -> VoiceEngine.getInstance()
                            .generateAudioPCM(s.text, currentSpeed, currentPitch)
                    } ?: continue // skip silently; defensive
                    queue.send(
                        SentencePcm(
                            sentenceIndex = i,
                            pcm = pcm,
                            range = SentenceRange(s.index, s.startChar, s.endChar),
                        ),
                    )
                }
            } catch (_: Throwable) {
                // Channel closed (consumer cancelled) or engine fault — just stop.
            } finally {
                queue.close()
            }
        }

        playbackJob = ioScope.launch {
            try {
                for (item in queue) {
                    // Surface the new sentence range BEFORE writing — write blocks
                    // for the duration of the previous sentence's buffer drain,
                    // so this is the last opportunity the UI gets to update.
                    withContext(Dispatchers.Main) {
                        currentSentenceIndex = item.sentenceIndex
                        _observableState.update {
                            it.copy(
                                currentSentenceRange = item.range,
                                charOffset = item.range.startCharInChapter,
                            )
                        }
                    }
                    val track = audioTrack ?: break
                    var written = 0
                    while (written < item.pcm.size) {
                        val n = track.write(item.pcm, written, item.pcm.size - written)
                        if (n < 0) break // error code from AudioTrack
                        written += n
                    }
                }
                // All sentences consumed — chapter ended.
                withContext(Dispatchers.Main) {
                    sleepTimer.signalChapterEnd()
                    handleChapterDone()
                }
            } catch (_: Throwable) {
                // Cancelled.
            }
        }
    }

    private fun stopPlaybackPipeline() {
        generationJob?.cancel()
        generationJob = null
        playbackJob?.cancel()
        playbackJob = null
        pcmQueue?.close()
        pcmQueue = null
        audioTrack?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        audioTrack = null
    }

    /** Build an AudioTrack with a fat buffer so we can pre-feed enough audio
     *  to keep playback continuous while the next sentence is being generated. */
    private fun createAudioTrack(sampleRate: Int): AudioTrack {
        val channelMask = AndroidAudioFormat.CHANNEL_OUT_MONO
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        // Aim for ~2 seconds at this rate; clamp to at least 4× the system
        // minimum so AudioFlinger doesn't reject us.
        val target = sampleRate * 2 * 2 // 2 channels-worth, but mono so this is generous
        val bufferSize = maxOf(target, minBuf * 4)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AndroidAudioAttributes.Builder()
                    .setUsage(AndroidAudioAttributes.USAGE_MEDIA)
                    .setContentType(AndroidAudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AndroidAudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(encoding)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun pauseTts() {
        _observableState.update { it.copy(isPlaying = false) }
        stopPlaybackPipeline()
        scope.launch { persistPosition() }
        invalidateState()
    }

    fun resume() {
        if (sentences.isEmpty()) return
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
        _observableState.update { it.copy(charOffset = sentences[target].startChar) }
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

    fun setTtsVolume(@Suppress("UNUSED_PARAMETER") v: Float) {
        // TODO: route through AudioTrack.setVolume; defer until EnginePlayer
        // is the default path.
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

    private companion object {
        /** Buffered slots ahead of the playback consumer. Each slot holds one
         *  sentence's PCM (~50–500KB). At 4 slots × ~250KB avg = ~1MB peak —
         *  fine on Android. Higher capacity gives more pre-render headroom but
         *  costs memory and slows seek-flush. */
        const val QUEUE_CAPACITY = 4

        /** Fallback when the engine reports a non-positive sample rate (model
         *  not loaded yet). Piper voices are 22050Hz; Kokoro is 24000Hz. */
        const val DEFAULT_SAMPLE_RATE = 22050
    }
}
