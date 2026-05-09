package `in`.jphe.storyvox.feature.browse

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.feature.api.BrowseFilter
import `in`.jphe.storyvox.feature.api.BrowsePaginator
import `in`.jphe.storyvox.feature.api.BrowseRepositoryUi
import `in`.jphe.storyvox.feature.api.BrowseSource
import `in`.jphe.storyvox.feature.api.GitHubSearchFilter
import `in`.jphe.storyvox.feature.api.UiFiction
import `in`.jphe.storyvox.feature.api.UiSearchOrder
import `in`.jphe.storyvox.feature.api.UiSortDirection
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class BrowseTab { Popular, NewReleases, BestRated, Search }

/**
 * Top-level source picker on the Browse screen. Chooses which
 * `FictionSource` the tabs route to. Royal Road is the default; the
 * GitHub option surfaces the curated registry from PR #58 via the
 * existing Popular/NewReleases tabs (BestRated + Search are hidden
 * on GitHub until step 8b adds /search/repositories integration).
 */
enum class BrowseSourceKey(val sourceId: String, val displayName: String) {
    RoyalRoad(SourceIds.ROYAL_ROAD, "Royal Road"),
    GitHub(SourceIds.GITHUB, "GitHub"),
    /** MemPalace — JP's local memory system as a read-only fiction source.
     *  Only meaningful when on JP's home LAN; off-network the source returns
     *  NetworkError on every call and Browse → Palace shows the empty state. */
    // "Palace" instead of "Memory Palace" so the segmented source picker
    // doesn't break the chip label across two lines on narrow phones (#148).
    MemPalace(SourceIds.MEMPALACE, "Palace"),
}

/** Tabs that are meaningful for [source]. GitHub registry doesn't
 *  yet support BestRated (no rating-ordered fetch — registry stores
 *  curator rating but doesn't yet sort by it), so it's hidden on
 *  GitHub. Search is wired as of step 8b — flips
 *  `GitHubSource.search()` to `/search/repositories?q=topic:fiction
 *  +{userQuery}`. */
fun BrowseSourceKey.supportedTabs(): List<BrowseTab> = when (this) {
    BrowseSourceKey.RoyalRoad -> listOf(
        BrowseTab.Popular,
        BrowseTab.NewReleases,
        BrowseTab.BestRated,
        BrowseTab.Search,
    )
    BrowseSourceKey.GitHub -> listOf(
        BrowseTab.Popular,
        BrowseTab.NewReleases,
        BrowseTab.Search,
    )
    // Spec: docs/superpowers/specs/2026-05-08-mempalace-integration-design.md.
    // - Popular = Wings tab (top-N rooms by drawer count).
    // - NewReleases = Recent tab (rooms ordered by latest drawer).
    // - BestRated has no analogue (palace doesn't rank).
    // - Search hidden in v1; surfaces in P1 once cross-room ranking lands.
    BrowseSourceKey.MemPalace -> listOf(
        BrowseTab.Popular,
        BrowseTab.NewReleases,
    )
}

@Immutable
data class BrowseUiState(
    val sourceKey: BrowseSourceKey = BrowseSourceKey.RoyalRoad,
    val tab: BrowseTab = BrowseTab.Popular,
    val query: String = "",
    val items: List<UiFiction> = emptyList(),
    /** True only on the very first page fetch (drives skeleton grid). */
    val isLoading: Boolean = true,
    /** True while fetching subsequent pages (drives footer spinner). */
    val isAppending: Boolean = false,
    /** False once the upstream returned `hasNext = false`. */
    val hasMore: Boolean = true,
    /** Last fetch error from the paginator, if any. Cleared on the next
     *  successful page. The screen surfaces this as an error state when
     *  [items] is empty (no prior data to fall back on) and as a snackbar
     *  / footer hint when [items] is non-empty (a tail-page failed but
     *  earlier pages are still useful). */
    val error: String? = null,
    val filter: BrowseFilter = BrowseFilter(),
    val isFilterActive: Boolean = false,
    /** GitHub-shaped filter — applied when sourceKey is GitHub. RR-source
     *  filter ([filter]) and GitHub filter coexist in state so flipping
     *  between sources doesn't lose either side's settings (subject to
     *  the [BrowseViewModel.selectSource] reset policy). */
    val githubFilter: GitHubSearchFilter = GitHubSearchFilter(),
    val isGitHubFilterActive: Boolean = false,
)

/** Typed view of a paginator's five state flows. Lifted into its own
 *  type so the outer `combine` doesn't need positional `vals[i]` casts —
 *  Copilot called the indexing form fragile and was right. */
private data class PaginatorView(
    val items: List<UiFiction>,
    val isLoading: Boolean,
    val isAppending: Boolean,
    val hasMore: Boolean,
    val error: String?,
)

