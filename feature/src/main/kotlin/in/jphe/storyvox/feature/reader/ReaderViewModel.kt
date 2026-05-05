package `in`.jphe.storyvox.feature.reader

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.UiPlaybackState
import `in`.jphe.storyvox.feature.api.VoiceProviderUi
import `in`.jphe.storyvox.ui.component.ReaderView
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@Immutable
data class ReaderUiState(
    val playback: UiPlaybackState? = null,
    val chapterText: String = "",
    val activePane: ReaderView = ReaderView.Audiobook,
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val playback: PlaybackControllerUi,
    private val voices: VoiceProviderUi,
    @Suppress("UnusedPrivateProperty") savedState: SavedStateHandle,
) : ViewModel() {

    private val _activePane = MutableStateFlow(ReaderView.Audiobook)

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
    fun skipForward() = playback.skipForward()
    fun skipBack() = playback.skipBack()
    fun nextChapter() = playback.nextChapter()
    fun previousChapter() = playback.previousChapter()
    fun setSpeed(speed: Float) = playback.setSpeed(speed)
    fun setVoice(voiceId: String) = playback.setVoice(voiceId)
}
