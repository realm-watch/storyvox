package `in`.jphe.storyvox.playback.tts

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
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
import `in`.jphe.storyvox.playback.SleepTimer
import com.google.common.collect.ImmutableList
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

/**
 * A Media3 [Player] backed by [TextToSpeech]. The framework treats this exactly like
 * a normal media player — Auto, lock screen, Bluetooth, and Wear all talk to it
 * through the bound [androidx.media3.session.MediaSession].
 *
 * The `SimpleBasePlayer` API only requires us to maintain a [SimpleBasePlayer.State]
 * and respond to handle* methods — the framework handles listener fan-out, command
 * masking, and threading.
 *
 * For Hypnos's v1 scaffold we wire the lifecycle and TTS bridge correctly; the
 * detailed `State` mapping (item list, position, duration) is filled out in v1.1
 * once Selene's repos and Aurora's screens are in place.
 */
@UnstableApi
class TtsPlayer @AssistedInject constructor(
    @Assisted private val context: Context,
    private val engine: VoxSherpaTtsEngine,
    private val chunker: SentenceChunker,
    private val chapterRepo: ChapterRepository,
    private val positionRepo: PlaybackPositionRepository,
    private val sleepTimer: SleepTimer,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    @AssistedFactory
    interface Factory {
        fun create(context: Context): TtsPlayer
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tts: TextToSpeech? = null
    private var initJob: Job? = null
    /** Held as a field so we can re-attach it after a TTS engine rebuild. */
    private var tracker: SentenceTracker? = null

    private var sentences: List<Sentence> = emptyList()
    private var currentSentenceIndex: Int = 0
    private var currentSpeed: Float = 1.0f
    private var currentPitch: Float = 1.0f
    private var currentVolume: Float = 1.0f
    private var currentVoiceId: String? = null

    private val _observableState = MutableStateFlow(PlaybackState())
    val observableState: StateFlow<PlaybackState> = _observableState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<PlaybackUiEvent>(extraBufferCapacity = 4)
    val uiEvents: SharedFlow<PlaybackUiEvent> = _uiEvents.asSharedFlow()

    init {
        initJob = scope.launch {
            tts = engine.initialize()
            if (tts == null) {
                _uiEvents.tryEmit(PlaybackUiEvent.EngineMissing(engine.installUrl))
                _observableState.update { it.copy(error = PlaybackError.EngineUnavailable) }
            }
        }
    }

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

    // ----- Storyvox-internal API used by the controller -----

    suspend fun loadAndPlay(fictionId: String, chapterId: String, charOffset: Int) {
        initJob?.join()
        val tts = this.tts ?: return
        val chapter: PlaybackChapter = chapterRepo.getChapter(chapterId) ?: run {
            // Per Selene: null = either the row is missing, or the chapter body
            // hasn't been downloaded yet. Aurora's reader screen typically warms
            // the cache before we get here, but if a user hits play from Auto on
            // a not-yet-downloaded chapter we surface a clear error.
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
        val text = chapter.text
        sentences = chunker.chunk(text)

        currentSentenceIndex = sentences.indexOfFirst { charOffset <= it.endChar }
            .takeIf { it >= 0 } ?: 0

        val tracker = SentenceTracker(
            sentences = sentences,
            onSentence = { range ->
                _observableState.update {
                    it.copy(currentSentenceRange = range, charOffset = range.startCharInChapter)
                }
                // (per-sentence persistPosition removed — was triggering
                // Compose recomposition + Room I/O on every sentence which
                // appeared to add audible delay between utterances on lower-
                // end hardware. Position still persists on pause and on
                // releaseTts; we'll add a coarser timer-based save later.)
            },
            onChapterDone = {
                sleepTimer.signalChapterEnd()
                scope.launch { handleChapterDone() }
            },
            onErrorEmitted = { uid, code ->
                _observableState.update { it.copy(error = PlaybackError.TtsSpeakFailed(uid, code)) }
            },
            parseIndex = chunker::parseSentenceIndex,
            onSpeedRunnerDetected = {
                // VoxSherpa silent-speed-run recovery. Stop the queue,
                // surface the error in state, and pause. The engine is
                // wedged at this point — `resume()` will rebuild it from
                // scratch before re-queueing.
                //
                // This callback fires on the TTS binder thread; invalidateState()
                // must run on the main thread (SimpleBasePlayer enforces this),
                // so we hop via scope which is Dispatchers.Main. Without the
                // hop, the IllegalStateException it throws propagates back as
                // a Binder transport error and kills the speak queue mid-stream
                // — which is exactly the "audio dies after a moment" symptom.
                tts?.stop()
                _observableState.update {
                    it.copy(
                        isPlaying = false,
                        error = PlaybackError.TtsSpeakFailed(
                            utteranceId = "speed-runner",
                            errorCode = -2,
                        ),
                    )
                }
                scope.launch { invalidateState() }
            },
        )
        this.tracker = tracker
        tts.setOnUtteranceProgressListener(tracker)
        tts.setSpeechRate(currentSpeed)
        tts.setPitch(currentPitch)

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
        speakFromIndex(currentSentenceIndex)
        invalidateState()
    }

    private fun speakFromIndex(startIndex: Int) {
        val tts = this.tts ?: return
        tts.stop()
        for (i in startIndex until sentences.size) {
            val s = sentences[i]
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, currentVolume)
            }
            val mode = if (i == startIndex) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(s.text, mode, params, chunker.utteranceId(s.index))
        }
    }

    fun pauseTts() {
        tts?.stop()
        _observableState.update { it.copy(isPlaying = false) }
        scope.launch { persistPosition() }
        invalidateState()
    }

    fun resume() {
        if (sentences.isEmpty()) return
        val priorError = _observableState.value.error
        val wasDryRun = priorError is PlaybackError.TtsSpeakFailed &&
            priorError.utteranceId == "speed-runner"
        _observableState.update { it.copy(isPlaying = true, error = null) }
        if (wasDryRun) {
            // VoxSherpa was wedged — onStart/onDone fired without audio. Just
            // calling speak() again hits the same wedged AudioTrack. Shut down
            // the TextToSpeech instance and bind a fresh one before queueing.
            // Re-check isPlaying after the (suspending) rebuild — if the user
            // tapped pause during the ~1s init we shouldn't speak afterwards.
            scope.launch {
                rebuildTtsEngine()
                if (_observableState.value.isPlaying) {
                    speakFromIndex(currentSentenceIndex)
                }
                invalidateState()
            }
        } else {
            speakFromIndex(currentSentenceIndex)
            invalidateState()
        }
    }

    /**
     * Tear down the current [TextToSpeech] instance and bind a fresh one. Re-attaches
     * the existing [SentenceTracker] and re-applies speed/pitch/voice so the engine
     * is in the same logical state, just with a fresh AudioTrack underneath.
     */
    private suspend fun rebuildTtsEngine() {
        tts?.shutdown()
        tts = null
        val fresh = engine.initialize() ?: run {
            _uiEvents.tryEmit(PlaybackUiEvent.EngineMissing(engine.installUrl))
            _observableState.update { it.copy(error = PlaybackError.EngineUnavailable) }
            return
        }
        tts = fresh
        tracker?.let { fresh.setOnUtteranceProgressListener(it) }
        fresh.setSpeechRate(currentSpeed)
        fresh.setPitch(currentPitch)
        currentVoiceId?.let { id ->
            fresh.voices?.firstOrNull { it.name == id }?.let { fresh.voice = it }
        }
    }

    fun seekToCharOffset(offset: Int) {
        if (sentences.isEmpty()) return
        val clamped = offset.coerceAtLeast(0)
        val target = sentences.indexOfLast { it.startChar <= clamped }
            .takeIf { it >= 0 } ?: 0
        currentSentenceIndex = target
        _observableState.update {
            it.copy(charOffset = sentences[target].startChar)
        }
        if (_observableState.value.isPlaying) speakFromIndex(target)
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
        // The next chapter's body may not be cached yet — queue a download and
        // wait for the row to materialize before we hand it to loadAndPlay.
        // Without this, auto-advance hits the loadAndPlay null-body branch and
        // pauses with ChapterFetchFailed instead of continuing playback.
        chapterRepo.queueChapterDownload(fiction, nextId, requireUnmetered = false)
        chapterRepo.observeChapter(nextId)
            .filterNotNull()
            .first()
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
        tts?.setSpeechRate(speed)
        _observableState.update { it.copy(speed = speed) }
        invalidateState()
    }

    fun setPitch(pitch: Float) {
        currentPitch = pitch
        tts?.setPitch(pitch)
        _observableState.update { it.copy(pitch = pitch) }
        invalidateState()
    }

    fun setVoice(voiceId: String) {
        currentVoiceId = voiceId
        tts?.voices?.firstOrNull { it.name == voiceId }?.let { tts?.voice = it }
        _observableState.update { it.copy(voiceId = voiceId) }
    }

    fun setTtsVolume(v: Float) {
        currentVolume = v.coerceIn(0f, 1f)
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
        tts?.stop()
        _observableState.update {
            it.copy(isPlaying = false, currentSentenceRange = null)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        setSpeed(playbackParameters.speed)
        setPitch(playbackParameters.pitch)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        releaseTts()
        return Futures.immediateVoidFuture()
    }

    fun releaseTts() {
        // Final position save before tearing the scope down. Use runBlocking
        // because scope.cancel() below would cancel any launched persist call;
        // the write itself goes through Room (Dispatchers.IO inside the dao)
        // so the main-thread block is just the await.
        kotlinx.coroutines.runBlocking { persistPosition() }
        tts?.shutdown()
        tts = null
        scope.cancel()
    }

}
