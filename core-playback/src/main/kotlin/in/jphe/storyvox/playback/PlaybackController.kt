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
    /** #120 — step to the next sentence boundary. No-op when already
     *  on the last sentence of the chapter. */
    fun nextSentence()
    /** #120 — step to the previous sentence boundary. No-op when
     *  already on sentence 0. */
    fun previousSentence()
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

    /**
     * Issue #290 — point-in-time snapshot of the active player's
     * producer queue + AudioTrack buffer state. Returns zeros when no
     * player is bound. Read by the Debug overlay at 1Hz.
     */
    fun bufferTelemetry(): BufferTelemetry

    /**
     * Issue #121 — bookmark the current playback position into the
     * currently-loaded chapter. No-op when no chapter is loaded. The
     * bookmark is persisted to the chapter row so it survives app
     * restarts and the playhead moving past it.
     */
    suspend fun bookmarkHere()

    /**
     * Issue #121 — clear the currently-loaded chapter's bookmark, if
     * any. No-op when no chapter is loaded or no bookmark exists.
     */
    suspend fun clearBookmark()

    /**
     * Issue #121 — seek to the currently-loaded chapter's bookmark. No-op
     * when no chapter is loaded or no bookmark exists. Returns true if
     * the seek fired, false otherwise — callers can use this to surface
     * "no bookmark to jump to" feedback.
     */
    suspend fun jumpToBookmark(): Boolean
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
    /** #90 — write the user's last play/pause intent so the
     *  Library Resume CTA can decide whether to auto-start. */
    private val resumePolicy: `in`.jphe.storyvox.data.repository.playback.PlaybackResumePolicyConfig,
    /** #121 — per-chapter bookmark read/write. Controller persists +
     *  jumps to bookmark positions; the player layer needs no
     *  awareness of bookmarks (they're a chapter-metadata concern). */
    private val chapterRepo: `in`.jphe.storyvox.data.repository.ChapterRepository,
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
        // Calliope (v0.5.00) — bridge the player's internal uiEvents
        // SharedFlow into the controller's public `events` flow. Pre-
        // Calliope this bridge was missing entirely: BookFinished,
        // ChapterChanged, EngineMissing, and AzureFellBack fired
        // inside EnginePlayer but never reached any external observer
        // because nothing collected `p.uiEvents`. The :app debug
        // surface read `controller.events` directly and silently
        // received nothing — only this commit's confetti
        // requirement caught it. New event consumers should rely on
        // this bridge being live for the lifetime of a player binding.
        scope.launch {
            p.uiEvents.collect { _events.tryEmit(it) }
        }
    }

    fun unbindPlayer() {
        player = null
        _recapPlayback.value = RecapPlaybackState.Idle
    }

    override suspend fun play(fictionId: String, chapterId: String, charOffset: Int) {
        // #90 — record the play intent BEFORE loadAndPlay (which can take
        // 30+s to return on a cold engine load). If the user kills the
        // app mid-load we want resume-on-reopen to autoplay.
        scope.launch { runCatching { resumePolicy.setLastWasPlaying(true) } }
        player?.loadAndPlay(fictionId, chapterId, charOffset)
    }

    override fun pause() {
        // #90 — explicit pause is a user intent. Persist so the next
        // Library Resume CTA respects it (load chapter, stay paused).
        scope.launch { runCatching { resumePolicy.setLastWasPlaying(false) } }
        player?.pauseTts()
    }

    override fun resume() {
        scope.launch { runCatching { resumePolicy.setLastWasPlaying(true) } }
        player?.resume()
    }

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

    override fun nextSentence() { player?.seekSentence(direction = 1) }
    override fun previousSentence() { player?.seekSentence(direction = -1) }

    override suspend fun nextChapter() { player?.advanceChapter(direction = 1) }
    override suspend fun previousChapter() {
        // #285 — standard media-player UX. Pressing Previous past a few
        // seconds into the current chapter rewinds to its start; only
        // pressing Previous *while near the start* goes to the previous
        // chapter. Without this, a stray tap during chapter 2 silently
        // dumps the user back to chapter 1, with no confirm, no
        // animation, and lost reading position. Apple Music, Spotify,
        // Pocket Casts, and every major player work this way — users
        // expect it.
        val s = state.value
        val charsPerSec = SPEED_BASELINE_CHARS_PER_SECOND * s.speed
        val rewindThresholdChars = (charsPerSec * REWIND_TO_START_THRESHOLD_SEC).toInt()
        if (s.charOffset > rewindThresholdChars) {
            player?.seekToCharOffset(0)
        } else {
            player?.advanceChapter(direction = -1)
        }
    }
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

    override fun bufferTelemetry(): BufferTelemetry =
        player?.bufferTelemetry() ?: BufferTelemetry()

    override suspend fun bookmarkHere() {
        val chapterId = state.value.currentChapterId ?: return
        val offset = state.value.charOffset
        chapterRepo.setChapterBookmark(chapterId, offset)
    }

    override suspend fun clearBookmark() {
        val chapterId = state.value.currentChapterId ?: return
        chapterRepo.setChapterBookmark(chapterId, null)
    }

    override suspend fun jumpToBookmark(): Boolean {
        val chapterId = state.value.currentChapterId ?: return false
        val offset = chapterRepo.chapterBookmark(chapterId) ?: return false
        player?.seekToCharOffset(offset)
        return true
    }

    internal fun emitEvent(event: PlaybackUiEvent) {
        _events.tryEmit(event)
    }

    companion object {
        /** Issue #285 — seconds-from-start threshold for the SkipPrevious
         *  rewind-to-start UX. Past this point in a chapter, tapping
         *  SkipPrevious seeks to char 0 of the current chapter; under it
         *  goes to the previous chapter. 3 seconds is the de-facto
         *  standard across Apple Music, Spotify, Pocket Casts, and the
         *  Android MediaSession default behavior. */
        internal const val REWIND_TO_START_THRESHOLD_SEC = 3f
    }
}
