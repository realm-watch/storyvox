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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class BrowseTab { Popular, NewReleases, BestRated, Search, MyRepos, Starred, Gists }

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
    /** RSS / Atom feeds (#236) — user's own subscription list, no global
     *  catalog. Each subscribed feed URL becomes one fiction; each item
     *  is one chapter. Pure user-content backend. */
    Rss(SourceIds.RSS, "RSS"),
    /** Local EPUB files (#235) — user picks a folder via SAF, indexed
     *  EPUBs render as fictions, spine items as chapters. Zero-network. */
    Epub(SourceIds.EPUB, "Local"),
    /** Outline (#245) — self-hosted wiki. Collections = fictions,
     *  documents = chapters. Pure user-content backend. */
    Outline(SourceIds.OUTLINE, "Wiki"),
    /** Project Gutenberg (#237) — 70,000+ public-domain titles via
     *  the Gutendex JSON catalog. Tap-to-add downloads each book's
     *  EPUB once and renders it through the `:source-epub` parser.
     *  Most-legally-clean source in the roster. */
    Gutenberg(SourceIds.GUTENBERG, "Gutenberg"),
    /** Archive of Our Own (#381) — 14M+ fanfiction works via AO3's
     *  per-tag Atom feeds (catalog) + per-work EPUB downloads
     *  (content). Same content pipeline as Gutenberg; discovery is
     *  fundamentally per-tag rather than per-catalog, so the
     *  genre picker drives a curated fandom list. */
    Ao3(SourceIds.AO3, "AO3"),
    /** Standard Ebooks (#375) — ~900 hand-curated, typographically
     *  polished public-domain classics. Browse hits the public HTML
     *  catalog at `/ebooks?view=list`; tap-to-add downloads the
     *  recommended-compatible EPUB once and renders chapters through
     *  the `:source-epub` parser. Same CC0 legal posture as Gutenberg. */
    StandardEbooks(SourceIds.STANDARD_EBOOKS, "Standard Ebooks"),
}

/** Tabs that are meaningful for [source]. GitHub registry doesn't
 *  yet support BestRated (no rating-ordered fetch — registry stores
 *  curator rating but doesn't yet sort by it), so it's hidden on
 *  GitHub. Search is wired as of step 8b — flips
 *  `GitHubSource.search()` to `/search/repositories?q=topic:fiction
 *  +{userQuery}`.
 *
 *  [githubSignedIn] gates the auth-only tabs on the GitHub source —
 *  `MyRepos` (#200), `Starred` (#201), and `Gists` (#202) — visible
 *  only when the user has captured an OAuth session via the Device
 *  Flow. Defaulting false
 *  keeps existing call sites (tests, screens that don't observe
 *  settings yet) on the anonymous tab list. */
