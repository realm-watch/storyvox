package `in`.jphe.storyvox.feature.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.db.entity.InboxEvent
import `in`.jphe.storyvox.data.db.entity.Shelf
import `in`.jphe.storyvox.data.repository.ContinueListeningEntry
import `in`.jphe.storyvox.data.repository.FictionRepository
import `in`.jphe.storyvox.data.repository.HistoryEntry
import `in`.jphe.storyvox.data.repository.HistoryRepository
import `in`.jphe.storyvox.data.repository.InboxRepository
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
 * Issue #158 — top-level sub-tabs inside the Library screen. Order matters:
 * the enum's `ordinal` drives [SecondaryTabRow]'s `selectedTabIndex`.
 *
 *  - [All] mirrors the pre-#158 Library grid (Resume card + alphabetical
 *    library grid) — the user's full collection. Chip row from #116 is
 *    visible here to let the user drill into Read/Wishlist shelves
 *    without leaving the tab.
 *  - [Reading] uses the #116 [Shelf.Reading] filter under the hood; on
 *    select we coerce [ShelfFilter] to `OneShelf(Reading)` so the grid
 *    swaps without the chip row needing to show.
 *  - [History] is the chronological chapter-open feed.
 */
/**
 * Issue #158 — top-level Library sub-tabs.
 *
 * **#438 collapse** — v0.5.36 shipped four tabs (`All / Reading / Inbox /
 * History`) stacked directly above a four-chip shelf row (`All / Reading
 * / Read / Wishlist`), so the same strings (`All`, `Reading`) appeared in
 * two adjacent nested navigation surfaces — a Material 3 anti-pattern
 * that retrained users to ignore navigation. Collapsed to three tabs:
 *
 *  - [Library] — the previous `All` tab renamed. Hosts the four-chip
 *    shelf strip below, so `Reading` lives there as a one-tap chip
 *    rather than competing for the same word with the tab row.
 *  - [Inbox] — chronological cross-source notification feed (#383).
 *  - [History] — chronological chapter-open feed (#158).
 *
 * The pre-#438 `Reading` tab is reached in one tap via the shelf chip
 * row, so no functionality is lost; only the visual conflict is.
 */
enum class LibraryTab(val label: String) {
    Library("Library"),
    /**
     * Issue #383 — chronological cross-source notification feed.
     * Sits between Library and History so the user finds it next to
     * Library (the "what's current" surface) rather than buried after
     * History. Carries a numeric badge driven by unread events.
     */
    Inbox("Inbox"),
    History("History"),
}

