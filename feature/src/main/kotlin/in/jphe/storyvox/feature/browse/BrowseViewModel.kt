package `in`.jphe.storyvox.feature.browse

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.feature.api.BrowseFilter
import `in`.jphe.storyvox.feature.api.BrowseRepositoryUi
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

enum class BrowseTab { Popular, NewReleases, BestRated, Search }

@Immutable
data class BrowseUiState(
    val tab: BrowseTab = BrowseTab.Popular,
    val query: String = "",
    val items: List<UiFiction> = emptyList(),
    val isLoading: Boolean = true,
    val filter: BrowseFilter = BrowseFilter(),
    val isFilterActive: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repo: BrowseRepositoryUi,
) : ViewModel() {

    private val _tab = MutableStateFlow(BrowseTab.Popular)
    private val _query = MutableStateFlow("")
    private val _isLoading = MutableStateFlow(true)
    private val _filter = MutableStateFlow(BrowseFilter())
    val query: StateFlow<String> = _query.asStateFlow()

    private val itemsFlow = combine(
        _tab,
        _query.debounce(300),
        _filter,
    ) { tab, q, filter -> Triple(tab, q, filter) }
        .flatMapLatest { (tab, q, filter) ->
            val isFilterActive = filter.isActive()
            val isEmptySearch = tab == BrowseTab.Search && q.isBlank() && !isFilterActive
            if (!isEmptySearch) _isLoading.value = true
            when {
                // When filters are set, route everything through `filtered`. The search tab
                // also folds its query field into the filter so users can refine a search.
                isFilterActive -> repo.filtered(
                    if (tab == BrowseTab.Search && q.isNotBlank()) filter.copy(term = q) else filter,
                )
                tab == BrowseTab.Popular -> repo.popular()
                tab == BrowseTab.NewReleases -> repo.newReleases()
                tab == BrowseTab.BestRated -> repo.bestRated()
                tab == BrowseTab.Search -> if (q.isBlank()) flowOf(emptyList()) else repo.search(q)
                else -> flowOf(emptyList())
            }.onEach { _isLoading.value = false }
        }.onStart { _isLoading.value = true }

    val uiState: StateFlow<BrowseUiState> = combine(
        _tab,
        _query,
        itemsFlow,
        _isLoading,
        _filter,
    ) { tab, q, items, loading, filter ->
        BrowseUiState(
            tab = tab,
            query = q,
            items = items,
            isLoading = loading,
            filter = filter,
            isFilterActive = filter.isActive(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState())

    fun selectTab(tab: BrowseTab) { _tab.value = tab }
    fun setQuery(q: String) { _query.value = q }
    fun setFilter(filter: BrowseFilter) { _filter.value = filter }
    fun resetFilter() { _filter.value = BrowseFilter() }
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