/** Bundled view of the user-controllable knobs (source picker, tab,
 *  query, RR filter, GitHub filter). Lifted into its own record so the
 *  outer combines stay within the 5-arg `combine` overload as we add
 *  filter shapes per source. */
private data class ControlsView(
    val sourceKey: BrowseSourceKey,
    val tab: BrowseTab,
    val query: String,
    val filter: BrowseFilter,
    val githubFilter: GitHubSearchFilter,
)

/**
 * Browse screen ViewModel. Each (tab, debounced query, filter) tuple
 * resolves to a [BrowseSource]; the repository hands a fresh
 * [BrowsePaginator] for it. The paginator accumulates pages on
 * `loadNext()` calls; the screen calls [loadMore] when the user nears
 * the end of the grid.
 *
 * `flatMapLatest` drops the previous paginator's flows when the tuple
 * changes (tab switch, new search, filter applied) — old paginator
 * objects become unreferenced and GC'd. The initial-load coroutine is
 * driven by `collectLatest` on the same paginator StateFlow so it's
 * cancelled cleanly when the tuple changes mid-fetch.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repo: BrowseRepositoryUi,
) : ViewModel() {

    private val _sourceKey = MutableStateFlow(BrowseSourceKey.RoyalRoad)
    private val _tab = MutableStateFlow(BrowseTab.Popular)
    private val _query = MutableStateFlow("")
    private val _filter = MutableStateFlow(BrowseFilter())
    private val _githubFilter = MutableStateFlow(GitHubSearchFilter())
    val query: StateFlow<String> = _query.asStateFlow()

    /** Active paginator for the current tuple; null when the search tab
     *  has neither a query nor active filters (the empty search hint is
     *  shown instead). */
    private val paginator: StateFlow<BrowsePaginator?> = combine(
        _sourceKey,
        _tab,
        _query.debounce(300),
        _filter,
        _githubFilter,
    ) { sourceKey, tab, q, filter, ghFilter ->
        resolveSource(sourceKey, tab, q, filter, ghFilter)?.let { source -> source to sourceKey.sourceId }
    }
        .distinctUntilChanged()
        .map { pair -> pair?.let { (source, sourceId) -> repo.paginator(source, sourceId) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Kick off the initial page whenever a fresh paginator lands.
        // `collectLatest` cancels the inner suspend if the tuple shifts
        // mid-fetch (e.g. user types more in the search box) — without
        // this guarantee an unreferenced paginator could keep hammering
        // the network after its UI is gone.
        viewModelScope.launch {
            paginator.collectLatest { p -> p?.loadNext() }
        }
    }

    /** All user-controlled knobs collapsed into one typed record so the
     *  outer combines below stay within the 5-arg `combine` overload. */
    private val controls: kotlinx.coroutines.flow.Flow<ControlsView> = combine(
        _sourceKey, _tab, _query, _filter, _githubFilter,
    ) { sourceKey, tab, q, filter, ghFilter ->
        ControlsView(sourceKey, tab, q, filter, ghFilter)
    }

    val uiState: StateFlow<BrowseUiState> = paginator.flatMapLatest { p ->
        if (p == null) {
            // Empty-search/no-filter: surface a quiet idle state so the
            // screen renders SearchHint rather than the skeleton grid.
            controls.map { c ->
                BrowseUiState(
                    sourceKey = c.sourceKey,
                    tab = c.tab,
                    query = c.query,
                    items = emptyList(),
                    isLoading = false,
                    isAppending = false,
                    hasMore = false,
                    error = null,
                    filter = c.filter,
                    isFilterActive = c.filter.isActive(),
                    githubFilter = c.githubFilter,
                    isGitHubFilterActive = c.githubFilter.isActive(),
                )
            }
        } else {
            // Two-step combine: first collapse the paginator's five
            // flows into a typed [PaginatorView], then merge with the
            // [ControlsView]. Keeps each combine within the 5-arg
            // comfort zone and avoids positional `vals[i]` casts.
            val paginatorView = combine(
                p.items,
                p.isLoading,
                p.isAppending,
                p.hasMore,
                p.error,
            ) { items, loading, appending, more, error ->
                PaginatorView(items, loading, appending, more, error)
            }
            combine(paginatorView, controls) { view, c ->
                BrowseUiState(
                    sourceKey = c.sourceKey,
                    tab = c.tab,
                    query = c.query,
                    items = view.items,
                    isLoading = view.isLoading,
                    isAppending = view.isAppending,
                    hasMore = view.hasMore,
                    error = view.error,
                    filter = c.filter,
                    isFilterActive = c.filter.isActive(),
                    githubFilter = c.githubFilter,
                    isGitHubFilterActive = c.githubFilter.isActive(),
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState())

    fun selectSource(key: BrowseSourceKey) {
        if (_sourceKey.value == key) return
        _sourceKey.value = key
        // If the previously-selected tab isn't supported on the new
        // source (e.g. user was on BestRated/Search on RR and switches
        // to GitHub), snap to Popular so the screen has something
        // sensible to render.
        if (_tab.value !in key.supportedTabs()) {
            _tab.value = BrowseTab.Popular
        }
        // Per-source filter shapes don't translate, so clear the
        // *other* source's filter on switch. Symmetrical: leaving
        // GitHub clears the GH filter; leaving RR clears the RR
        // filter. Always clear the query so a half-typed term doesn't
        // leak across sources.
        _query.value = ""
        when (key) {
            BrowseSourceKey.RoyalRoad -> _githubFilter.value = GitHubSearchFilter()
            BrowseSourceKey.GitHub -> _filter.value = BrowseFilter()
            // MemPalace has no source-specific filter shape today — the
            // wing dropdown reuses BrowseFilter.tagsInclude (each wing
            // appears as a single tag on its rooms). Clearing both the
            // RR and GH filters keeps the picker idempotent.
            BrowseSourceKey.MemPalace -> {
                _filter.value = BrowseFilter()
                _githubFilter.value = GitHubSearchFilter()
            }
        }
    }

    fun selectTab(tab: BrowseTab) { _tab.value = tab }
    fun setQuery(q: String) { _query.value = q }
    fun setFilter(filter: BrowseFilter) { _filter.value = filter }
    fun resetFilter() { _filter.value = BrowseFilter() }
    fun setGitHubFilter(filter: GitHubSearchFilter) { _githubFilter.value = filter }
    fun resetGitHubFilter() { _githubFilter.value = GitHubSearchFilter() }

    /** Called by the grid when the user nears the end of the visible
     *  list. Idempotent — the paginator's mutex collapses concurrent
     *  calls. */
    fun loadMore() {
        viewModelScope.launch { paginator.value?.loadNext() }
    }
}

private fun resolveSource(
    sourceKey: BrowseSourceKey,
    tab: BrowseTab,
    q: String,
    filter: BrowseFilter,
    githubFilter: GitHubSearchFilter,
): BrowseSource? = when (sourceKey) {
    // GitHub: filter takes priority over tab. When filter is active OR
    // user is on Search with a typed query, route to FilteredGitHub so
    // the qualifier-laden query lands. Otherwise the tab decides
    // (Popular/NewReleases/Search). Search-with-blank-query stays null
    // so the screen renders SearchHint.
    BrowseSourceKey.GitHub -> when {
        githubFilter.isActive() -> BrowseSource.FilteredGitHub(
            query = if (tab == BrowseTab.Search) q else "",
            filter = githubFilter,
        )
        tab == BrowseTab.Popular -> BrowseSource.Popular
        tab == BrowseTab.NewReleases -> BrowseSource.NewReleases
        tab == BrowseTab.Search -> if (q.isBlank()) null else BrowseSource.Search(q)
        else -> null
    }
    BrowseSourceKey.RoyalRoad -> when {
        filter.isActive() -> BrowseSource.Filtered(
            if (tab == BrowseTab.Search && q.isNotBlank()) filter.copy(term = q) else filter,
        )
        tab == BrowseTab.Popular -> BrowseSource.Popular
        tab == BrowseTab.NewReleases -> BrowseSource.NewReleases
        tab == BrowseTab.BestRated -> BrowseSource.BestRated
        tab == BrowseTab.Search -> if (q.isBlank()) null else BrowseSource.Search(q)
        else -> null
    }
    // MemPalace tabs map straight to the existing BrowseSource enum —
    // Popular = Wings (top rooms by drawer count), NewReleases = Recent
    // (rooms by latest drawer). No filtered/search variant in v1; spec
    // P1 surfaces the daemon's /search endpoint behind a feature flag.
    BrowseSourceKey.MemPalace -> when (tab) {
        BrowseTab.Popular -> BrowseSource.Popular
        BrowseTab.NewReleases -> BrowseSource.NewReleases
        BrowseTab.BestRated -> null
        BrowseTab.Search -> null
    }
}

private fun BrowseFilter.isActive(): Boolean =
    tagsInclude.isNotEmpty() ||
        tagsExclude.isNotEmpty() ||
        statuses.isNotEmpty() ||
        warningsRequire.isNotEmpty() ||
        warningsExclude.isNotEmpty() ||
        type != `in`.jphe.storyvox.feature.api.UiFictionType.All ||
        minPages != null || maxPages != null ||
        minRating != null || maxRating != null ||
        orderBy != UiSearchOrder.Popularity ||
        direction != UiSortDirection.Desc
