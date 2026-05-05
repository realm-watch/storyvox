package `in`.jphe.storyvox.feature.browse

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.feature.api.BrowseRepositoryUi
import `in`.jphe.storyvox.feature.api.UiFiction
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
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repo: BrowseRepositoryUi,
) : ViewModel() {

    private val _tab = MutableStateFlow(BrowseTab.Popular)
    private val _query = MutableStateFlow("")
    private val _isLoading = MutableStateFlow(true)
    val query: StateFlow<String> = _query.asStateFlow()

    private val itemsFlow = combine(_tab, _query.debounce(300)) { tab, q -> tab to q }
        .flatMapLatest { (tab, q) ->
            // Empty search query resolves instantly — not a loading state.
            val isEmptySearch = tab == BrowseTab.Search && q.isBlank()
            if (!isEmptySearch) _isLoading.value = true
            when (tab) {
                BrowseTab.Popular -> repo.popular()
                BrowseTab.NewReleases -> repo.newReleases()
                BrowseTab.BestRated -> repo.bestRated()
                BrowseTab.Search -> if (q.isBlank()) flowOf(emptyList()) else repo.search(q)
            }.onEach { _isLoading.value = false }
        }.onStart { _isLoading.value = true }

    val uiState: StateFlow<BrowseUiState> = combine(
        _tab,
        _query,
        itemsFlow,
        _isLoading,
    ) { tab, q, items, loading ->
        BrowseUiState(tab = tab, query = q, items = items, isLoading = loading)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState())

    fun selectTab(tab: BrowseTab) { _tab.value = tab }
    fun setQuery(q: String) { _query.value = q }
}
