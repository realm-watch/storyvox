package `in`.jphe.storyvox.feature.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.api.BrowseFilter
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.cascadeReveal
import `in`.jphe.storyvox.ui.component.ErrorBlock
import `in`.jphe.storyvox.ui.component.friendlyErrorMessage
import `in`.jphe.storyvox.ui.component.ErrorPlacement
import `in`.jphe.storyvox.ui.component.FictionCardSkeleton
import `in`.jphe.storyvox.ui.component.FictionCoverThumb
import `in`.jphe.storyvox.ui.component.fictionMonogram
import `in`.jphe.storyvox.ui.component.MagicSkeletonTile
import `in`.jphe.storyvox.ui.component.MagicSpinner
import `in`.jphe.storyvox.ui.theme.LocalSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    onOpenFiction: (String) -> Unit,
    /** Issue #241 — navigates to the Royal Road sign-in WebView. Surfaced
     *  on the listing tabs (Popular / NewReleases / BestRated) when the
     *  user is not signed in to RR; Search keeps working anonymously. */
    onOpenRoyalRoadSignIn: () -> Unit,
    onOpenSettings: () -> Unit = {},
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    var showFilterSheet by remember { mutableStateOf(false) }
    /** Issue #247 — RSS feed management moved out of Settings into a
     *  FAB-launched sheet visible only when sourceKey=Rss. */
    var showRssManageSheet by remember { mutableStateOf(false) }

    // #328 — see LibraryScreen.kt; hoist distinctBy out of the grid
    // builder so allocations happen once per state.items change instead
    // of every recomposition, and log when duplicates appear so the
    // upstream RR / GitHub paginator that emitted them can be traced.
    val dedupedItems = remember(state.items) {
        val out = state.items.distinctBy { it.id }
        val dropped = state.items.size - out.size
        if (dropped > 0) {
            android.util.Log.w(
                "storyvox",
                "BrowseScreen: dropped $dropped duplicate fiction id(s) (size ${state.items.size} -> ${out.size}) — see #328",
            )
        }
        out
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Browse", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { scaffoldPadding ->
    Box(modifier = Modifier.fillMaxSize().padding(scaffoldPadding)) {
    Column(modifier = Modifier.fillMaxSize().padding(top = spacing.md)) {
        // Top-level source picker. Switches the multibinding lookup in
        // FictionRepository between Royal Road and GitHub. Tabs and the
        // filter sheet rebind to whatever the chosen source supports.
        BrowseSourcePicker(
            selected = state.sourceKey,
            onSelect = viewModel::selectSource,
            enabledKeys = state.enabledSources,
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md),
        )

        val supportedTabs = remember(state.sourceKey, state.githubSignedIn) {
            state.sourceKey.supportedTabs(githubSignedIn = state.githubSignedIn)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Scrollable so 'Popular' / 'Best Rated' don't truncate when
            // sharing the row with the filter funnel on narrow phones.
            // edgePadding=0 keeps the active-tab indicator flush with the
            // left edge to match the tighter tab look from the previous
            // SecondaryTabRow.
            SecondaryScrollableTabRow(
                selectedTabIndex = supportedTabs.indexOf(state.tab).coerceAtLeast(0),
                modifier = Modifier.weight(1f),
                edgePadding = 0.dp,
            ) {
                supportedTabs.forEach { tab ->
                    Tab(
                        selected = tab == state.tab,
                        onClick = { viewModel.selectTab(tab) },
                        // Issues #258 + #270 — render Search as an icon-only
                        // tab so the row fits on Flip3 (1080px inner display
                        // and similar narrow phones). When "Search" was a
                        // text tab between Best Rated and the filter funnel,
                        // it got clipped to 'S' and dragged the row into
                        // horizontal-scroll territory, which then clipped
                        // 'Popular' to 'ar' on the other edge. Magnifying-
                        // glass is the universal affordance for search and
                        // the contentDescription keeps a11y intact.
                        icon = if (tab == BrowseTab.Search) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = "Search",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else null,
                        text = if (tab == BrowseTab.Search) null else {
                            {
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        },
                    )
                }
            }
            // Filter sheet is per-source: RR has its `/fictions/search`
            // form, GitHub has the `/search/repositories` qualifier set,
            // MemPalace has a wing chooser. RSS / EPUB / Outline have
            // no filter sheet (configuration lives in Settings) — hide
            // the button entirely for those sources, since the click
            // handler is a documented no-op (`showFilterSheet = false`
            // at the bottom of the file) and presenting a tappable icon
            // that does nothing reads as a bug to users (phone audit
            // pass 2).
            val filterableSource = when (state.sourceKey) {
                BrowseSourceKey.RoyalRoad,
                BrowseSourceKey.GitHub,
                BrowseSourceKey.MemPalace -> true
                BrowseSourceKey.Rss,
                BrowseSourceKey.Epub,
                BrowseSourceKey.Outline,
                BrowseSourceKey.Gutenberg,
                // AO3 (#381) — no filter sheet in v1; the fandom row
                // belongs to the source's genre picker. Falls into
                // the non-filterable branch like the other catalog-
                // driven sources.
                BrowseSourceKey.Ao3 -> false
                BrowseSourceKey.StandardEbooks -> false
                BrowseSourceKey.Wikipedia -> false
                // #376 — Wikisource has no filter sheet; the curated
                // landing IS the filter (Category:Validated_texts) and
                // free-form search covers the rest.
                BrowseSourceKey.Wikisource -> false
                // #374 — KVMR is a single-fiction audio backend; no
                // filter surface (you either tune in or you don't).
                BrowseSourceKey.Kvmr -> false
                // #233 — Notion v1 has no filter sheet; the database
                // configured in Settings IS the filter. A server-side
                // filter body via /databases/{id}/query is the
                // follow-up.
                BrowseSourceKey.Notion -> false
                // #379 — Hacker News has no filter sheet; the catalog
                // (Top / Ask / Show) selection lives in the Browse tab
                // shape itself, not a sidecar filter.
                BrowseSourceKey.HackerNews -> false
            }
            if (filterableSource) {
                FilterButton(
                    activeCount = when (state.sourceKey) {
                        BrowseSourceKey.RoyalRoad -> state.filter.activeCount()
                        BrowseSourceKey.GitHub -> state.githubFilter.activeCount()
                        // #191 — single dimension (wing) so badge counts at
                        // most 1.
                        BrowseSourceKey.MemPalace -> if (state.palaceFilter.wing != null) 1 else 0
                        // Gated by `filterableSource` above; the
                        // non-filterable branches are unreachable here
                        // but Kotlin still wants exhaustive coverage.
                        BrowseSourceKey.Rss,
                        BrowseSourceKey.Epub,
                        BrowseSourceKey.Outline,
                        BrowseSourceKey.Gutenberg,
                        BrowseSourceKey.Ao3 -> 0
                        BrowseSourceKey.StandardEbooks -> 0
                        BrowseSourceKey.Wikipedia -> 0
                        BrowseSourceKey.Wikisource -> 0
                        BrowseSourceKey.Kvmr -> 0
                        BrowseSourceKey.Notion -> 0
                        BrowseSourceKey.HackerNews -> 0
                    },
                    onClick = { showFilterSheet = true },
                )
            }
        }

        // Active wing hint — surface the selected wing prominently
        // below the tab row so users always know the listing is scoped.
        // Tapping the chip clears the wing (one-tap reset path) without
        // having to reopen the sheet.
        if (state.sourceKey == BrowseSourceKey.MemPalace && state.palaceFilter.wing != null) {
            ActiveWingChip(
                wing = state.palaceFilter.wing!!,
                onClear = { viewModel.resetPalaceFilter() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.md, vertical = spacing.xs),
            )
        }

        if (state.tab == BrowseTab.Search) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                label = { Text("Search ${state.sourceKey.displayName}") },
                modifier = Modifier.fillMaxWidth().padding(spacing.md),
                singleLine = true,
            )
        }

        when {
            // Issue #241 — RR listings are gated on sign-in. CTA sits ahead
            // of the SkeletonGrid branch so the user never sees a loading
            // shimmer for a request the resolver wasn't going to fire.
            // Search stays open (the resolver in BrowseViewModel exempts
            // it), so the CTA is suppressed when the user is on Search.
            state.sourceKey == BrowseSourceKey.RoyalRoad &&
                !state.royalRoadSignedIn &&
                state.tab != BrowseTab.Search -> RoyalRoadSignedOutCta(onOpenRoyalRoadSignIn)
            state.isLoading && state.items.isEmpty() -> SkeletonGrid()
            state.tab == BrowseTab.Search && state.query.isBlank() && !state.isFilterActive -> SearchHint(state.sourceKey)
            // First-load failure with no cached items: full-screen error.
            // Retry triggers viewModel.loadMore() which the paginator
            // resolves to the same page that failed.
            state.error != null && state.items.isEmpty() -> ErrorBlock(
                title = "The realm is unreachable",
                // #171 — friendlyErrorMessage maps the raw exception
                // string (HTTP 0 timeouts, IOExceptions, "host not
                // configured") to user copy that doesn't leak the
                // OkHttp stack into the UI.
                message = friendlyErrorMessage(state.error),
                onRetry = { viewModel.loadMore() },
                placement = ErrorPlacement.FullScreen,
            )
            else -> {
                // Hoist the grid state so we can watch the last-visible
                // index and trigger viewModel.loadMore() near the end.
                val gridState = rememberLazyGridState()
                // Reset scroll to top whenever the source tuple changes
                // (tab switch, new search, filter applied). The paginator
                // hands us a fresh items list anyway; we just nudge the
                // viewport back to the start so the user doesn't land
                // mid-scroll into a different listing.
                LaunchedEffect(state.sourceKey, state.tab, state.query, state.filter) {
                    if (gridState.firstVisibleItemIndex != 0) {
                        gridState.scrollToItem(0)
                    }
                }
                val nearEnd by remember(state.items.size, state.hasMore) {
                    derivedStateOf {
                        if (!state.hasMore) return@derivedStateOf false
                        val info = gridState.layoutInfo
                        val total = info.totalItemsCount
                        if (total == 0) return@derivedStateOf false
                        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                        // Trigger when within ~6 items of the end —
                        // roughly one row at 6-col, three rows at 2-col.
                        lastVisible >= total - 6
                    }
                }
                // rememberUpdatedState pins the latest state snapshot so
                // the long-lived collector below reads current values
                // without restarting on every state change.
                val currentState by rememberUpdatedState(state)
                // Edge-trigger on nearEnd: distinctUntilChanged means we
                // only fire on a transition false→true. Without this a
                // failed page (no items added, isAppending flips back
                // false while nearEnd stays true) would tight-loop the
                // network. User must scroll back+forward to retry — the
                // safe default.
                LaunchedEffect(gridState) {
                    snapshotFlow { nearEnd }
                        .distinctUntilChanged()
                        .filter { it }
                        .collect {
                            val s = currentState
                            if (!s.isAppending && !s.isLoading) viewModel.loadMore()
                        }
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    // Tail/refresh error while we still have cached items —
                    // surface as a banner so users keep seeing what they were
                    // browsing. Retry path is the same loadMore() the
                    // paginator already wires to scroll-near-end.
                    if (state.error != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ErrorBlock(
                                title = "Couldn't refresh",
                                message = state.error ?: "We couldn't reach Royal Road.",
                                onRetry = { viewModel.loadMore() },
                                placement = ErrorPlacement.Banner,
                            )
                        }
                    }
                    itemsIndexed(dedupedItems, key = { _, item -> item.id }) { index, fiction ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .cascadeReveal(index = index, key = fiction.id)
                                .clickable { onOpenFiction(fiction.id) },
                            verticalArrangement = Arrangement.spacedBy(spacing.xs),
                        ) {
                            FictionCoverThumb(
                                coverUrl = fiction.coverUrl,
                                title = fiction.title,
                                monogram = fictionMonogram(fiction.author, fiction.title),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            // Issue #272 — titles longer than 2 lines were silently
                            // cut mid-token ("…[Vols", "…(OP", "the I"), reading as
                            // broken data rather than UI truncation. Set
                            // overflow = Ellipsis so the cut becomes "…" and the
                            // user knows the text continues. Author already has
                            // the same treatment below (maxLines = 1) but no
                            // overflow set; same fix.
                            Text(
                                fiction.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            if (fiction.author.isNotBlank()) {
                                Text(
                                    fiction.author,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (state.isAppending) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(spacing.lg),
                                contentAlignment = Alignment.Center,
                            ) {
                                // Brass MagicSpinner while the next page is being
                                // fetched — matches the rest of the realm's loading
                                // vocabulary instead of the cool-grey M3 default.
                                MagicSpinner(modifier = Modifier.size(32.dp))
                            }
                        }
                    } else if (!state.hasMore && state.items.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "End of list",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(spacing.lg),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }

    // Issue #247 — FAB for RSS feed management. Only visible on the
    // RSS source; other backends manage subscriptions elsewhere
    // (Settings folder picker for EPUB, host config for Outline,
    // sign-in for RR/GitHub).
    if (state.sourceKey == BrowseSourceKey.Rss) {
        FloatingActionButton(
            onClick = { showRssManageSheet = true },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(spacing.lg),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add RSS feed")
        }
    }
    }  // Box
    }  // Scaffold

    if (showRssManageSheet) {
        BrowseRssManageSheet(
            viewModel = viewModel,
            onDismiss = { showRssManageSheet = false },
        )
    }

    if (showFilterSheet) {
        when (state.sourceKey) {
            BrowseSourceKey.RoyalRoad -> BrowseFilterSheet(
                filter = state.filter,
                onApply = { applied ->
                    viewModel.setFilter(applied)
                    showFilterSheet = false
                },
                onReset = {
                    viewModel.resetFilter()
                    showFilterSheet = false
                },
                onDismiss = { showFilterSheet = false },
            )
            BrowseSourceKey.GitHub -> GitHubFilterSheet(
                filter = state.githubFilter,
                onApply = { applied ->
                    viewModel.setGitHubFilter(applied)
                    showFilterSheet = false
                },
                onReset = {
                    viewModel.resetGitHubFilter()
                    showFilterSheet = false
                },
                onDismiss = { showFilterSheet = false },
                showVisibilityChips = state.hasGitHubRepoScope,
            )
            BrowseSourceKey.MemPalace -> MemPalaceFilterSheet(
                filter = state.palaceFilter,
                wings = state.palaceWings,
                onApply = { applied ->
                    viewModel.setPalaceFilter(applied)
                    showFilterSheet = false
                },
                onReset = {
                    viewModel.resetPalaceFilter()
                    showFilterSheet = false
                },
                onDismiss = { showFilterSheet = false },
            )
            // #236 — RSS has no filter UI (the subscription list is
            // managed in Settings, not a Browse-side sheet).
            BrowseSourceKey.Rss -> { showFilterSheet = false }
            // #235 — EPUB also has no filter UI (folder picker is in
            // Settings).
            BrowseSourceKey.Epub -> { showFilterSheet = false }
            BrowseSourceKey.Outline -> { showFilterSheet = false }
            // #237 — Gutenberg has no filter sheet; topic-search via
            // the Search tab covers the discovery cases.
            BrowseSourceKey.Gutenberg -> { showFilterSheet = false }
            // #381 — AO3 has no filter sheet; the curated fandom
            // list rides on the genre row.
            BrowseSourceKey.Ao3 -> { showFilterSheet = false }
            // #375 — Standard Ebooks mirrors Gutenberg's surface: no
            // filter sheet in v1, free-form Search covers discovery.
            BrowseSourceKey.StandardEbooks -> { showFilterSheet = false }
            // #377 — Wikipedia has no filter sheet; the language code
            // lives in Settings and topic-search via opensearch covers
            // discovery.
            BrowseSourceKey.Wikipedia -> { showFilterSheet = false }
            // #376 — Wikisource has no filter sheet; the curated landing
            // (Category:Validated_texts) IS the filter scope.
            BrowseSourceKey.Wikisource -> { showFilterSheet = false }
            // #374 — KVMR is a single-fiction audio backend; no filter
            // sheet (the station is the station).
            BrowseSourceKey.Kvmr -> { showFilterSheet = false }
            // #233 — Notion v1 has no filter sheet; the configured
            // database id IS the filter scope.
            BrowseSourceKey.Notion -> { showFilterSheet = false }
            // #379 — Hacker News has no filter sheet in v1; the Top
            // landing is the surface and Search is the discovery path.
            BrowseSourceKey.HackerNews -> { showFilterSheet = false }
        }
    }
}

@Composable
private fun FilterButton(activeCount: Int, onClick: () -> Unit) {
    if (activeCount > 0) {
        BadgedBox(
            badge = {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) { Text(activeCount.toString()) }
            },
        ) {
            IconButton(onClick = onClick) {
                Icon(Icons.Outlined.FilterAlt, contentDescription = "Filter")
            }
        }
    } else {
        IconButton(onClick = onClick) {
            Icon(Icons.Outlined.FilterAlt, contentDescription = "Filter")
        }
    }
}

@Composable
private fun SkeletonGrid() {
    val spacing = LocalSpacing.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        items(12) { FictionCardSkeleton(modifier = Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun SearchHint(sourceKey: BrowseSourceKey) {
    val spacing = LocalSpacing.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Text(
                "Search by title",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Issue #271 — per-source empty-state subtitle. The old copy
            // hard-coded "across Royal Road" even when RSS/GitHub/etc. was
            // the selected source. Pick the right phrase from the source
            // key — each source has its own corpus shape (RSS = "your
            // subscribed feeds", GitHub = "indexed repositories", etc.).
            Text(
                searchHintForSource(sourceKey),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

/** Issue #271 — per-source subtitle for the Search empty state. */
private fun searchHintForSource(sourceKey: BrowseSourceKey): String = when (sourceKey) {
    BrowseSourceKey.RoyalRoad -> "Find fictions across Royal Road"
    BrowseSourceKey.GitHub -> "Search indexed GitHub repositories"
    BrowseSourceKey.MemPalace -> "Search your MemPalace knowledge base"
    BrowseSourceKey.Rss -> "Search your subscribed feeds"
    BrowseSourceKey.Epub -> "Search your local EPUB library"
    BrowseSourceKey.Outline -> "Search your Outline notes"
    BrowseSourceKey.Gutenberg -> "Search Project Gutenberg's 70,000+ public-domain books"
    // #381 — AO3 has no Search tab in v1 (the AO3 search endpoint is
    // HTML-only and we don't scrape). This hint is unreachable
    // unless a future iteration re-adds the tab, but Kotlin still
    // wants exhaustive coverage. Keep the copy ready for that day.
    BrowseSourceKey.Ao3 -> "Search AO3 by tag, fandom, or character"
    BrowseSourceKey.StandardEbooks -> "Search Standard Ebooks' hand-curated public-domain classics"
    BrowseSourceKey.Wikipedia -> "Search Wikipedia — narrate any article"
    BrowseSourceKey.Wikisource -> "Search Wikisource — transcribed public-domain texts"
    BrowseSourceKey.Kvmr -> "Tune in to KVMR — live community radio from Nevada City"
    BrowseSourceKey.Notion -> "Search your configured Notion database"
    BrowseSourceKey.HackerNews -> "Search Hacker News stories (Algolia-backed full-text)"
}

private val BrowseTab.label: String
    get() = when (this) {
        BrowseTab.Popular -> "Popular"
        BrowseTab.NewReleases -> "New"
        BrowseTab.BestRated -> "Best Rated"
        BrowseTab.Search -> "Search"
        BrowseTab.MyRepos -> "My Repos"
        BrowseTab.Starred -> "Starred"
        BrowseTab.Gists -> "Gists"
    }

/**
 * Two-segment source picker pinned above the tab row. Material 3
 * `SingleChoiceSegmentedButtonRow` with brass-tinted selection so it
 * reads as part of the realm aesthetic and not a generic Material
 * widget. Adding a new source is one more `forEach` entry once
 * `BrowseSourceKey` grows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseSourcePicker(
    selected: BrowseSourceKey,
    onSelect: (BrowseSourceKey) -> Unit,
    enabledKeys: Set<BrowseSourceKey>,
    modifier: Modifier = Modifier,
) {
    // Filter to user-enabled sources (#221) — when a backend is toggled
    // off in Settings, drop it from the picker entirely so Browse stops
    // trying to talk to it. If the user disables their currently-selected
    // source, BrowseViewModel snaps back to the first enabled one.
    val keys = remember(enabledKeys) {
        BrowseSourceKey.entries.filter { it in enabledKeys }
    }
    if (keys.isEmpty()) return
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        keys.forEachIndexed { index, key ->
            SegmentedButton(
                selected = key == selected,
                onClick = { onSelect(key) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = keys.size,
                ),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Text(key.displayName, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Hint chip surfaced under the tab row when MemPalace browse is scoped
 * to a wing (#191). The leading "Wing:" label keeps the source of the
 * filter unambiguous (vs a bare wing name); the trailing close icon
 * gives one-tap reset without re-opening the filter sheet.
 */
@Composable
private fun ActiveWingChip(
    wing: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = onClear,
        label = { Text("Wing: ${prettifyWing(wing)}") },
        trailingIcon = {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Clear wing filter",
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
        modifier = modifier,
    )
}

/**
 * Issue #241 — empty state shown on the Royal Road listing tabs
 * (Popular / NewReleases / BestRated / filter-active) when the user
 * is not signed in. Mirrors FollowsScreen's `SignedOutEmpty` rhythm
 * (sigil tile + titleMedium primary headline + bodyMedium body +
 * brass primary CTA) so the two surfaces read as part of the same
 * family — the same brass voice asking the user to authorize before
 * we hit RR's listing endpoints on their behalf.
 *
 * The Search tab is intentionally suppressed in BrowseScreen's
 * `when` branch — search and Add-by-URL keep working anonymously per
 * the #241 spec, since they target specific URLs the user already
 * knows (not anonymous discovery of the catalog).
 */
@Composable
private fun RoyalRoadSignedOutCta(onOpenSignIn: () -> Unit) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.fillMaxSize().padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MagicSkeletonTile(
            modifier = Modifier.size(width = 160.dp, height = 220.dp),
            shape = MaterialTheme.shapes.medium,
            glyphSize = 80.dp,
        )
        Spacer(Modifier.height(spacing.lg))
        Text(
            "Sign in to browse Royal Road",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            "Storyvox uses your Royal Road session to fetch listings on your behalf — a logged-in reader, not an anonymous one. Search and paste-URL still work without signing in.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.lg))
        BrassButton(
            label = "Sign in to Royal Road",
            onClick = onOpenSignIn,
            variant = BrassButtonVariant.Primary,
        )
    }
}

/** Number of independent filter knobs the user has actively set. */
private fun BrowseFilter.activeCount(): Int {
    var n = 0
    if (tagsInclude.isNotEmpty()) n++
    if (tagsExclude.isNotEmpty()) n++
    if (statuses.isNotEmpty()) n++
    if (warningsRequire.isNotEmpty()) n++
    if (warningsExclude.isNotEmpty()) n++
    if (type != `in`.jphe.storyvox.feature.api.UiFictionType.All) n++
    if (minPages != null || maxPages != null) n++
    if (minRating != null || maxRating != null) n++
    return n
}
