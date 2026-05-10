package `in`.jphe.storyvox.playback

import `in`.jphe.storyvox.playback.tts.EnginePlayer
import `in`.jphe.storyvox.playback.tts.RecapPlaybackState
import `in`.jphe.storyvox.playback.tts.SentenceChunker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

interface PlaybackController {
    val state: StateFlow<PlaybackState>
    val events: SharedFlow<PlaybackUiEvent>

    /** Issue #189 — recap-aloud TTS pipeline state. Idle until [speakText]
     *  is called; flips to Speaking while the one-shot utterance is playing,
     *  back to Idle when it finishes naturally or [stopSpeaking] is called. */
    val recapPlayback: StateFlow<RecapPlaybackState>

    suspend fun play(fictionId: String, chapterId: String, charOffset: Int = 0)
    fun pause()
    fun resume()
    fun togglePlayPause()
    fun seekTo(charOffset: Int)
    fun skipForward30s()
    fun skipBack30s()
    suspend fun nextChapter()
    suspend fun previousChapter()
    suspend fun jumpToChapter(chapterId: String)

    fun setSpeed(speed: Float)
    fun setPitch(pitch: Float)
    /** Issue #90 — see [in.jphe.storyvox.playback.tts.EnginePlayer.setPunctuationPauseMultiplier]. */
    fun setPunctuationPauseMultiplier(multiplier: Float)

    fun startSleepTimer(mode: SleepTimerMode)
    fun cancelSleepTimer()
    fun toggleSleepTimer()

    /** Issue #150 — push the user's shake-to-extend setting into the
     *  state stream so [StoryvoxPlaybackService] can gate sensor
     *  registration on it without taking a feature-module dep. */
    fun setShakeToExtendEnabled(enabled: Boolean)

    /** Issue #189 — synthesize and play [text] as a one-shot utterance via
     *  the active voice. Used by the chapter-recap modal to read the
     *  AI-generated recap aloud. The caller is responsible for pausing
     *  active fiction playback before calling — the engine is shared and
     *  overlapping audio would muddy the listener experience. */
    suspend fun speakText(text: String)

    /** Issue #189 — cancel an in-flight recap-aloud utterance. Idempotent. */
    fun stopSpeaking()
}

/**
 * The single source of truth for playback. Wires together:
 * - the [EnginePlayer] (which actually speaks — VoxSherpa engine path),
 * - the chapter repository (to fetch text and resolve next/prev),
 * - the position repository (to persist resume points),
 * - the sleep timer.
 *
 * Service-bound: lives in [SingletonComponent] so all callers share state.
 * The [EnginePlayer] is created and lifecycle-managed by [StoryvoxPlaybackService] —
 * the service injects this controller and calls [bindPlayer] / [unbindPlayer]
 * during its lifecycle. Until a player is bound, transport calls are no-ops.
 */
@Singleton
class DefaultPlaybackController @Inject constructor(
    private val chunker: SentenceChunker,
    private val sleepTimer: SleepTimer,
) : PlaybackController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlaybackUiEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<PlaybackUiEvent> = _events.asSharedFlow()

    private var player: EnginePlayer? = null

    /** Issue #189 — mirror of the bound player's recap-aloud state. Idle
     *  before any player binds (and between bindings); reflects the
     *  active player's flow while bound. */
    private val _recapPlayback = MutableStateFlow(RecapPlaybackState.Idle)
    override val recapPlayback: StateFlow<RecapPlaybackState> = _recapPlayback.asStateFlow()

    init {
        scope.launch {
            sleepTimer.remainingMs.collect { remaining ->
                _state.value = _state.value.copy(sleepTimerRemainingMs = remaining)
            }
        }
    }

    fun bindPlayer(p: EnginePlayer) {
        player = p
        scope.launch {
            p.observableState.collect { update ->
                _state.value = update.copy(sleepTimerRemainingMs = sleepTimer.remainingMs.value)
            }
        }
        scope.launch {
            p.recapPlayback.collect { _recapPlayback.value = it }
        }
    }

    fun unbindPlayer() {
        player = null
        _recapPlayback.value = RecapPlaybackState.Idle
    }

    override suspend fun play(fictionId: String, chapterId: String, charOffset: Int) {
        player?.loadAndPlay(fictionId, chapterId, charOffset)
    }

    override fun pause() { player?.pauseTts() }
    override fun resume() { player?.resume() }
    override fun togglePlayPause() {
        if (state.value.isPlaying) pause() else resume()
    }

    override fun seekTo(charOffset: Int) { player?.seekToCharOffset(charOffset) }

    override fun skipForward30s() {
        val s = state.value
        val charsPerSec = SPEED_BASELINE_CHARS_PER_SECOND * s.speed
        player?.seekToCharOffset(s.charOffset + (charsPerSec * 30).toInt())
    }

    override fun skipBack30s() {
        val s = state.value
        val charsPerSec = SPEED_BASELINE_CHARS_PER_SECOND * s.speed
        player?.seekToCharOffset((s.charOffset - (charsPerSec * 30).toInt()).coerceAtLeast(0))
    }

    override suspend fun nextChapter() { player?.advanceChapter(direction = 1) }
    override suspend fun previousChapter() { player?.advanceChapter(direction = -1) }
    override suspend fun jumpToChapter(chapterId: String) {
        val fictionId = state.value.currentFictionId ?: return
        play(fictionId, chapterId, charOffset = 0)
    }

    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        player?.setSpeed(clamped)
    }

    override fun setPitch(pitch: Float) {
        val clamped = pitch.coerceIn(0.5f, 2.0f)
        player?.setPitch(clamped)
    }

    override fun setPunctuationPauseMultiplier(multiplier: Float) {
        player?.setPunctuationPauseMultiplier(multiplier)
    }

    override fun startSleepTimer(mode: SleepTimerMode) {
        sleepTimer.start(mode)
    }

    override fun cancelSleepTimer() {
        sleepTimer.cancel()
    }

    override fun toggleSleepTimer() {
        if (state.value.sleepTimerRemainingMs != null) cancelSleepTimer()
        else startSleepTimer(SleepTimerMode.Duration(15))
    }

    override fun setShakeToExtendEnabled(enabled: Boolean) {
        if (_state.value.shakeToExtendEnabled == enabled) return
        _state.value = _state.value.copy(shakeToExtendEnabled = enabled)
    }

    override suspend fun speakText(text: String) {
        player?.speak(text)
    }

    override fun stopSpeaking() {
        player?.stopSpeaking()
    }

    internal fun emitEvent(event: PlaybackUiEvent) {
        _events.tryEmit(event)
    }
}
