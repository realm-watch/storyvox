package `in`.jphe.storyvox.feature.follows

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.feature.api.FictionRepositoryUi
import `in`.jphe.storyvox.feature.api.UiFollow
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class FollowsUiState(
    val follows: List<UiFollow> = emptyList(),
    val isLoading: Boolean = true,
)

sealed interface FollowsUiEvent {
    data class OpenFiction(val fictionId: String) : FollowsUiEvent
    data object CaughtUp : FollowsUiEvent
}

@HiltViewModel
class FollowsViewModel @Inject constructor(
    private val repo: FictionRepositoryUi,
) : ViewModel() {

    private val _events = Channel<FollowsUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // Lazy refresh on tab visit — pulls the user's RR follows list into
        // the local DB. Silent on failure (unauthed users keep an empty tab).
        viewModelScope.launch { repo.refreshFollows() }
    }

    val uiState: StateFlow<FollowsUiState> = repo.follows
        .map { FollowsUiState(follows = it, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FollowsUiState())

    fun open(fictionId: String) {
        viewModelScope.launch { _events.send(FollowsUiEvent.OpenFiction(fictionId)) }
    }

    fun markAllCaughtUp() {
        viewModelScope.launch {
            repo.markAllCaughtUp()
            _events.send(FollowsUiEvent.CaughtUp)
        }
    }
}
