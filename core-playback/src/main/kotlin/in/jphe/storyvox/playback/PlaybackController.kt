package `in`.jphe.storyvox.playback

import `in`.jphe.storyvox.playback.tts.SentenceChunker
import `in`.jphe.storyvox.playback.tts.TtsPlayer
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
    fun setVoice(voiceId: String)

    fun startSleepTimer(mode: SleepTimerMode)
    fun cancelSleepTimer()
    fun toggleSleepTimer()
}

/**
 * The single source of truth for playback. Wires together:
 * - the [TtsPlayer] (which actually speaks),
 * - the chapter repository (to fetch text and resolve next/prev),
 * - the position repository (to persist resume points),
 * - the sleep timer.
 *
 * Service-bound: lives in [SingletonComponent] so all callers share state.
 * The [TtsPlayer] is created and lifecycle-managed by [StoryvoxPlaybackService] —
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

    private var player: TtsPlayer? = null

    fun bindPlayer(p: TtsPlayer) {
        player = p
        scope.launch { p.observableState.collect { update -> _state.value = update } }
    }

    fun unbindPlayer() {
        player = null
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

    override fun setVoice(voiceId: String) {
        player?.setVoice(voiceId)
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

    internal fun emitEvent(event: PlaybackUiEvent) {
        _events.tryEmit(event)
    }
}
