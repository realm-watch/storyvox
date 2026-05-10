package `in`.jphe.storyvox.feature.reader

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.UiPlaybackState
import `in`.jphe.storyvox.feature.api.UiRecapPlaybackState
import `in`.jphe.storyvox.feature.api.UiSleepTimerMode
import `in`.jphe.storyvox.llm.LlmError
import `in`.jphe.storyvox.llm.feature.ChapterRecap
import `in`.jphe.storyvox.ui.component.ReaderView
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class ReaderUiState(
    val playback: UiPlaybackState? = null,
    val chapterText: String = "",
    val activePane: ReaderView = ReaderView.Audiobook,
)

/** UI state for the Chapter Recap modal. Sealed because the
 *  states are mutually exclusive — at any given moment the modal is
 *  either closed, asking, streaming, done, or in error. */
@Immutable
sealed class RecapUiState {
    /** Modal not visible. */
    object Hidden : RecapUiState()

    /** Modal opened, waiting for the first token from the LLM. */
    object Loading : RecapUiState()

    /** First token arrived; partial response builds up. */
    data class Streaming(val text: String) : RecapUiState()

    /** Stream completed successfully. */
    data class Done(val text: String) : RecapUiState()

    /** Stream failed. The UI shows [message] + the appropriate
     *  recovery action (Settings link, Try again, etc.). */
    data class Error(
        val message: String,
        val kind: ErrorKind,
    ) : RecapUiState()

