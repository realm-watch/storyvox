package `in`.jphe.storyvox.feature.browse

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.plugin.SourcePluginDescriptor
import `in`.jphe.storyvox.data.source.plugin.SourcePluginRegistry
import `in`.jphe.storyvox.feature.api.BrowseFilter
import `in`.jphe.storyvox.feature.api.BrowsePaginator
import `in`.jphe.storyvox.feature.api.BrowseRepositoryUi
import `in`.jphe.storyvox.feature.api.BrowseSource
import `in`.jphe.storyvox.feature.api.GitHubSearchFilter
import `in`.jphe.storyvox.feature.api.MemPalaceFilter
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.UiFiction
import `in`.jphe.storyvox.feature.api.UiGitHubAuthState
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class BrowseTab { Popular, NewReleases, BestRated, Search, MyRepos, Starred, Gists }

@Immutable
data class BrowseUiState(
    /** Stable plugin id (see `SourceIds`) of the currently selected
     *  source. The Phase 1/2 `BrowseSourceKey` enum was deleted in
     *  Phase 3 (#384) in favour of registry-driven iteration; the chip
     *  picker, tab list, and filter sheet all key off this string now. */
    val sourceId: String = SourceIds.ROYAL_ROAD,
    val tab: BrowseTab = BrowseTab.Popular,
    val query: String = "",
    val items: List<UiFiction> = emptyList(),
    val isLoading: Boolean = true,
    val isAppending: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
    val filter: BrowseFilter = BrowseFilter(),
    val isFilterActive: Boolean = false,
    val githubFilter: GitHubSearchFilter = GitHubSearchFilter(),
    val isGitHubFilterActive: Boolean = false,
    val palaceFilter: MemPalaceFilter = MemPalaceFilter(),
    val palaceWings: List<String> = emptyList(),
    val githubSignedIn: Boolean = false,
    val hasGitHubRepoScope: Boolean = false,
    val royalRoadSignedIn: Boolean = false,
    /** Sources the user has enabled in Settings, as a set of stable
     *  plugin ids. */
    val enabledSourceIds: Set<String> = emptySet(),
    /** Ordered list of plugin descriptors visible to the picker (the
     *  enabled subset, in registry display order). Surfaced here so the
     *  chip strip can render without re-injecting the registry. */
    val visibleSources: List<SourcePluginDescriptor> = emptyList(),
    /** Issue #443 — true when the Notion source is in anonymous-public
     *  mode (no integration token configured). The Notion listing then
     *  surfaces TechEmpower's public content as a zero-setup demo; the
     *  Browse screen renders a clarifying banner so users understand
     *  what they're seeing isn't "their Notion". */
    val notionAnonymousActive: Boolean = true,
)

/** Typed view of a paginator's five state flows. */
private data class PaginatorView(
    val items: List<UiFiction>,
    val isLoading: Boolean,
    val isAppending: Boolean,
    val hasMore: Boolean,
    val error: String?,
)

/** Bundled view of the user-controllable knobs. */
private data class ControlsView(
    val sourceId: String,
    val tab: BrowseTab,
    val query: String,
    val filter: BrowseFilter,
    val githubFilter: GitHubSearchFilter,
    val palaceFilter: MemPalaceFilter,
    val githubSignedIn: Boolean,
    val hasGitHubRepoScope: Boolean,
    val royalRoadSignedIn: Boolean,
    val enabledSourceIds: Set<String>,
    val visibleSources: List<SourcePluginDescriptor>,
)