/**
 * Issue #116 — which row of the chip-filter strip is selected. `All` is
 * the default (current behaviour pre-shelves); the three [Shelf] options
 * filter the grid to fictions that sit on that shelf. Surfaced as a
 * chip row under the [LibraryTab.Library] tab — #438 dropped the
 * pre-existing `LibraryTab.Reading` shortcut tab so this chip row is
 * the canonical "Reading shelf" affordance.
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
    /** Issue #158 — chronological history feed, most-recent-open first. */
    val history: List<HistoryEntry> = emptyList(),
    /** Issue #383 — cross-source Inbox feed, most-recent first. */
    val inbox: List<InboxEvent> = emptyList(),
    /** Issue #383 — unread-event count driving the Inbox tab badge. */
    val inboxUnreadCount: Int = 0,
    /** Selected sub-tab (#158). */
    val tab: LibraryTab = LibraryTab.Library,
    /** Active chip filter (#116) — only meaningful while tab == All. */
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
    /**
     * Issue #383 — Inbox row tap. Carries a fully-resolved deep-link URI
     * string (`storyvox://reader/<fid>/<cid>` or `storyvox://fiction/<fid>`).
     * The host activity decodes the URI and routes — same shape as the
     * existing Reader / Fiction events, just opaque so the Inbox can
     * point at surfaces that don't exist today (live audio player,
     * source-specific detail screens).
     */
    data class OpenInboxLink(val deepLinkUri: String) : LibraryUiEvent
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
    /** Issue #158 — reading history backing the new "History" sub-tab. */
    historyRepo: HistoryRepository,
    /** Issue #383 — cross-source Inbox feed + unread count. */
    private val inboxRepo: InboxRepository,
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

    /**
     * Selected sub-tab. Hoisted out of the combined `uiState` flow so a
     * tab-switch doesn't have to wait for the library/resume/history
     * flows to all emit — pressing the tab feels instant.
     */
    private val _tab = MutableStateFlow(LibraryTab.Library)

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

    /**
     * Issue #383 — Inbox feed + unread count + filter merged into a
     * single tab-related snapshot so the outer combine stays inside
     * the 5-arg overload. Mirrors the `NonPrefsConfigs` pattern in
     * [SettingsRepositoryUiImpl] — roll multiple deps into one
     * product type, combine once outside.
     */
    private data class TabSnapshot(
        val inboxEvents: List<InboxEvent>,
        val inboxUnreadCount: Int,
        val tab: LibraryTab,
        val filter: ShelfFilter,
    )

    private val tabSnapshot: kotlinx.coroutines.flow.Flow<TabSnapshot> =
        combine(
            inboxRepo.observeAll(),
            inboxRepo.observeUnreadCount(),
            _tab,
            _filter,
        ) { events, count, tab, filter ->
            TabSnapshot(events, count, tab, filter)
        }

    val uiState: StateFlow<LibraryUiState> = combine(
        fictionsFlow,
        positionRepo.observeMostRecentContinueListening(),
        historyRepo.observeAll(),
        tabSnapshot,
    ) { library, resume, history, snap ->
        LibraryUiState(
            resume = resume,
            fictions = library,
            history = history,
            inbox = snap.inboxEvents,
            inboxUnreadCount = snap.inboxUnreadCount,
            tab = snap.tab,
            filter = snap.filter,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    // ─── sub-tabs (#158) ──────────────────────────────────────────────────

    /**
     * Tab switch. #438 collapsed the four-tab strip to three; the shelf
     * filter is the chip row under [LibraryTab.Library] now. We don't
     * touch [_filter] on tab switch — the user's last chip choice stays
     * remembered when they bounce out to Inbox/History and back.
     */
    fun selectTab(tab: LibraryTab) {
        _tab.value = tab
        when (tab) {
            LibraryTab.Library -> { /* chip-row filter persists across tab switches */ }
            LibraryTab.History -> { /* history feed renders from state.history */ }
            LibraryTab.Inbox -> { /* inbox feed renders from state.inbox */ }
        }
    }

    /**
     * Issue #383 — Inbox row tap. Marks the event read (so the badge
     * count decrements immediately) and emits a deep-link nav event
     * for the host activity to decode. If the event has no
     * deepLinkUri (rare — source-wide events with no good landing
     * page) we still mark it read; the tap was the acknowledgement.
     */
    fun openInboxEvent(event: InboxEvent) {
        viewModelScope.launch {
            inboxRepo.markRead(event.id)
            val link = event.deepLinkUri ?: return@launch
            _events.send(LibraryUiEvent.OpenInboxLink(link))
        }
    }

    /** Issue #383 — "Mark all read" action in the Inbox top affordance. */
    fun markAllInboxRead() {
        viewModelScope.launch { inboxRepo.markAllRead() }
    }

    /**
     * Issue #158 — History row tap. Navigates to the reader at that
     * (fictionId, chapterId). We deliberately *don't* call
     * `playback.startListening` here: the user might be browsing history
     * to find context, not to start playing right now. Reader opens the
     * chapter view; if they want audio they tap play from there.
     */
    fun openHistoryEntry(entry: HistoryEntry) {
        viewModelScope.launch {
            _events.send(LibraryUiEvent.OpenReader(entry.fictionId, entry.chapterId))
        }
    }

    // ─── chips (#116) ─────────────────────────────────────────────────────

    /** Top-of-screen chip row taps (only visible on Tab.All). */
    fun selectFilter(filter: ShelfFilter) {
        _filter.value = filter
    }

    // ─── manage-shelves bottom sheet (#116) ───────────────────────────────

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
