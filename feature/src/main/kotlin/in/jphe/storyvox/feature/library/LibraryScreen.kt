package `in`.jphe.storyvox.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import `in`.jphe.storyvox.data.db.entity.Shelf
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpenFiction: (String) -> Unit,
    onOpenReader: (String, String) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val addByUrlState by viewModel.addByUrlState.collectAsStateWithLifecycle()
    val manageShelvesState by viewModel.manageShelvesState.collectAsStateWithLifecycle()
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
        Column(modifier = Modifier.fillMaxSize().padding(scaffoldPadding).padding(top = spacing.md)) {
            // Issue #116 — chip strip lives above the grid (and above
            // the Resume card) so it's always reachable regardless of
            // scroll. Selecting a chip swaps the underlying flow in the
            // VM, which re-emits a filtered grid.
            ShelfChipRow(
                selected = state.filter,
                onSelect = viewModel::selectFilter,
            )

            // Empty-state branch needs to know which chip we're on — the
            // "library is empty" copy makes sense for All but reads as
            // wrong on a shelf with zero pinned books.
            val isEmpty = !state.isLoading && state.fictions.isEmpty() && state.resume == null
            if (isEmpty) {
                when (val f = state.filter) {
                    ShelfFilter.All -> EmptyLibrary()
                    is ShelfFilter.OneShelf -> EmptyShelf(f.shelf)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    contentPadding = PaddingValues(spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    // Hide the Resume card on shelf-filtered views — it's
                    // a library-wide affordance, and surfacing it inside
                    // a Wishlist filter (a book the user hasn't started)
                    // is visually confusing.
                    if (state.filter is ShelfFilter.All) {
                        state.resume?.let { resume ->
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                ResumeCard(resume, onResume = viewModel::resume)
                            }
                            // #265 — the Resume card used to sit one
                            // `verticalArrangement.spacedBy(md)` gap above the
                            // grid's first row, reading as another grid item
                            // rather than a hero zone. The brass "Your library"
                            // caption now labels the grid as a separate section.
                            // Spacer + caption live in one full-span item so we
                            // only pay one inter-row gap from the grid's
                            // vertical arrangement (two items would double it).
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                Column(modifier = Modifier.padding(top = spacing.xs)) {
                                    Text(
                                        text = "Your library",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(bottom = spacing.xxs),
                                    )
                                }
                            }
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
                                // Long-press → manage-shelves bottom sheet
                                // (issue #116). combinedClickable keeps
                                // the tap-to-open path identical to
                                // before; the long-press is an additive
                                // overlay handler.
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { viewModel.openFiction(fiction.id) },
                                        onLongClick = { viewModel.openManageShelves(fiction) },
                                    ),
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

    ManageShelvesSheet(
        state = manageShelvesState,
        onToggle = viewModel::toggleShelf,
        onDismiss = viewModel::dismissManageShelves,
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
 * Issue #116 — empty state per shelf. The library-wide [EmptyLibrary]
 * doesn't fit here: the user *has* books in their library, they just
 * haven't pinned any to this particular shelf. Same brass-realm rhythm
 * as the rest of the empty-state vocabulary (sigil tile, titleMedium
 * primary headline, bodyMedium body) so it reads as part of the family.
 */
@Composable
private fun EmptyShelf(shelf: Shelf) {
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
            "Nothing on the ${shelf.displayName} shelf yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            "Long-press a book to add it.",
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
