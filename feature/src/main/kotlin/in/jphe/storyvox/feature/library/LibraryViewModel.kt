package `in`.jphe.storyvox.feature.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.repository.ContinueListeningEntry
import `in`.jphe.storyvox.data.repository.FictionRepository
import `in`.jphe.storyvox.data.repository.PlaybackPositionRepository
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.feature.api.DownloadMode
import `in`.jphe.storyvox.feature.api.FictionRepositoryUi
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class LibraryUiState(
    /** Topmost continue-listening entry — null until the user has played anything. */
    val resume: ContinueListeningEntry? = null,
    val fictions: List<FictionSummary> = emptyList(),
    val isLoading: Boolean = true,
)

sealed interface LibraryUiEvent {
    data class OpenFiction(val fictionId: String) : LibraryUiEvent
    data class OpenReader(val fictionId: String, val chapterId: String) : LibraryUiEvent
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    fictionRepo: FictionRepository,
    positionRepo: PlaybackPositionRepository,
    private val uiRepo: FictionRepositoryUi,
    private val playback: PlaybackControllerUi,
) : ViewModel() {

    private val _events = Channel<LibraryUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val uiState: StateFlow<LibraryUiState> = combine(
        fictionRepo.observeLibrary(),
        positionRepo.observeContinueListening(),
    ) { library, recents ->
        LibraryUiState(
            resume = recents.firstOrNull(),
            fictions = library,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun openFiction(id: String) {
        viewModelScope.launch { _events.send(LibraryUiEvent.OpenFiction(id)) }
    }

    fun resume() {
        val entry = uiState.value.resume ?: return
        playback.play()
        viewModelScope.launch {
            _events.send(LibraryUiEvent.OpenReader(entry.fiction.id, entry.chapter.id))
        }
    }

    fun setDownloadMode(fictionId: String, mode: DownloadMode) {
        viewModelScope.launch { uiRepo.setDownloadMode(fictionId, mode) }
    }
}
