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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
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
import `in`.jphe.storyvox.data.db.entity.Shelf
import `in`.jphe.storyvox.data.repository.ContinueListeningEntry
import `in`.jphe.storyvox.data.repository.HistoryEntry
import `in`.jphe.storyvox.data.source.model.FictionSummary
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
        Column(modifier = Modifier.fillMaxSize().padding(scaffoldPadding)) {
            // Issue #158 — Library sub-tabs. SecondaryTabRow rather than the
            // PrimaryTabRow used by the bottom-level top-bar tabs, because
            // these are *inside* a tab — Material 3 reserves the primary
            // indicator for the top-level surface (BottomTabBar) and uses
            // secondary for nested division.
            //
            // Layering with #116 (shelves): Tab.Reading coerces the chip
            // filter to OneShelf(Reading) in the VM, so the same shelf-
            // scoped Room flow drives both surfaces. The chip row is shown
            // only on Tab.All — on Tab.Reading the tab itself IS the
            // filter, on Tab.History the chip row isn't meaningful.
            SecondaryTabRow(
                selectedTabIndex = state.tab.ordinal,
                modifier = Modifier.fillMaxWidth(),
            ) {
                LibraryTab.entries.forEach { tab ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                softWrap = false,
                            )
                        },
                    )
                }
            }

            when (state.tab) {
                LibraryTab.All -> Column(modifier = Modifier.fillMaxSize().padding(top = spacing.md)) {
                    // #116 — chip strip lives above the grid (and above
                    // the Resume card) so it's always reachable regardless of
                    // scroll. Only shown on Tab.All — Reading shelf has its
                    // own tab now.
                    ShelfChipRow(
                        selected = state.filter,
                        onSelect = viewModel::selectFilter,
                    )
                    LibraryGridBody(
                        state = state,
                        dedupedFictions = dedupedFictions,
                        onResume = viewModel::resume,
                        onOpenFiction = viewModel::openFiction,
                        onLongPress = viewModel::openManageShelves,
                    )
                }

                LibraryTab.Reading -> Box(modifier = Modifier.fillMaxSize().padding(top = spacing.md)) {
                    // Reading tab: filter is auto-coerced to Reading shelf
                    // in the VM. No chip row — the tab is the filter.
                    LibraryGridBody(
                        state = state,
                        dedupedFictions = dedupedFictions,
                        onResume = viewModel::resume,
                        onOpenFiction = viewModel::openFiction,
                        onLongPress = viewModel::openManageShelves,
                    )
                }

                LibraryTab.History -> Box(modifier = Modifier.fillMaxSize().padding(top = spacing.md)) {
                    HistoryList(
                        entries = state.history,
                        onOpenChapter = viewModel::openHistoryEntry,
                    )
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

/**
 * Issue #158 — All / Reading library grid. Extracted from the inlined body
 * so the same composable serves both sub-tabs without duplication. The
 * Resume card + "Your library" caption hero zone is preserved as the
 * first full-span rows in the grid (unchanged from the pre-sub-tabs
 * implementation).
 */
/**
 * The unified library grid body. Renders the chip-aware empty state, the
 * Resume hero (only on filter == All, so it doesn't surface inside a
 * Wishlist scope), and the cascading grid with long-press → manage-shelves.
 *
 * Shared between [LibraryTab.All] (chips visible above) and
 * [LibraryTab.Reading] (chips hidden — tab is the filter).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryGridBody(
    state: LibraryUiState,
    dedupedFictions: List<FictionSummary>,
    onResume: () -> Unit,
    onOpenFiction: (String) -> Unit,
    onLongPress: (FictionSummary) -> Unit,
) {
    val spacing = LocalSpacing.current
    val isEmpty = !state.isLoading && dedupedFictions.isEmpty() && state.resume == null
    if (isEmpty) {
        when (val f = state.filter) {
            ShelfFilter.All -> EmptyLibrary()
            is ShelfFilter.OneShelf -> EmptyShelf(f.shelf)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        contentPadding = PaddingValues(spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        // Hide the Resume card on shelf-filtered views — it's a
        // library-wide affordance, and surfacing it inside a Wishlist
        // filter (a book the user hasn't started) is visually confusing.
        if (state.filter is ShelfFilter.All) {
            state.resume?.let { entry ->
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    ResumeCard(entry, onResume = onResume)
                }
                // #265 — single full-span caption row labelling the grid
                // as a separate section beneath the Resume hero. See the
                // kdoc on the original implementation for the gap-doubling
                // rationale.
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
                    // Long-press → manage-shelves bottom sheet (#116).
                    // combinedClickable keeps the tap-to-open path
                    // identical to before; long-press is additive.
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onOpenFiction(fiction.id) },
                            onLongClick = { onLongPress(fiction) },
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

/**
 * Issue #158 — chronological reading-history list. Most-recent open at the
 * top. Each row: cover thumb, fiction title, chapter title, "2h ago"
 * relative timestamp. Tap → reader at that chapter.
 *
 * LazyColumn (not LazyVerticalGrid) because History is a single-column
 * timeline — the visual rhythm is closer to a chat log than a poster
 * wall. Brass-themed cover thumbs match the Library grid aesthetic.
 *
 * Empty state mirrors [EmptyLibrary]: same sigil + heading + invitation
 * pattern, just worded for the no-history case ("Start listening and
 * chapters will show up here.").
 */
@Composable
private fun HistoryList(
    entries: List<HistoryEntry>,
    onOpenChapter: (HistoryEntry) -> Unit,
) {
    val spacing = LocalSpacing.current
    if (entries.isEmpty()) {
        EmptyHistory()
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        items(entries, key = { entry -> "${entry.fictionId}::${entry.chapterId}" }) { entry ->
            HistoryRow(entry, onClick = { onOpenChapter(entry) })
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onClick: () -> Unit) {
    val spacing = LocalSpacing.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(spacing.sm).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            FictionCoverThumb(
                coverUrl = entry.coverUrl,
                title = entry.fictionTitle.orEmpty(),
                // Fallback monogram if a cascade-delete race left us with
                // a null fictionTitle (see ChapterHistoryRow kdoc). Empty
                // strings here just produce a brass sigil placeholder
                // instead of a "?" — see #322's fictionMonogram.
                monogram = fictionMonogram(entry.fictionAuthor.orEmpty(), entry.fictionTitle.orEmpty()),
                modifier = Modifier.size(width = 44.dp, height = 64.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.fictionTitle ?: "(removed)",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                // Match Resume card formatting: "Ch. N · title" with the
                // dangling-separator guard for blank titles (see #265).
                val chapterLabel = buildString {
                    entry.chapterIndex?.let { append("Ch. $it") }
                    val title = entry.chapterTitle.orEmpty()
                    if (entry.chapterIndex != null && title.isNotBlank()) append(" · ")
                    if (title.isNotBlank()) append(title)
                    if (isEmpty()) append("(chapter removed)")
                }
                Text(
                    text = chapterLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = relativeTimeLabel(entry.openedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun EmptyHistory() {
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
            "Nothing here yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            "Start listening and the chapters you open will show up here, newest first.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Coarse "how long ago" label for the History list. Bucketed for human
 * scanability rather than precise: "just now" (under a minute), "Nm ago"
 * (under an hour), "Nh ago" (under a day), "Nd ago" (under a week),
 * "Nw ago" otherwise. Past ~12 weeks we still emit "Nw ago" — for a
 * forever-retention surface we'd rather over-emit weeks than introduce a
 * month-or-year boundary that would render dates inconsistently across
 * locales. If JP wants absolute dates we add them as a tooltip later.
 *
 * Uses `System.currentTimeMillis()` so the label is point-in-time-of-
 * composition; Compose recomposes the History list whenever the
 * underlying flow emits (i.e. on every new open). For a forever-running
 * Library screen the labels eventually drift — that's fine, the next
 * compositional kick (tab switch, new open, app foreground) refreshes
 * them. We deliberately don't run a per-second ticker for what's
 * essentially a secondary-priority caption.
 */
private fun relativeTimeLabel(openedAt: Long): String {
    val deltaMs = (System.currentTimeMillis() - openedAt).coerceAtLeast(0L)
    val minutes = deltaMs / 60_000L
    if (minutes < 1L) return "just now"
    if (minutes < 60L) return "${minutes}m ago"
    val hours = minutes / 60L
    if (hours < 24L) return "${hours}h ago"
    val days = hours / 24L
    if (days < 7L) return "${days}d ago"
    val weeks = days / 7L
    return "${weeks}w ago"
}
