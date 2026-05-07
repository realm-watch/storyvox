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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
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
import `in`.jphe.storyvox.ui.component.FictionCardSkeleton
import `in`.jphe.storyvox.ui.component.FictionCoverThumb
import `in`.jphe.storyvox.ui.theme.LocalSpacing

@Composable
fun BrowseScreen(
    onOpenFiction: (String) -> Unit,
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    var showFilterSheet by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(top = spacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SecondaryTabRow(
                selectedTabIndex = state.tab.ordinal,
                modifier = Modifier.weight(1f),
            ) {
                BrowseTab.entries.forEach { tab ->
                    Tab(
                        selected = tab == state.tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(tab.label, style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
            FilterButton(
                activeCount = state.filter.activeCount(),
                onClick = { showFilterSheet = true },
            )
        }

        if (state.tab == BrowseTab.Search) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                label = { Text("Search Royal Road") },
                modifier = Modifier.fillMaxWidth().padding(spacing.md),
                singleLine = true,
            )
        }

        when {
            state.isLoading && state.items.isEmpty() -> SkeletonGrid()
            state.tab == BrowseTab.Search && state.query.isBlank() && !state.isFilterActive -> SearchHint()
            else -> {
                // Hoist the grid state so we can watch the last-visible
                // index and trigger viewModel.loadMore() near the end.
                val gridState = rememberLazyGridState()
                // Reset scroll to top whenever the source tuple changes
                // (tab switch, new search, filter applied). The paginator
                // hands us a fresh items list anyway; we just nudge the
                // viewport back to the start so the user doesn't land
                // mid-scroll into a different listing.
                LaunchedEffect(state.tab, state.query, state.filter) {
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
                    items(state.items, key = { it.id }) { fiction ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenFiction(fiction.id) },
                            verticalArrangement = Arrangement.spacedBy(spacing.xs),
                        ) {
                            FictionCoverThumb(
                                coverUrl = fiction.coverUrl,
                                title = fiction.title,
                                authorInitial = fiction.author.firstOrNull()?.uppercaseChar() ?: '?',
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(fiction.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            if (fiction.author.isNotBlank()) {
                                Text(fiction.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                    if (state.isAppending) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(spacing.lg),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(32.dp))
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
        BrowseFilterSheet(
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
private fun SearchHint() {
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
            Text(
                "Find fictions across Royal Road",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

private val BrowseTab.label: String
    get() = when (this) {
        BrowseTab.Popular -> "Popular"
        BrowseTab.NewReleases -> "New"
        BrowseTab.BestRated -> "Best Rated"
        BrowseTab.Search -> "Search"
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
