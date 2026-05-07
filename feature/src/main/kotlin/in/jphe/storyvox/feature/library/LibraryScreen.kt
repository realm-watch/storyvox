package `in`.jphe.storyvox.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.data.repository.ContinueListeningEntry
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.FictionCoverThumb
import `in`.jphe.storyvox.ui.component.cascadeReveal
import `in`.jphe.storyvox.ui.theme.LocalSpacing

@Composable
fun LibraryScreen(
    onOpenFiction: (String) -> Unit,
    onOpenReader: (String, String) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val addByUrlState by viewModel.addByUrlState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LibraryUiEvent.OpenFiction -> onOpenFiction(event.fictionId)
                is LibraryUiEvent.OpenReader -> onOpenReader(event.fictionId, event.chapterId)
            }
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = viewModel::showAddByUrl,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add fiction by URL")
            }
        },
    ) { scaffoldPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(scaffoldPadding).padding(top = spacing.md)) {
            if (!state.isLoading && state.fictions.isEmpty() && state.resume == null) {
                EmptyLibrary()
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    contentPadding = PaddingValues(spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    state.resume?.let { resume ->
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            ResumeCard(resume, onResume = viewModel::resume)
                        }
                    }
                    itemsIndexed(state.fictions, key = { _, item -> item.id }) { index, fiction ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .cascadeReveal(index = index, key = fiction.id),
                            horizontalAlignment = Alignment.Start,
                            verticalArrangement = Arrangement.spacedBy(spacing.xs),
                        ) {
                            FictionCoverThumb(
                                coverUrl = fiction.coverUrl,
                                title = fiction.title,
                                authorInitial = fiction.author.firstOrNull()?.uppercaseChar() ?: '?',
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.openFiction(fiction.id) },
                            )
                            Text(
                                fiction.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                            )
                            if (fiction.author.isNotBlank()) {
                                Text(
                                    fiction.author,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    AddByUrlSheet(
        state = addByUrlState,
        onSubmit = viewModel::submitAddByUrl,
        onDismiss = viewModel::dismissAddByUrl,
    )
}

@Composable
private fun ResumeCard(entry: ContinueListeningEntry, onResume: () -> Unit) {
    val spacing = LocalSpacing.current
    val fraction = entry.progressFraction()

    Card(
        modifier = Modifier.fillMaxWidth().height(132.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(spacing.md).fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            FictionCoverThumb(
                coverUrl = entry.fiction.coverUrl,
                title = entry.fiction.title,
                authorInitial = entry.fiction.author.firstOrNull()?.uppercaseChar() ?: '?',
                modifier = Modifier.size(width = 68.dp, height = 100.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Resume", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(entry.fiction.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    "Ch. ${entry.chapter.index} · ${entry.chapter.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = spacing.xxs),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Text(
                        "${(fraction * 100).toInt()}% through chapter ${entry.chapter.index}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            BrassButton(
                label = "Resume",
                onClick = onResume,
                variant = BrassButtonVariant.Primary,
            )
        }
    }
}

@Composable
private fun EmptyLibrary() {
    val spacing = LocalSpacing.current
    Box(
        modifier = Modifier.fillMaxSize().padding(spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                "Your library is empty",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Open Browse to add fictions",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Estimate progress through the chapter from `charOffset` and `wordCount`.
 * Royal Road averages ~5 chars per word incl. spacing; null if word count is missing.
 */
private fun ContinueListeningEntry.progressFraction(): Float? {
    val words = chapter.wordCount ?: return null
    if (words <= 0) return null
    val estimatedChars = words * 5f
    return (charOffset / estimatedChars).coerceIn(0f, 1f)
}