/**
 * Browse screen ViewModel.
 *
 * Plugin-seam Phase 3 (#384) — the source picker is now driven by
 * `SourcePluginRegistry.descriptors` rather than the (deleted)
 * `BrowseSourceKey` enum. `selectSource` takes a plugin id; the
 * resolver routes by id string.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repo: BrowseRepositoryUi,
    private val settings: SettingsRepositoryUi,
    private val registry: SourcePluginRegistry,
) : ViewModel() {

    /** Default selected source — first defaultEnabled plugin, or
     *  [SourceIds.ROYAL_ROAD] as a sentinel when nothing is enabled. */
    private val defaultSourceId: String =
        registry.descriptors.firstOrNull { it.defaultEnabled }?.id ?: SourceIds.ROYAL_ROAD

    private val _sourceId = MutableStateFlow(defaultSourceId)
    private val _tab = MutableStateFlow(BrowseTab.Popular)
    private val _query = MutableStateFlow("")
    private val _filter = MutableStateFlow(BrowseFilter())
    private val _githubFilter = MutableStateFlow(GitHubSearchFilter())
    private val _palaceFilter = MutableStateFlow(MemPalaceFilter())
    private val _palaceWings = MutableStateFlow<List<String>>(emptyList())
    private var palaceWingsLoaded = false
    val query: StateFlow<String> = _query.asStateFlow()

    private val githubSignedIn: StateFlow<Boolean> = settings.settings
        .map { it.github is UiGitHubAuthState.SignedIn }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val royalRoadSignedIn: StateFlow<Boolean> = settings.settings
        .map { it.isSignedIn }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Issue #443 — anonymous-public Notion mode is active when no
     *  integration token is configured (the source falls back to the
     *  TechEmpower public Notion content). Surfaces a demo banner on
     *  the Browse → Notion chip so users understand what they're
     *  seeing. */
    private val notionAnonymousActive: StateFlow<Boolean> = settings.settings
        .map { !it.notionTokenConfigured }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val hasGitHubRepoScope: kotlinx.coroutines.flow.Flow<Boolean> =
        settings.settings
            .map { s ->
                val auth = s.github
                auth is UiGitHubAuthState.SignedIn &&
                    auth.scopes.split(' ').any { it == "repo" }
            }
            .distinctUntilChanged()

    private data class AuthSnapshot(
        val githubSignedIn: Boolean,
        val hasGitHubRepoScope: Boolean,
        val royalRoadSignedIn: Boolean,
    )

    private val authSnapshot: kotlinx.coroutines.flow.Flow<AuthSnapshot> =
        combine(githubSignedIn, hasGitHubRepoScope, royalRoadSignedIn) { gh, repo, rr ->
            AuthSnapshot(gh, repo, rr)
        }.distinctUntilChanged()

    /**
     * Plugin-seam Phase 3 (#384) — enabled-set projection straight off
     * the registry-driven `sourcePluginsEnabled` map. The Phase 1/2
     * version collected 17 hand-rolled per-source boolean projections;
     * Phase 3 reads them by id from one map, with `defaultEnabled`
     * fallback for ids that haven't been written yet.
     */
    private val enabledSourceIds: StateFlow<Set<String>> =
        settings.settings
            .map { s ->
                buildSet {
                    for (descriptor in registry.descriptors) {
                        val explicit = s.sourcePluginsEnabled[descriptor.id]
                        val effective = explicit ?: descriptor.defaultEnabled
                        if (effective) add(descriptor.id)
                    }
                }
            }
            .distinctUntilChanged()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                registry.descriptors.filter { it.defaultEnabled }.map { it.id }.toSet(),
            )

    private val paginator: StateFlow<BrowsePaginator?> = run {
        val baseTuple = combine(
            _sourceId,
            _tab,
            _query.debounce(300),
            _filter,
            _githubFilter,
        ) { sourceId, tab, q, filter, ghFilter ->
            ResolveTuple(sourceId, tab, q, filter, ghFilter)
        }
        combine(baseTuple, _palaceFilter, githubSignedIn, royalRoadSignedIn) { tup, palaceFilter, ghSignedIn, rrSignedIn ->
            resolveSource(
                sourceId = tup.sourceId,
                tab = tup.tab,
                q = tup.q,
                filter = tup.filter,
                githubFilter = tup.ghFilter,
                palaceFilter = palaceFilter,
                githubSignedIn = ghSignedIn,
                royalRoadSignedIn = rrSignedIn,
            )?.let { source -> source to tup.sourceId }
        }
            .distinctUntilChanged()
            .map { pair -> pair?.let { (source, sourceId) -> repo.paginator(source, sourceId) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    init {
        viewModelScope.launch {
            paginator.collectLatest { p -> p?.loadNext() }
        }
        viewModelScope.launch {
            githubSignedIn.collectLatest { signedIn ->
                if (!signedIn && _tab.value in AUTH_ONLY_GH_TABS) {
                    _tab.value = BrowseTab.Popular
                }
            }
        }
        viewModelScope.launch {
            enabledSourceIds.collectLatest { enabled ->
                if (_sourceId.value !in enabled) {
                    _sourceId.value = enabled.firstOrNull() ?: defaultSourceId
                }
            }
        }
    }

    private val controls: kotlinx.coroutines.flow.Flow<ControlsView> = run {
        val baseTuple = combine(
            _sourceId, _tab, _query, _filter, _githubFilter,
        ) { sourceId, tab, q, filter, ghFilter ->
            ResolveTuple(sourceId, tab, q, filter, ghFilter)
        }
        combine(
            baseTuple,
            _palaceFilter,
            authSnapshot,
            enabledSourceIds,
        ) { tup, palaceFilter, auth, enabled ->
            ControlsView(
                sourceId = tup.sourceId,
                tab = tup.tab,
                query = tup.q,
                filter = tup.filter,
                githubFilter = tup.ghFilter,
                palaceFilter = palaceFilter,
                githubSignedIn = auth.githubSignedIn,
                hasGitHubRepoScope = auth.hasGitHubRepoScope,
                royalRoadSignedIn = auth.royalRoadSignedIn,
                enabledSourceIds = enabled,
                visibleSources = registry.descriptors.filter { it.id in enabled },
            )
        }
    }

    val uiState: StateFlow<BrowseUiState> = paginator.flatMapLatest { p ->
        if (p == null) {
            combine(controls, _palaceWings, notionAnonymousActive) { c, wings, notionAnon ->
                BrowseUiState(
                    sourceId = c.sourceId,
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
                    palaceFilter = c.palaceFilter,
                    palaceWings = wings,
                    githubSignedIn = c.githubSignedIn,
                    hasGitHubRepoScope = c.hasGitHubRepoScope,
                    royalRoadSignedIn = c.royalRoadSignedIn,
                    enabledSourceIds = c.enabledSourceIds,
                    visibleSources = c.visibleSources,
                    notionAnonymousActive = notionAnon,
                )
            }
        } else {
            val paginatorView = combine(
                p.items,
                p.isLoading,
                p.isAppending,
                p.hasMore,
                p.error,
            ) { items, loading, appending, more, error ->
                PaginatorView(items, loading, appending, more, error)
            }
            combine(paginatorView, controls, _palaceWings, notionAnonymousActive) { view, c, wings, notionAnon ->
                BrowseUiState(
                    sourceId = c.sourceId,
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
                    palaceFilter = c.palaceFilter,
                    palaceWings = wings,
                    githubSignedIn = c.githubSignedIn,
                    hasGitHubRepoScope = c.hasGitHubRepoScope,
                    royalRoadSignedIn = c.royalRoadSignedIn,
                    enabledSourceIds = c.enabledSourceIds,
                    visibleSources = c.visibleSources,
                    notionAnonymousActive = notionAnon,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState(sourceId = defaultSourceId))

    /** Select a source by stable plugin id (see `SourceIds`). */
    fun selectSource(id: String) {
        if (_sourceId.value == id) return
        _sourceId.value = id
        val supported = BrowseSourceUi.supportedTabs(id, githubSignedIn.value)
        if (_tab.value !in supported) {
            _tab.value = BrowseTab.Popular
        }
        _query.value = ""
        when (BrowseSourceUi.filterShape(id)) {
            FilterShape.RoyalRoad -> {
                _githubFilter.value = GitHubSearchFilter()
                _palaceFilter.value = MemPalaceFilter()
            }
            FilterShape.GitHub -> {
                _filter.value = BrowseFilter()
                _palaceFilter.value = MemPalaceFilter()
            }
            FilterShape.MemPalace -> {
                _filter.value = BrowseFilter()
                _githubFilter.value = GitHubSearchFilter()
                ensurePalaceWingsLoaded()
            }
            FilterShape.None -> {
                _filter.value = BrowseFilter()
                _githubFilter.value = GitHubSearchFilter()
                _palaceFilter.value = MemPalaceFilter()
            }
        }
    }

    private fun ensurePalaceWingsLoaded() {
        if (palaceWingsLoaded) return
        palaceWingsLoaded = true
        viewModelScope.launch {
            val wings = runCatching { repo.genres(SourceIds.MEMPALACE) }
                .getOrDefault(emptyList())
            if (wings.isEmpty()) palaceWingsLoaded = false
            _palaceWings.value = wings
        }
    }

    fun selectTab(tab: BrowseTab) { _tab.value = tab }
    fun setQuery(q: String) { _query.value = q }
    fun setFilter(filter: BrowseFilter) { _filter.value = filter }
    fun resetFilter() { _filter.value = BrowseFilter() }
    fun setGitHubFilter(filter: GitHubSearchFilter) { _githubFilter.value = filter }
    fun resetGitHubFilter() { _githubFilter.value = GitHubSearchFilter() }
    fun setPalaceFilter(filter: MemPalaceFilter) { _palaceFilter.value = filter }
    fun resetPalaceFilter() { _palaceFilter.value = MemPalaceFilter() }

    fun loadMore() {
        viewModelScope.launch { paginator.value?.loadNext() }
    }

    // ─── RSS feed management (#247) ─────────────────────────────────────

    val rssSubscriptions: StateFlow<List<String>> =
        settings.rssSubscriptions
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val suggestedRssFeeds: StateFlow<List<`in`.jphe.storyvox.feature.api.SuggestedFeed>> =
        settings.suggestedRssFeeds
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addRssFeed(url: String) {
        viewModelScope.launch { settings.addRssFeed(url) }
    }

    fun removeRssFeedByUrl(url: String) {
        viewModelScope.launch { settings.removeRssFeedByUrl(url) }
    }
}

private val AUTH_ONLY_GH_TABS: Set<BrowseTab> = setOf(BrowseTab.MyRepos, BrowseTab.Starred, BrowseTab.Gists)

private data class ResolveTuple(
    val sourceId: String,
    val tab: BrowseTab,
    val q: String,
    val filter: BrowseFilter,
    val ghFilter: GitHubSearchFilter,
)

/**
 * Phase 3 (#384) — id-keyed source resolver. The Phase 1/2 version
 * was an exhaustive `when (BrowseSourceKey)` on 17 enum branches;
 * Phase 3 keys off the stable plugin id, with a "registry default"
 * fall-through for unknown ids (Popular / NewReleases / Search →
 * the default `BrowseSource` shape). Source-specific routing (RR
 * sign-in gate, GitHub auth-only tabs, MemPalace wing routing)
 * stays as targeted branches keyed off the id constants.
 */
private fun resolveSource(
    sourceId: String,
    tab: BrowseTab,
    q: String,
    filter: BrowseFilter,
    githubFilter: GitHubSearchFilter,
    palaceFilter: MemPalaceFilter,
    githubSignedIn: Boolean,
    royalRoadSignedIn: Boolean,
): BrowseSource? = when (sourceId) {
    SourceIds.GITHUB -> when {
        tab == BrowseTab.MyRepos -> if (githubSignedIn) BrowseSource.GitHubMyRepos else null
        tab == BrowseTab.Starred -> if (githubSignedIn) BrowseSource.GitHubStarred else null
        tab == BrowseTab.Gists -> if (githubSignedIn) BrowseSource.GitHubGists else null
        githubFilter.isActive() -> BrowseSource.FilteredGitHub(
            query = if (tab == BrowseTab.Search) q else "",
            filter = githubFilter,
        )
        tab == BrowseTab.Popular -> BrowseSource.Popular
        tab == BrowseTab.NewReleases -> BrowseSource.NewReleases
        tab == BrowseTab.Search -> if (q.isBlank()) null else BrowseSource.Search(q)
        else -> null
    }
    SourceIds.ROYAL_ROAD -> when {
        tab == BrowseTab.Search -> if (q.isBlank()) null else BrowseSource.Search(q)
        !royalRoadSignedIn -> null
        filter.isActive() -> BrowseSource.Filtered(
            if (tab == BrowseTab.Search && q.isNotBlank()) filter.copy(term = q) else filter,
        )
        tab == BrowseTab.Popular -> BrowseSource.Popular
        tab == BrowseTab.NewReleases -> BrowseSource.NewReleases
        tab == BrowseTab.BestRated -> BrowseSource.BestRated
        else -> null
    }
    SourceIds.MEMPALACE -> when {
        palaceFilter.wing != null -> BrowseSource.ByGenre(palaceFilter.wing)
        tab == BrowseTab.Popular -> BrowseSource.Popular
        tab == BrowseTab.NewReleases -> BrowseSource.NewReleases
        else -> null
    }
    SourceIds.RSS -> when {
        tab == BrowseTab.Search -> if (q.isBlank()) BrowseSource.NewReleases else BrowseSource.Search(q)
        tab == BrowseTab.Popular -> BrowseSource.Popular
        tab == BrowseTab.NewReleases -> BrowseSource.NewReleases
        else -> null
    }
    SourceIds.EPUB, SourceIds.OUTLINE -> when {
        tab == BrowseTab.Search -> if (q.isBlank()) BrowseSource.Popular else BrowseSource.Search(q)
        tab == BrowseTab.Popular -> BrowseSource.Popular
        else -> null
    }
    SourceIds.AO3 -> when (tab) {
        BrowseTab.Popular -> BrowseSource.Popular
        BrowseTab.NewReleases -> BrowseSource.NewReleases
        else -> null
    }
    // Issue #417 — Radio source. Popular surfaces the curated + starred
    // list; Search hits Radio Browser. KVMR alias stays Popular-only
    // for v0.5.20+ persisted shortcuts that still route through it.
    SourceIds.RADIO -> when (tab) {
        BrowseTab.Popular -> BrowseSource.Popular
        BrowseTab.Search -> if (q.isBlank()) null else BrowseSource.Search(q)
        else -> null
    }
    SourceIds.KVMR -> when (tab) {
        BrowseTab.Popular -> BrowseSource.Popular
        else -> null
    }
    // Default Popular/NewReleases/Search shape — Gutenberg, Standard
    // Ebooks, Wikipedia, Wikisource, Notion, Hacker News, arXiv, PLOS,
    // Discord all use this resolver.
    else -> when (tab) {
        BrowseTab.Popular -> BrowseSource.Popular
        BrowseTab.NewReleases -> BrowseSource.NewReleases
        BrowseTab.Search -> if (q.isBlank()) null else BrowseSource.Search(q)
        else -> null
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
