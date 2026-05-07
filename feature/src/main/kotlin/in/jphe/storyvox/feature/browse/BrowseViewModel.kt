package `in`.jphe.storyvox.feature.browse

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.feature.api.BrowseFilter
import `in`.jphe.storyvox.feature.api.BrowsePaginator
import `in`.jphe.storyvox.feature.api.BrowseRepositoryUi
import `in`.jphe.storyvox.feature.api.BrowseSource
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

@Immutable
data class BrowseUiState(
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

    private val _tab = MutableStateFlow(BrowseTab.Popular)
    private val _query = MutableStateFlow("")
    private val _filter = MutableStateFlow(BrowseFilter())
    val query: StateFlow<String> = _query.asStateFlow()

    /** Active paginator for the current tuple; null when the search tab
     *  has neither a query nor active filters (the empty search hint is
     *  shown instead). */
    private val paginator: StateFlow<BrowsePaginator?> = combine(
        _tab,
        _query.debounce(300),
        _filter,
    ) { tab, q, filter -> resolveSource(tab, q, filter) }
        .distinctUntilChanged()
        .map { source -> source?.let(repo::paginator) }
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

    val uiState: StateFlow<BrowseUiState> = paginator.flatMapLatest { p ->
        if (p == null) {
            // Empty-search/no-filter: surface a quiet idle state so the
            // screen renders SearchHint rather than the skeleton grid.
            combine(_tab, _query, _filter) { tab, q, filter ->
                BrowseUiState(
                    tab = tab,
                    query = q,
                    items = emptyList(),
                    isLoading = false,
                    isAppending = false,
                    hasMore = false,
                    error = null,
                    filter = filter,
                    isFilterActive = filter.isActive(),
                )
            }
        } else {
            // Two-step combine: first collapse the paginator's five
            // flows into a typed [PaginatorView], then merge with the
            // tab/query/filter trio. Keeps each combine within the
            // 5-arg comfort zone and avoids positional `vals[i]` casts.
            val paginatorView = combine(
                p.items,
                p.isLoading,
                p.isAppending,
                p.hasMore,
                p.error,
            ) { items, loading, appending, more, error ->
                PaginatorView(items, loading, appending, more, error)
            }
            combine(paginatorView, _tab, _query, _filter) { view, tab, q, filter ->
                BrowseUiState(
                    tab = tab,
                    query = q,
                    items = view.items,
                    isLoading = view.isLoading,
                    isAppending = view.isAppending,
                    hasMore = view.hasMore,
                    error = view.error,
                    filter = filter,
                    isFilterActive = filter.isActive(),
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState())

    fun selectTab(tab: BrowseTab) { _tab.value = tab }
    fun setQuery(q: String) { _query.value = q }
    fun setFilter(filter: BrowseFilter) { _filter.value = filter }
    fun resetFilter() { _filter.value = BrowseFilter() }

    /** Called by the grid when the user nears the end of the visible
     *  list. Idempotent — the paginator's mutex collapses concurrent
     *  calls. */
    fun loadMore() {
        viewModelScope.launch { paginator.value?.loadNext() }
    }
}

private fun resolveSource(tab: BrowseTab, q: String, filter: BrowseFilter): BrowseSource? = when {
    filter.isActive() -> BrowseSource.Filtered(
        if (tab == BrowseTab.Search && q.isNotBlank()) filter.copy(term = q) else filter,
    )
    tab == BrowseTab.Popular -> BrowseSource.Popular
    tab == BrowseTab.NewReleases -> BrowseSource.NewReleases
    tab == BrowseTab.BestRated -> BrowseSource.BestRated
    tab == BrowseTab.Search -> if (q.isBlank()) null else BrowseSource.Search(q)
    else -> null
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
