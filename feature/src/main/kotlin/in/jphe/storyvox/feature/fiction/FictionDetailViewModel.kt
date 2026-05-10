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
    /** Set when the first-subscription `refreshDetail` failed and we have
     *  no cached row to show. Cleared on the next successful refresh.
     *  When [fiction] is non-null this is a tail/refresh error — the
     *  screen should keep showing the cached data and surface the error
     *  as a snackbar / banner rather than blocking the page. */
    val error: String? = null,
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
        repo.fictionLoadError(fictionId),
    ) { fiction, chapters, library, error ->
        FictionDetailUiState(
            fiction = fiction,
            chapters = chapters,
            isInLibrary = library.any { it.id == fictionId },
            // Stop showing the spinner once we either have a cached row
            // OR the refresh has failed — otherwise a Cloudflare/network
            // error leaves the user on a permanent spinner with no signal.
            isLoading = fiction == null && error == null,
            error = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FictionDetailUiState())

    fun toggleFollow(follow: Boolean) {
        viewModelScope.launch { repo.follow(fictionId, follow) }
    }

    fun setMode(mode: DownloadMode) {
        viewModelScope.launch { repo.setDownloadMode(fictionId, mode) }
    }

    fun listen(chapterId: String) {
        // Issue #288 — tapping Listen is a stronger intent than tapping
        // 'Add to library'. The user is committing to listen RIGHT NOW.
        // Silently follow the fiction if it isn't already in their
        // library so it survives app restart and the Resume card can
        // surface it on Library. Mirror the gesture-only-add pattern
        // (no confirm dialog) — remove-from-library still requires the
        // explicit AlertDialog from issue #169.
        if (!uiState.value.isInLibrary) {
            viewModelScope.launch { repo.follow(fictionId, true) }
        }
        playback.startListening(fictionId, chapterId)
        viewModelScope.launch { _events.send(FictionDetailUiEvent.OpenReader(fictionId, chapterId)) }
    }
}