fun BrowseSourceKey.supportedTabs(githubSignedIn: Boolean = false): List<BrowseTab> = when (this) {
    BrowseSourceKey.RoyalRoad -> listOf(
        BrowseTab.Popular,
        BrowseTab.NewReleases,
        BrowseTab.BestRated,
        BrowseTab.Search,
    )
    BrowseSourceKey.GitHub -> buildList {
        add(BrowseTab.Popular)
        add(BrowseTab.NewReleases)
        if (githubSignedIn) {
            add(BrowseTab.MyRepos)
            add(BrowseTab.Starred)
            add(BrowseTab.Gists)
        }
        add(BrowseTab.Search)
    }
    // Spec: docs/superpowers/specs/2026-05-08-mempalace-integration-design.md.
    // - Popular = Wings tab (top-N rooms by drawer count).
    // - NewReleases = Recent tab (rooms ordered by latest drawer).
    // - BestRated has no analogue (palace doesn't rank).
    // - Search hidden in v1; surfaces in P1 once cross-room ranking lands.
    BrowseSourceKey.MemPalace -> listOf(
        BrowseTab.Popular,
        BrowseTab.NewReleases,
    )
    // RSS subscriptions form a flat user-curated list. NewReleases sorts
    // by most-recent-item-first; Popular shows the same set in subscription
    // order. Search filters by feed title. BestRated has no analogue.
    BrowseSourceKey.Rss -> listOf(
        BrowseTab.NewReleases,
        BrowseTab.Popular,
        BrowseTab.Search,
    )
    // Local EPUB files: indexed list + search by filename. NewReleases
    // and Popular both list the indexed books in the same order
    // (alphabetical by filename) since EPUBs are static.
    BrowseSourceKey.Epub -> listOf(
        BrowseTab.Popular,
        BrowseTab.Search,
    )
    BrowseSourceKey.Outline -> listOf(
        BrowseTab.Popular,
        BrowseTab.Search,
    )
    // Gutenberg (#237): Popular sorts by Gutendex download_count;
    // NewReleases sorts by highest book-id (PG ingests roughly
    // chronologically). BestRated has no analogue. Search hits
    // Gutendex's title+author full-text matcher.
    BrowseSourceKey.Gutenberg -> listOf(
        BrowseTab.Popular,
        BrowseTab.NewReleases,
        BrowseTab.Search,
    )
    // AO3 (#381): Popular and NewReleases both surface the
    // per-tag Atom feed (sorted by recency — AO3 doesn't expose a
    // separate popularity signal without HTML scraping, which v1
    // explicitly opts out of). Search is hidden in v1 because the
    // AO3 search endpoint returns HTML only; reinstating it is the
    // follow-up. The fandom row (BrowseFilter genres) routes
    // through byGenre() to a specific tag's feed.
    BrowseSourceKey.Ao3 -> listOf(
        BrowseTab.Popular,
        BrowseTab.NewReleases,
    // Standard Ebooks (#375): Popular sorts by SE's "popularity"
    // (most → least); NewReleases sorts by SE's "default" (release
    // date desc — i.e. newest produced first). Search hits the same
    // listing endpoint with `?query=`. BestRated has no analogue
    // (SE doesn't rank by reading-quality). Same tab shape as
    // Gutenberg — both are catalog-plus-EPUB-download backends.
    BrowseSourceKey.StandardEbooks -> listOf(
        BrowseTab.Popular,
        BrowseTab.NewReleases,
        BrowseTab.Search,
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
    /** MemPalace-shaped filter (#191) — applied when sourceKey is
     *  MemPalace. Coexists with [filter] / [githubFilter] across
     *  source switches per [BrowseViewModel.selectSource] reset
     *  policy. */
    val palaceFilter: MemPalaceFilter = MemPalaceFilter(),
    /** Available wing names for the MemPalace filter sheet. Loaded
     *  lazily on first switch to MemPalace; empty until the daemon
     *  responds (or daemon unreachable). */
    val palaceWings: List<String> = emptyList(),
    /** True when an OAuth session is captured. Drives the `MyRepos`
     *  (#200) and `Gists` (#202) tab visibility on the GitHub
     *  source. Sourced from `SettingsRepositoryUi.settings.github`
     *  and flips back to false on sign-out / token expiry. */
    val githubSignedIn: Boolean = false,
    /** True when the user has a GitHub OAuth session that includes the
     *  `repo` scope (#203 / #204). Drives the visibility chip row in
     *  the GitHub filter sheet — the `is:public` / `is:private`
     *  qualifiers are only meaningful for callers with private-repo
     *  access. */
    val hasGitHubRepoScope: Boolean = false,
    /** Issue #241 — Royal Road sign-in state. Drives the soft-gate on
     *  RR listing tabs (Popular / NewReleases / BestRated / filter-
     *  active): when false, those tabs render a sign-in CTA empty
     *  state rather than firing an anonymous request that returns the
     *  same content but carries the "bot" framing. Search and
     *  Add-by-URL keep working anonymously — they target specific
     *  URLs the user already knows. */
    val royalRoadSignedIn: Boolean = false,
    /** Sources the user has enabled in Settings (#221). Drives the
     *  BrowseSourcePicker membership — disabled sources are hidden from
     *  the chip strip. Default to all three so a fresh-install user sees
     *  the full picker. */
    val enabledSources: Set<BrowseSourceKey> = BrowseSourceKey.entries.toSet(),
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
 *  query, RR filter, GitHub filter, MemPalace filter, GH sign-in).
 *  Lifted into its own record so the outer combines stay within the
 *  5-arg `combine` overload as we add filter shapes per source. The
 *  MemPalace filter and the sign-in flag are folded in via nested
 *  combines (see [BrowseViewModel.controls]) to keep within the
 *  overload arity. */
private data class ControlsView(
    val sourceKey: BrowseSourceKey,
    val tab: BrowseTab,
    val query: String,
    val filter: BrowseFilter,
    val githubFilter: GitHubSearchFilter,
    val palaceFilter: MemPalaceFilter,
    /** Snapshot of the GitHub auth state — true when the user has a
     *  captured session (`UiGitHubAuthState.SignedIn`). Drives the
     *  `MyRepos` (#200) and `Gists` (#202) tab visibility conditions
     *  in [BrowseUiState]. */
    val githubSignedIn: Boolean,
    val hasGitHubRepoScope: Boolean,
    /** Issue #241 — sourced from `SettingsRepositoryUi.settings.isSignedIn`
     *  (Royal Road cookie state). Drives the soft-gate on RR listing
     *  tabs in [resolveSource] and the sign-in CTA empty state in
     *  BrowseScreen. */
    val royalRoadSignedIn: Boolean,
    /** Backends the user has not toggled off in Settings (#221).
     *  Drives [BrowseSourcePicker] membership and an auto-snap when the
     *  currently-selected source disappears. */
    val enabledSources: Set<BrowseSourceKey>,
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
    private val settings: SettingsRepositoryUi,
) : ViewModel() {

    private val _sourceKey = MutableStateFlow(BrowseSourceKey.RoyalRoad)
    private val _tab = MutableStateFlow(BrowseTab.Popular)
    private val _query = MutableStateFlow("")
    private val _filter = MutableStateFlow(BrowseFilter())
    private val _githubFilter = MutableStateFlow(GitHubSearchFilter())
    private val _palaceFilter = MutableStateFlow(MemPalaceFilter())
    private val _palaceWings = MutableStateFlow<List<String>>(emptyList())
    /** Latches once the wings have been fetched (or attempted) so we
     *  don't re-hit the daemon on every source switch. Reset only on
     *  process death; a stale list is acceptable in v1. */
    private var palaceWingsLoaded = false
    val query: StateFlow<String> = _query.asStateFlow()

    /** Github sign-in projection — drives MyRepos (#200) and Gists
     *  (#202) tab visibility. `Expired` reads as signed-out: the disk
     *  copy is intact for Settings to render "session expired" but the
     *  listing endpoints would 401, so hiding the tabs keeps the user
     *  from a guaranteed-failure path. */
    private val githubSignedIn: StateFlow<Boolean> = settings.settings
        .map { it.github is UiGitHubAuthState.SignedIn }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Issue #241 — Royal Road sign-in projection. Single boolean
     *  derived from `UiSettings.isSignedIn` (the RR cookie state).
     *  Drives the soft-gate on listing tabs in [resolveSource] and the
     *  empty-state CTA in BrowseScreen. StateFlow so [selectSource]'s
     *  init-block auto-snap can read `.value` synchronously. */
    private val royalRoadSignedIn: StateFlow<Boolean> = settings.settings
        .map { it.isSignedIn }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** True when the user is signed in to GitHub with the `repo` scope
     *  granted. The scopes string is space-separated per RFC 6749; the
     *  Settings impl persists exactly what GitHub returned. Drives the
     *  GitHub filter sheet's visibility chip row — without `repo` the
     *  `is:private` qualifier silently returns nothing, so we hide the
     *  knob entirely instead of letting users wander into an empty grid. */
    private val hasGitHubRepoScope: kotlinx.coroutines.flow.Flow<Boolean> =
        settings.settings
            .map { s ->
                val auth = s.github
                auth is UiGitHubAuthState.SignedIn &&
                    auth.scopes.split(' ').any { it == "repo" }
            }
            .distinctUntilChanged()

    /** Bundled auth signals — flows into the controls combine as one arg
     *  to stay within the 5-arg overload after #241 added a third boolean.
     *  Recomposes on any field change; downstream code reads what it needs. */
    private data class AuthSnapshot(
        val githubSignedIn: Boolean,
        val hasGitHubRepoScope: Boolean,
        val royalRoadSignedIn: Boolean,
    )

    private val authSnapshot: kotlinx.coroutines.flow.Flow<AuthSnapshot> =
        combine(githubSignedIn, hasGitHubRepoScope, royalRoadSignedIn) { gh, repo, rr ->
            AuthSnapshot(gh, repo, rr)
        }.distinctUntilChanged()

    /** Per-backend on/off projection (#221). The Settings screen exposes
     *  three switches; this collapses them to a [Set] of enabled keys so
     *  Browse can filter the source picker to the user's preference. A
     *  fresh install defaults to all three, matching pre-#221 behavior.
     *  StateFlow (not cold Flow) so [selectSource]'s init-block snap can
     *  read .value synchronously. */
    private val enabledSources: StateFlow<Set<BrowseSourceKey>> =
        settings.settings
            .map { s ->
                buildSet {
                    if (s.sourceRoyalRoadEnabled) add(BrowseSourceKey.RoyalRoad)
                    if (s.sourceGitHubEnabled) add(BrowseSourceKey.GitHub)
                    if (s.sourceMemPalaceEnabled) add(BrowseSourceKey.MemPalace)
                    if (s.sourceRssEnabled) add(BrowseSourceKey.Rss)
                    if (s.sourceEpubEnabled) add(BrowseSourceKey.Epub)
                    if (s.sourceOutlineEnabled) add(BrowseSourceKey.Outline)
                    if (s.sourceGutenbergEnabled) add(BrowseSourceKey.Gutenberg)
                    if (s.sourceAo3Enabled) add(BrowseSourceKey.Ao3)
                    if (s.sourceStandardEbooksEnabled) add(BrowseSourceKey.StandardEbooks)
                }
            }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseSourceKey.entries.toSet())

    /** Active paginator for the current tuple; null when the search tab
     *  has neither a query nor active filters (the empty search hint is
     *  shown instead). Multi-step combine: the 5-arg overload is full
     *  with source/tab/query/RRFilter/GHFilter, so palaceFilter (#191)
     *  and githubSignedIn (#200) layer on top in nested combines. */
    private val paginator: StateFlow<BrowsePaginator?> = run {
        val baseTuple = combine(
            _sourceKey,
            _tab,
            _query.debounce(300),
            _filter,
            _githubFilter,
        ) { sourceKey, tab, q, filter, ghFilter ->
            ResolveTuple(sourceKey, tab, q, filter, ghFilter)
        }
        combine(baseTuple, _palaceFilter, githubSignedIn, royalRoadSignedIn) { tup, palaceFilter, ghSignedIn, rrSignedIn ->
            resolveSource(
                sourceKey = tup.sourceKey,
                tab = tup.tab,
                q = tup.q,
                filter = tup.filter,
                githubFilter = tup.ghFilter,
                palaceFilter = palaceFilter,
                githubSignedIn = ghSignedIn,
                royalRoadSignedIn = rrSignedIn,
            )?.let { source -> source to tup.sourceKey.sourceId }
        }
            .distinctUntilChanged()
            .map { pair -> pair?.let { (source, sourceId) -> repo.paginator(source, sourceId) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    init {
        // Kick off the initial page whenever a fresh paginator lands.
        // `collectLatest` cancels the inner suspend if the tuple shifts
        // mid-fetch (e.g. user types more in the search box) — without
        // this guarantee an unreferenced paginator could keep hammering
        // the network after its UI is gone.
        viewModelScope.launch {
            paginator.collectLatest { p -> p?.loadNext() }
        }
        // Auto-snap off auth-only tabs (`MyRepos` #200, `Starred` #201,
        // `Gists` #202) when the user signs out. They disappear from
        // `supportedTabs(githubSignedIn=false)`, so leaving the value
        // pinned would render an empty grid driven by a null
        // BrowseSource. Snap to Popular so the screen has something to
        // draw.
        viewModelScope.launch {
            githubSignedIn.collectLatest { signedIn ->
                if (!signedIn && _tab.value in AUTH_ONLY_GH_TABS) {
                    _tab.value = BrowseTab.Popular
                }
            }
        }
        // Auto-snap source selection when the user disables the current
        // backend in Settings (#221). The picker filters disabled keys
        // out, but `_sourceKey` is the source-of-truth for the paginator
        // and would otherwise keep firing requests against a backend the
        // user just told us to ignore. Snap to the first enabled key, or
        // RoyalRoad as a sentinel if the user disabled everything (the
        // empty-picker branch in BrowseSourcePicker handles the visual).
        viewModelScope.launch {
            enabledSources.collectLatest { enabled ->
                if (_sourceKey.value !in enabled) {
                    _sourceKey.value = enabled.firstOrNull() ?: BrowseSourceKey.RoyalRoad
                }
            }
        }
    }

    /** All user-controlled knobs collapsed into one typed record. The
     *  inner 5-arg combine builds the base tuple; outer combine folds
     *  in palaceFilter (#191), githubSignedIn (#200/#202), and the
     *  has-`repo`-scope flag (#204) without exceeding the `combine`
     *  5-arg overload. */
    private val controls: kotlinx.coroutines.flow.Flow<ControlsView> = run {
        val baseTuple = combine(
            _sourceKey, _tab, _query, _filter, _githubFilter,
        ) { sourceKey, tab, q, filter, ghFilter ->
            ResolveTuple(sourceKey, tab, q, filter, ghFilter)
        }
        // 4-arg combine — under the ceiling thanks to [authSnapshot]
        // bundling the three auth-related booleans (github sign-in,
        // github repo-scope, royal-road sign-in #241). If a 6th
        // independent controls flow ever needs to land, lift one of
        // these into a side StateFlow consumed inside the lambda
        // rather than reaching for the variadic overload.
        combine(
            baseTuple,
            _palaceFilter,
            authSnapshot,
            enabledSources,
        ) { tup, palaceFilter, auth, enabled ->
            ControlsView(
                sourceKey = tup.sourceKey,
                tab = tup.tab,
                query = tup.q,
                filter = tup.filter,
                githubFilter = tup.ghFilter,
                palaceFilter = palaceFilter,
                githubSignedIn = auth.githubSignedIn,
                hasGitHubRepoScope = auth.hasGitHubRepoScope,
                royalRoadSignedIn = auth.royalRoadSignedIn,
                enabledSources = enabled,
            )
        }
    }

    val uiState: StateFlow<BrowseUiState> = paginator.flatMapLatest { p ->
        if (p == null) {
            // Empty-search/no-filter: surface a quiet idle state so the
            // screen renders SearchHint rather than the skeleton grid.
            combine(controls, _palaceWings) { c, wings ->
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
                    palaceFilter = c.palaceFilter,
                    palaceWings = wings,
                    githubSignedIn = c.githubSignedIn,
                    hasGitHubRepoScope = c.hasGitHubRepoScope,
                    royalRoadSignedIn = c.royalRoadSignedIn,
                    enabledSources = c.enabledSources,
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
            combine(paginatorView, controls, _palaceWings) { view, c, wings ->
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
                    palaceFilter = c.palaceFilter,
                    palaceWings = wings,
                    githubSignedIn = c.githubSignedIn,
                    hasGitHubRepoScope = c.hasGitHubRepoScope,
                    royalRoadSignedIn = c.royalRoadSignedIn,
                    enabledSources = c.enabledSources,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState())

    fun selectSource(key: BrowseSourceKey) {
        if (_sourceKey.value == key) return
        _sourceKey.value = key
        // If the previously-selected tab isn't supported on the new
        // source (e.g. user was on BestRated/Search on RR and switches
        // to GitHub, or MyRepos/Gists when not on GitHub), snap to
        // Popular so the screen has something sensible to render.
        if (_tab.value !in key.supportedTabs(githubSignedIn.value)) {
            _tab.value = BrowseTab.Popular
        }
        // Per-source filter shapes don't translate, so clear the
        // *other* sources' filters on switch. Always clear the query
        // so a half-typed term doesn't leak across sources.
        _query.value = ""
        when (key) {
            BrowseSourceKey.RoyalRoad -> {
                _githubFilter.value = GitHubSearchFilter()
                _palaceFilter.value = MemPalaceFilter()
            }
            BrowseSourceKey.GitHub -> {
                _filter.value = BrowseFilter()
                _palaceFilter.value = MemPalaceFilter()
            }
            BrowseSourceKey.MemPalace -> {
                _filter.value = BrowseFilter()
                _githubFilter.value = GitHubSearchFilter()
                // Lazy-load the wing list on first switch — keeps the
                // daemon round-trip off the cold-start path for users
                // who never visit the palace tab.
                ensurePalaceWingsLoaded()
            }
            BrowseSourceKey.Rss -> {
                // RSS has no per-source filter; clear sibling filters
                // so a switch from RR/GitHub/Palace doesn't carry
                // stale state into a future switch back.
                _filter.value = BrowseFilter()
                _githubFilter.value = GitHubSearchFilter()
                _palaceFilter.value = MemPalaceFilter()
            }
            BrowseSourceKey.Epub -> {
                // Local EPUB: same as RSS — no per-source filter, clear siblings.
                _filter.value = BrowseFilter()
                _githubFilter.value = GitHubSearchFilter()
                _palaceFilter.value = MemPalaceFilter()
            }
            BrowseSourceKey.Outline -> {
                _filter.value = BrowseFilter()
                _githubFilter.value = GitHubSearchFilter()
                _palaceFilter.value = MemPalaceFilter()
            }
            BrowseSourceKey.Gutenberg -> {
                // PG has no per-source filter in v1 — its catalog
                // is queried directly via Search/Popular/NewReleases.
                // Clear sibling filters so a future switch back to RR/GH
                // doesn't pick up leftover state.
                _filter.value = BrowseFilter()
                _githubFilter.value = GitHubSearchFilter()
                _palaceFilter.value = MemPalaceFilter()
            }
            BrowseSourceKey.Ao3 -> {
                // AO3 has no per-source filter sheet in v1 — the
                // fandom picker rides on the genre row (which
                // belongs to the source itself, not a filter
                // overlay). Clear sibling filters so a future
                // switch back to RR/GH doesn't pick up leftover
                // state.
            BrowseSourceKey.StandardEbooks -> {
                // SE has no per-source filter in v1 — same shape as PG:
                // tab-driven Popular/NewReleases + free-form Search.
                // (Subject-by-genre exists upstream and could land later
                // as a filter sheet; v1 keeps the surface symmetrical
                // with Gutenberg.)
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
            // Empty list on failure — the sheet renders an "All" chip
            // only and the user sees no wing options, which matches
            // the "palace unreachable / unconfigured" empty state.
            // Reset the latch on failure so a subsequent switch retries.
            val wings = runCatching { repo.genres(BrowseSourceKey.MemPalace.sourceId) }
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

    /** Called by the grid when the user nears the end of the visible
     *  list. Idempotent — the paginator's mutex collapses concurrent
     *  calls. */
    fun loadMore() {
        viewModelScope.launch { paginator.value?.loadNext() }
    }

    // ─── RSS feed management (#247) ────────────────────────────────────
    // Moved from SettingsViewModel as part of the "whole move" of feed
    // add/remove out of Settings into a FAB-launched sheet on Browse →
    // RSS. The underlying repository surface didn't change; we just
    // changed where the user reaches it. SettingsScreen still owns the
    // RSS source on/off toggle (that's a "source enable" call, not
    // "feed management").

    /** Hot stream of currently-subscribed feed URLs. Drives the
     *  removable-feeds list in the Browse RSS management sheet. */
    val rssSubscriptions: StateFlow<List<String>> =
        settings.rssSubscriptions
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Hot stream of curated suggested feeds. Backed by the
     *  jphein/storyvox-feeds registry (#246) with a baked-in seed list
     *  so the sheet's "Suggested" section has something to render
     *  before the network fetch resolves. */
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

/** Tabs on the GitHub source that require an OAuth session.
 *  Used by the auto-snap watcher to bounce the user off the tab
 *  cleanly when their session goes away (sign-out, expired). */
private val AUTH_ONLY_GH_TABS: Set<BrowseTab> = setOf(BrowseTab.MyRepos, BrowseTab.Starred, BrowseTab.Gists)

/** 5-arg shoehorn so the inner [combine] stays within the overload
 *  arity. Folded back into [ControlsView] (or consumed directly by
 *  [resolveSource]) at the next combine layer. */
private data class ResolveTuple(
    val sourceKey: BrowseSourceKey,
    val tab: BrowseTab,
    val q: String,
    val filter: BrowseFilter,
    val ghFilter: GitHubSearchFilter,
)

private fun resolveSource(
    sourceKey: BrowseSourceKey,
    tab: BrowseTab,
    q: String,
    filter: BrowseFilter,
    githubFilter: GitHubSearchFilter,
    palaceFilter: MemPalaceFilter,
    githubSignedIn: Boolean,
    royalRoadSignedIn: Boolean,
): BrowseSource? = when (sourceKey) {
    // GitHub: filter takes priority over tab. When filter is active OR
    // user is on Search with a typed query, route to FilteredGitHub so
    // the qualifier-laden query lands. Otherwise the tab decides
    // (Popular/NewReleases/MyRepos/Gists/Search). MyRepos and Gists
    // are sign-in-gated; a stale tab value when the user has signed
    // out maps to null (BrowseScreen will see the tab missing from
    // supportedTabs and re-snap). Search-with-blank-query stays null
    // so the screen renders SearchHint. The GitHub filter doesn't
    // apply to Gists (no `/search/gists` endpoint shape matches the
    // repo-search qualifiers) so an active filter just means the tab
    // still pages through gists.
    BrowseSourceKey.GitHub -> when {
        // Auth-only tabs short-circuit ahead of the filter check —
        // search qualifiers don't apply to `/user/{repos,starred,gists}`.
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
    // Issue #241 — soft-gate on RR sign-in. Listing tabs
    // (Popular / NewReleases / BestRated / any filter-active tab)
    // return null when the user is not signed in to RR; the screen
    // renders a sign-in CTA empty state instead of firing an
    // anonymous request. Search and Add-by-URL stay open: they
    // target specific URLs the user already knows, which is
    // structurally distinct from anonymous browsing.
    BrowseSourceKey.RoyalRoad -> when {
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
    // MemPalace: when a wing is selected, route through ByGenre so the
    // daemon scopes the listing to that wing (overrides tab). Wing-less
    // requests fall through to the tab-driven Popular/NewReleases pair.
    // Spec P1 surfaces the daemon's /search endpoint behind a feature
    // flag; today Search is hidden on MemPalace.
    BrowseSourceKey.MemPalace -> when {
        palaceFilter.wing != null -> BrowseSource.ByGenre(palaceFilter.wing)
        tab == BrowseTab.Popular -> BrowseSource.Popular
        tab == BrowseTab.NewReleases -> BrowseSource.NewReleases
        else -> null
    }
    // RSS (#236): Search filters by feed title (handled by RssSource);
    // Popular and NewReleases both list the user's subscriptions
    // (RssSource sorts NewReleases by most-recent-item).
    BrowseSourceKey.Rss -> when {
        tab == BrowseTab.Search -> if (q.isBlank()) BrowseSource.NewReleases else BrowseSource.Search(q)
        tab == BrowseTab.Popular -> BrowseSource.Popular
        tab == BrowseTab.NewReleases -> BrowseSource.NewReleases
        else -> null
    }
    // Local EPUB (#235): same shape as RSS — Popular = full list,
    // Search filters by filename. No NewReleases concept (files are
    // static). Library tab is hidden via supportedTabs.
    BrowseSourceKey.Epub -> when {
        tab == BrowseTab.Search -> if (q.isBlank()) BrowseSource.Popular else BrowseSource.Search(q)
        tab == BrowseTab.Popular -> BrowseSource.Popular
        else -> null
    }
    // Outline (#245): same shape as Epub — Popular = full collection
    // list, Search filters by collection name.
    BrowseSourceKey.Outline -> when {
        tab == BrowseTab.Search -> if (q.isBlank()) BrowseSource.Popular else BrowseSource.Search(q)
        tab == BrowseTab.Popular -> BrowseSource.Popular
        else -> null
    }
    // Project Gutenberg (#237): Popular hits Gutendex `?sort=popular`;
    // NewReleases hits `?sort=descending` (highest id = newest);
    // Search hits `?search=<term>`. No filter surface in v1 — the
    // catalog's free-form subject strings don't map cleanly to a
    // filter sheet; topic search via the Search tab covers the
    // discovery cases.
    BrowseSourceKey.Gutenberg -> when (tab) {
        BrowseTab.Popular -> BrowseSource.Popular
        BrowseTab.NewReleases -> BrowseSource.NewReleases
        BrowseTab.Search -> if (q.isBlank()) null else BrowseSource.Search(q)
        else -> null
    }
    // AO3 (#381): both tabs route through the same Atom feed
    // (the source's popular() and latestUpdates() return the
    // tag-feed-sorted-by-recency in both cases — AO3 doesn't
    // expose a separate popularity signal without HTML scraping,
    // which v1 opts out of). Search is hidden via supportedTabs.
    BrowseSourceKey.Ao3 -> when (tab) {
        BrowseTab.Popular -> BrowseSource.Popular
        BrowseTab.NewReleases -> BrowseSource.NewReleases
    // Standard Ebooks (#375): Popular hits SE `?sort=popularity`;
    // NewReleases hits `?sort=default` (release date desc); Search
    // hits `?query=<term>`. No filter surface in v1 — same shape as
    // Gutenberg, which the SE source structurally mirrors.
    BrowseSourceKey.StandardEbooks -> when (tab) {
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
