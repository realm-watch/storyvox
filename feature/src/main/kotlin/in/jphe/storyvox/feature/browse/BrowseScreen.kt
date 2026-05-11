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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
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
import `in`.jphe.storyvox.ui.component.cascadeReveal
import `in`.jphe.storyvox.ui.component.ErrorBlock
import `in`.jphe.storyvox.ui.component.friendlyErrorMessage
import `in`.jphe.storyvox.ui.component.ErrorPlacement
import `in`.jphe.storyvox.ui.component.FictionCardSkeleton
import `in`.jphe.storyvox.ui.component.FictionCoverThumb
import `in`.jphe.storyvox.ui.component.fictionMonogram
import `in`.jphe.storyvox.ui.component.MagicSpinner
import `in`.jphe.storyvox.ui.theme.LocalSpacing

@Composable
fun BrowseScreen(
    onOpenFiction: (String) -> Unit,
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    var showFilterSheet by remember { mutableStateOf(false) }

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
                BrowseSourceKey.Outline -> false
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
                        BrowseSourceKey.Outline -> 0
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
