package `in`.jphe.storyvox.feature.fiction

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.feature.api.DownloadMode
import `in`.jphe.storyvox.feature.api.FictionRepositoryUi
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.UiChapter
import `in`.jphe.storyvox.feature.api.UiFiction
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class FictionDetailUiState(
    val fiction: UiFiction? = null,
    val chapters: List<UiChapter> = emptyList(),
    val isInLibrary: Boolean = false,
    val downloadMode: DownloadMode = DownloadMode.Lazy,
    val isLoading: Boolean = true,
)

sealed interface FictionDetailUiEvent {
    data class OpenReader(val fictionId: String, val chapterId: String) : FictionDetailUiEvent
}

@HiltViewModel
class FictionDetailViewModel @Inject constructor(
    private val repo: FictionRepositoryUi,
    private val playback: PlaybackControllerUi,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val fictionId: String = checkNotNull(savedState["fictionId"]) {
        "FictionDetailScreen requires a `fictionId` nav arg"
    }

    private val _events = Channel<FictionDetailUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val uiState: StateFlow<FictionDetailUiState> = combine(
        repo.fictionById(fictionId),
        repo.chaptersFor(fictionId),
        repo.library,
    ) { fiction, chapters, library ->
        FictionDetailUiState(
            fiction = fiction,
            chapters = chapters,
            isInLibrary = library.any { it.id == fictionId },
            isLoading = fiction == null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FictionDetailUiState())

    fun toggleFollow(follow: Boolean) {
        viewModelScope.launch { repo.follow(fictionId, follow) }
    }

    fun setMode(mode: DownloadMode) {
        viewModelScope.launch { repo.setDownloadMode(fictionId, mode) }
    }

    fun listen(chapterId: String) {
        playback.startListening(fictionId, chapterId)
        viewModelScope.launch { _events.send(FictionDetailUiEvent.OpenReader(fictionId, chapterId)) }
    }
}
