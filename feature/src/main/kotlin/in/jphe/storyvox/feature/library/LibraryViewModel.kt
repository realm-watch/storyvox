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
import `in`.jphe.storyvox.feature.api.UiAddByUrlResult
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** State for the paste-URL bottom sheet. */
@Immutable
sealed interface AddByUrlSheetState {
    /** Sheet hidden. */
    data object Hidden : AddByUrlSheetState

    /** Sheet shown, accepting input. [error] non-null after a failed submission. */
    data class Open(val error: String? = null) : AddByUrlSheetState

    /** Submission in flight (network call to the source). */
    data object Submitting : AddByUrlSheetState
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

    private val _addByUrlState = MutableStateFlow<AddByUrlSheetState>(AddByUrlSheetState.Hidden)
    val addByUrlState: StateFlow<AddByUrlSheetState> = _addByUrlState.asStateFlow()

    val uiState: StateFlow<LibraryUiState> = combine(
        fictionRepo.observeLibrary(),
        positionRepo.observeMostRecentContinueListening(),
    ) { library, resume ->
        LibraryUiState(
            resume = resume,
            fictions = library,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun openFiction(id: String) {
        viewModelScope.launch { _events.send(LibraryUiEvent.OpenFiction(id)) }
    }

    fun resume() {
        val entry = uiState.value.resume ?: return
        // Cold-start: the controller may have nothing loaded (fresh app launch),
        // in which case `play()` is a no-op. `startListening` queues the chapter
        // download and kicks the TTS engine, which is what we actually want.
        playback.startListening(entry.fiction.id, entry.chapter.id, entry.charOffset)
        viewModelScope.launch {
            _events.send(LibraryUiEvent.OpenReader(entry.fiction.id, entry.chapter.id))
        }
    }

    fun setDownloadMode(fictionId: String, mode: DownloadMode) {
        viewModelScope.launch { uiRepo.setDownloadMode(fictionId, mode) }
    }

    fun showAddByUrl() {
        _addByUrlState.value = AddByUrlSheetState.Open()
    }

    fun dismissAddByUrl() {
        _addByUrlState.value = AddByUrlSheetState.Hidden
    }

    fun submitAddByUrl(url: String) {
        if (_addByUrlState.value === AddByUrlSheetState.Submitting) return
        _addByUrlState.value = AddByUrlSheetState.Submitting
        viewModelScope.launch {
            when (val result = uiRepo.addByUrl(url)) {
                is UiAddByUrlResult.Success -> {
                    _addByUrlState.value = AddByUrlSheetState.Hidden
                    _events.send(LibraryUiEvent.OpenFiction(result.fictionId))
                }
                UiAddByUrlResult.UnrecognizedUrl -> {
                    _addByUrlState.value = AddByUrlSheetState.Open(
                        error = "That URL doesn't look like a Royal Road or GitHub address.",
                    )
                }
                is UiAddByUrlResult.UnsupportedSource -> {
                    _addByUrlState.value = AddByUrlSheetState.Open(
                        error = "${result.sourceId.replaceFirstChar { it.uppercase() }} support is coming soon.",
                    )
                }
                is UiAddByUrlResult.Error -> {
                    _addByUrlState.value = AddByUrlSheetState.Open(
                        error = result.message.ifBlank { "Could not load that fiction. Try again." },
                    )
                }
            }
        }
    }
}
