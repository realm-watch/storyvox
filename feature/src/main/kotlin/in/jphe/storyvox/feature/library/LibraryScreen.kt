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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import `in`.jphe.storyvox.ui.component.BrassProgressBar
import `in`.jphe.storyvox.ui.component.FictionCoverThumb
import `in`.jphe.storyvox.ui.component.fictionMonogram
import `in`.jphe.storyvox.ui.component.MagicSkeletonTile
import `in`.jphe.storyvox.ui.component.cascadeReveal
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.style.TextAlign

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenFiction: (String) -> Unit,
    onOpenReader: (String, String) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val addByUrlState by viewModel.addByUrlState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    // #328 — dedupe defensively before the LazyVerticalGrid sees the list.
    // Compose enforces unique item keys; duplicates throw and crash the
    // activity. Hoisted out of the grid builder via remember so the
    // distinctBy pass runs once per state.fictions change instead of on
    // every recomposition. Logs at warn level when duplicates are dropped
    // so the underlying source can be traced — silent-by-default would
    // hide the upstream bug we're guarding around.
    val dedupedFictions = androidx.compose.runtime.remember(state.fictions) {
        val out = state.fictions.distinctBy { it.id }
        val dropped = state.fictions.size - out.size
        if (dropped > 0) {
            android.util.Log.w(
                "storyvox",
                "LibraryScreen: dropped $dropped duplicate fiction id(s) (size ${state.fictions.size} -> ${out.size}) — see #328",
            )
        }
        out
    }

    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LibraryUiEvent.OpenFiction -> onOpenFiction(event.fictionId)
                is LibraryUiEvent.OpenReader -> onOpenReader(event.fictionId, event.chapterId)
            }
        }
    }

    Scaffold(
        // Issue #255 — Library used to fade straight from the system status
        // bar into a Resume card with no header — no 'Library' title, no
        // identity. CenterAlignedTopAppBar gives the screen a hard
        // identifier (matches Material 3 standard for home tabs) and a
        // place to surface search/sort/filter actions later. For now the
        // bar is bare title-only; the issue's deeper ask (search +
        // sort/filter affordances) needs its own follow-up since neither
        // exists in LibraryViewModel yet.
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Library", style = MaterialTheme.typography.titleMedium) },
            )
        },
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
                        // #265 — the Resume card used to sit one
                        // `verticalArrangement.spacedBy(md)` gap above the
                        // grid's first row, reading as another grid item
                        // rather than a hero zone. A small spacer-row +
                        // brass "Your library" caption gives the resume
                        // its own band and labels the grid as a separate
                        // section.
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Spacer(modifier = Modifier.height(spacing.xs))
                        }
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = "Your library",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = spacing.xs, bottom = spacing.xxs),
                            )
                        }
                    }
                    itemsIndexed(dedupedFictions, key = { _, item -> item.id }) { index, fiction ->
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
                                monogram = fictionMonogram(fiction.author, fiction.title),
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
                monogram = fictionMonogram(entry.fiction.author, entry.fiction.title),
                modifier = Modifier.size(width = 68.dp, height = 100.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Resume", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(entry.fiction.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                // #265 — when chapter.title is blank (RSS feeds where only
                // the index was parsed, first-cold-launch state), the old
                // format produced "Ch. 0 · " with a dangling separator
                // that read as missing data. Drop the separator entirely
                // in that case so the line ends cleanly with "Ch. 0".
                Text(
                    text = if (entry.chapter.title.isNotBlank()) {
                        "Ch. ${entry.chapter.index} · ${entry.chapter.title}"
                    } else {
                        "Ch. ${entry.chapter.index}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (fraction != null) {
                    // BrassProgressBar smooth-animates the fill on resume —
                    // when the user opens Library mid-chapter the bar
                    // visibly settles to current progress instead of
                    // snapping. Same brass palette as the rest of the row.
                    BrassProgressBar(
                        progress = fraction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = spacing.xxs),
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
    // Same brass-realm rhythm as FollowsScreen.SignedOutEmpty and the
    // ErrorBlock FullScreen treatment — sigil tile, titleMedium primary
    // headline, bodyMedium body. No CTA: Browse is one tap away in the
    // bottom nav and Add-by-URL is on the FAB above. Empty state's job
    // is to acknowledge and invite, not duplicate navigation.
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
            "Your library is empty",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            "Browse Royal Road or paste a fiction URL with the + button to start collecting.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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