    enum class ErrorKind {
        /** AI not configured, or "Send chapter text to AI" toggle off
         *  → route to Settings. */
        NotConfigured,
        /** Provider key invalid → route to Settings, flag the field. */
        AuthFailed,
        /** Network/transport — recoverable. */
        Transport,
        /** Provider returned a non-auth 4xx/5xx. */
        ProviderError,
    }
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val playback: PlaybackControllerUi,
    private val settings: SettingsRepositoryUi,
    private val chapterRecap: ChapterRecap,
    @Suppress("UnusedPrivateProperty") savedState: SavedStateHandle,
) : ViewModel() {

    private val _activePane = MutableStateFlow(ReaderView.Audiobook)
    private val _recap = MutableStateFlow<RecapUiState>(RecapUiState.Hidden)

    /** Recap modal state. Reader UI collects this and renders the
     *  modal when not [RecapUiState.Hidden]. */
    val recap: StateFlow<RecapUiState> = _recap.asStateFlow()

    /** Issue #189 — recap-aloud TTS pipeline state, surfaced from the
     *  PlaybackController so the modal's Read-aloud button can render the
     *  right play/pause icon. The chapter-recap modal collects this
     *  alongside [recap] (the modal-content state) — they're independent
     *  axes: the modal can be Done while the audio is Idle (button shows
     *  Play), or Done while Speaking (button shows Pause). */
    val recapPlayback: StateFlow<UiRecapPlaybackState> = playback.recapPlayback
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiRecapPlaybackState.Idle)

    /** The currently in-flight recap stream, or null when no recap is
     *  running. Cancelling this Job cancels the underlying OkHttp
     *  Call (TCP RST to the provider; they stop generating; we stop
     *  billing). */
    private var recapJob: Job? = null

    val uiState: StateFlow<ReaderUiState> = combine(
        playback.state,
        playback.chapterText,
        _activePane,
    ) { state, text, pane ->
        ReaderUiState(playback = state, chapterText = text, activePane = pane)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderUiState())

    fun setActivePane(pane: ReaderView) { _activePane.value = pane }

    fun playPause() {
        val state = uiState.value.playback ?: return
        if (state.isPlaying) playback.pause() else playback.play()
    }

    fun seekTo(ms: Long) = playback.seekTo(ms)
    fun seekToChar(charOffset: Int) = playback.seekToChar(charOffset)
    fun skipForward() = playback.skipForward()
    fun skipBack() = playback.skipBack()
    fun nextChapter() = playback.nextChapter()
    fun previousChapter() = playback.previousChapter()
    fun nextSentence() = playback.nextSentence()
    fun previousSentence() = playback.previousSentence()

    fun setSpeed(speed: Float) {
        playback.setSpeed(speed)
    }

    fun persistSpeed(speed: Float) {
        viewModelScope.launch { settings.setDefaultSpeed(speed) }
    }

    fun setPitch(pitch: Float) {
        playback.setPitch(pitch)
    }

    fun persistPitch(pitch: Float) {
        viewModelScope.launch { settings.setDefaultPitch(pitch) }
    }

    fun startSleepTimer(mode: UiSleepTimerMode) = playback.startSleepTimer(mode)
    fun cancelSleepTimer() = playback.cancelSleepTimer()

    // ── Chapter Recap (issue #81) ──────────────────────────────────

    /** Open the modal and stream a recap for the current chapter.
     *  No-op if the playback state isn't ready (no fictionId/chapterId
     *  to recap). */
    fun requestRecap() {
        // Cancel any in-flight recap so we don't double-stream into
        // the buffer.
        recapJob?.cancel()
        val state = uiState.value.playback ?: return
        val fictionId = state.fictionId ?: return
        val chapterId = state.chapterId ?: return

        _recap.value = RecapUiState.Loading
        val buf = StringBuilder()
        recapJob = viewModelScope.launch {
            chapterRecap.recap(fictionId, chapterId)
                .catch { e ->
                    _recap.value = mapErrorToUi(e)
                }
                .onCompletion { cause ->
                    if (cause == null && _recap.value is RecapUiState.Streaming) {
                        _recap.value = RecapUiState.Done(buf.toString())
                    } else if (cause == null && _recap.value === RecapUiState.Loading) {
                        // Stream completed without emitting anything
                        // (e.g. no chapters in window).
                        _recap.value = RecapUiState.Done(buf.toString())
                    }
                    // Cancelled (cause == CancellationException) →
                    // leave whatever state we were in; the caller
                    // (cancelRecap) flips us to Hidden directly.
                }
                .collect { delta ->
                    buf.append(delta)
                    _recap.value = RecapUiState.Streaming(buf.toString())
                }
        }
    }

    /** Hide the modal. Cancels the in-flight stream — partial recap
     *  is discarded. Also stops any in-flight recap-aloud utterance
     *  (#189) so closing the modal silences the audio. */
    fun cancelRecap() {
        recapJob?.cancel()
        recapJob = null
        playback.stopSpeaking()
        _recap.value = RecapUiState.Hidden
    }

    /**
     * Issue #189 — toggle the recap-aloud TTS. Tapped from the Read-aloud
     * button in [RecapModal] when the recap is in [RecapUiState.Done].
     *
     * Behaviour:
     *  - If a recap utterance is already speaking, stop it. (Button
     *    rendered as a Pause icon — second tap silences.)
     *  - Otherwise, pause the active fiction (so the recap and the
     *    chapter audio don't overlap), then synthesize the recap text
     *    via the active voice. Per the spec we leave fiction paused
     *    when the recap finishes — auto-resume would feel aggressive.
     */
    fun toggleRecapAloud() {
        if (recapPlayback.value == UiRecapPlaybackState.Speaking) {
            playback.stopSpeaking()
            return
        }
        val text = (recap.value as? RecapUiState.Done)?.text ?: return
        if (text.isBlank()) return
        // Pause active fiction first — engine is shared, overlapping audio
        // would be muddy.
        if (uiState.value.playback?.isPlaying == true) playback.pause()
        viewModelScope.launch {
            playback.speakText(text)
        }
    }

    private fun mapErrorToUi(e: Throwable): RecapUiState.Error = when (e) {
        is LlmError.NotConfigured -> RecapUiState.Error(
            message = "Set up AI in Settings to use Recap.",
            kind = RecapUiState.ErrorKind.NotConfigured,
        )
        is LlmError.AuthFailed -> RecapUiState.Error(
            message = "${e.provider} key is invalid — check Settings.",
            kind = RecapUiState.ErrorKind.AuthFailed,
        )
        is LlmError.Transport -> RecapUiState.Error(
            message = "Couldn't reach the AI — check your connection and try again.",
            kind = RecapUiState.ErrorKind.Transport,
        )
        is LlmError.ProviderError -> RecapUiState.Error(
            message = "AI service error (${e.status}). Try again in a moment.",
            kind = RecapUiState.ErrorKind.ProviderError,
        )
        else -> RecapUiState.Error(
            message = e.message ?: "Recap failed.",
            kind = RecapUiState.ErrorKind.Transport,
        )
    }
}
