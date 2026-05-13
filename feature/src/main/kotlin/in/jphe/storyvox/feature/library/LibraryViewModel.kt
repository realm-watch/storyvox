package `in`.jphe.storyvox.feature.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.db.entity.Shelf
import `in`.jphe.storyvox.data.repository.ContinueListeningEntry
import `in`.jphe.storyvox.data.repository.FictionRepository
import `in`.jphe.storyvox.data.repository.PlaybackPositionRepository
import `in`.jphe.storyvox.data.repository.ShelfRepository
import `in`.jphe.storyvox.data.repository.playback.PlaybackResumePolicyConfig
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Issue #116 — which row of the chip-filter strip is selected. `All` is
 * the default (current behaviour pre-shelves); the three [Shelf] options
 * filter the grid to fictions that sit on that shelf.
 */
@Immutable
sealed interface ShelfFilter {
    data object All : ShelfFilter
    data class OneShelf(val shelf: Shelf) : ShelfFilter
}

@Immutable
data class LibraryUiState(
    /** Topmost continue-listening entry — null until the user has played anything. */
    val resume: ContinueListeningEntry? = null,
    val fictions: List<FictionSummary> = emptyList(),
    /** Currently-active chip — drives both the grid filter and which empty state shows. */
    val filter: ShelfFilter = ShelfFilter.All,
    val isLoading: Boolean = true,
)

/**
 * Long-press → manage-shelves bottom-sheet state. Tracks the fiction being
 * managed plus the live set of shelves it currently sits on (kept in sync
 * with the DB so toggling a switch updates immediately).
 */
@Immutable
sealed interface ManageShelvesSheetState {
    data object Hidden : ManageShelvesSheetState
    data class Open(
        val fictionId: String,
        val fictionTitle: String,
        val memberOf: Set<Shelf>,
    ) : ManageShelvesSheetState
}

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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryViewModel @Inject constructor(
    fictionRepo: FictionRepository,
    positionRepo: PlaybackPositionRepository,
    private val shelfRepo: ShelfRepository,
    private val uiRepo: FictionRepositoryUi,
    private val playback: PlaybackControllerUi,
    /** #90 — read the user's last play/pause intent to decide whether
     *  the Resume CTA should auto-start playback. */
    private val resumePolicy: PlaybackResumePolicyConfig,
) : ViewModel() {

    private val _events = Channel<LibraryUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _addByUrlState = MutableStateFlow<AddByUrlSheetState>(AddByUrlSheetState.Hidden)
    val addByUrlState: StateFlow<AddByUrlSheetState> = _addByUrlState.asStateFlow()

    private val _filter = MutableStateFlow<ShelfFilter>(ShelfFilter.All)

    private val _manageShelves = MutableStateFlow<ManageShelvesSheetState>(ManageShelvesSheetState.Hidden)
    val manageShelvesState: StateFlow<ManageShelvesSheetState> = _manageShelves.asStateFlow()

    /**
     * The fiction list flow swaps between the full library and a
     * shelf-scoped flow depending on the active filter. flatMapLatest
     * cancels the previous subscription so flipping chips doesn't pile
     * up Room flows.
     */
    private val fictionsFlow = _filter.flatMapLatest { f ->
        when (f) {
            ShelfFilter.All -> fictionRepo.observeLibrary()
            is ShelfFilter.OneShelf -> shelfRepo.observeByShelf(f.shelf)
        }
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        fictionsFlow,
        positionRepo.observeMostRecentContinueListening(),
        _filter,
    ) { library, resume, filter ->
        LibraryUiState(
            resume = resume,
            fictions = library,
            filter = filter,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    // ─── chips ────────────────────────────────────────────────────────────

    /** Top-of-screen chip row taps. */
    fun selectFilter(filter: ShelfFilter) {
        _filter.value = filter
    }

    // ─── manage-shelves bottom sheet ──────────────────────────────────────

    /** Long-press on a library card → open the manage-shelves sheet. */
    fun openManageShelves(fiction: FictionSummary) {
        viewModelScope.launch {
            val current = shelfRepo.shelvesForFiction(fiction.id)
            _manageShelves.value = ManageShelvesSheetState.Open(
                fictionId = fiction.id,
                fictionTitle = fiction.title,
                memberOf = current,
            )
        }
    }

    fun dismissManageShelves() {
        _manageShelves.value = ManageShelvesSheetState.Hidden
    }

    /** Flip a single shelf for the currently-managed fiction. */
    fun toggleShelf(fictionId: String, shelf: Shelf) {
        viewModelScope.launch {
            shelfRepo.toggle(fictionId, shelf)
            // Refresh the sheet's local cache of memberships so the toggle
            // visibly settles. Re-reading from the DB after the write is
            // the cheapest correctness — observeShelvesForFiction would
            // also work but pulls the sheet into a flow lifecycle we don't
            // need (it dismisses cleanly without lingering state).
            val sheet = _manageShelves.value
            if (sheet is ManageShelvesSheetState.Open && sheet.fictionId == fictionId) {
                _manageShelves.value = sheet.copy(
                    memberOf = shelfRepo.shelvesForFiction(fictionId),
                )
            }
        }
    }

    // ─── existing surface (unchanged behaviour) ───────────────────────────

    fun openFiction(id: String) {
        viewModelScope.launch { _events.send(LibraryUiEvent.OpenFiction(id)) }
    }

    fun resume() {
        val entry = uiState.value.resume ?: return
        viewModelScope.launch {
            // #90 smart-resume — read the user's last play/pause
            // intent. If they explicitly paused before, we still load
            // the chapter (so the player tab is ready) but don't
            // auto-start audio. App-killed-mid-playback (no explicit
            // pause) leaves the flag at its prior `true` so the
            // common "phone died, want to keep listening" case
            // auto-resumes as before.
            val autoPlay = resumePolicy.currentLastWasPlaying()
            // Cold-start: the controller may have nothing loaded (fresh app launch),
            // in which case `play()` is a no-op. `startListening` queues the chapter
            // download and kicks the TTS engine, which is what we actually want.
            playback.startListening(
                entry.fiction.id,
                entry.chapter.id,
                entry.charOffset,
                autoPlay = autoPlay,
            )
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
