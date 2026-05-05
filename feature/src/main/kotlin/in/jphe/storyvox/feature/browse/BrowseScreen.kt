package `in`.jphe.storyvox.feature.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    Column(modifier = Modifier.fillMaxSize().padding(top = spacing.md)) {
        SecondaryTabRow(selectedTabIndex = state.tab.ordinal) {
            BrowseTab.entries.forEach { tab ->
                Tab(
                    selected = tab == state.tab,
                    onClick = { viewModel.selectTab(tab) },
                    text = { Text(tab.label, style = MaterialTheme.typography.labelLarge) },
                )
            }
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
            state.isLoading && state.items.isEmpty() -> SkeletonRow()
            state.tab == BrowseTab.Search && state.query.isBlank() -> SearchHint()
            else -> LazyRow(
                modifier = Modifier.fillMaxSize().padding(spacing.md),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                items(state.items) { fiction ->
                    Column(
                        modifier = Modifier
                            .size(width = 140.dp, height = 240.dp)
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
                        Text(fiction.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonRow() {
    val spacing = LocalSpacing.current
    LazyRow(
        modifier = Modifier.fillMaxSize().padding(spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        items(4) { FictionCardSkeleton() }
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
