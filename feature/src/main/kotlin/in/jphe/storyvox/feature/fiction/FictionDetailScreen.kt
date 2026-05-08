package `in`.jphe.storyvox.feature.fiction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.api.UiChapter
import `in`.jphe.storyvox.feature.api.UiFiction
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.ChapterCard
import `in`.jphe.storyvox.ui.component.ChapterCardState
import `in`.jphe.storyvox.ui.component.ErrorBlock
import `in`.jphe.storyvox.ui.component.ErrorPlacement
import `in`.jphe.storyvox.ui.component.FictionCoverThumb
import `in`.jphe.storyvox.ui.component.FictionDetailSkeleton
import `in`.jphe.storyvox.ui.layout.isAtLeastTablet
import `in`.jphe.storyvox.ui.theme.LocalSpacing

@Composable
fun FictionDetailScreen(
    onOpenReader: (String, String) -> Unit,
    /** Issue #169 — the no-cache full-page error path was a dead-end
     *  with no nav (no Back, no Retry, only OS back). AppNav wires
     *  this so the user always has a way out. Default `{}` keeps
     *  preview/test use working but should never be the production
     *  callsite. */
    onBack: () -> Unit = {},
    viewModel: FictionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val twoColumn = isAtLeastTablet()

    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is FictionDetailUiEvent.OpenReader) onOpenReader(event.fictionId, event.chapterId)
        }
    }

    // Issue #169 — destructive removeFromLibrary used to fire on a
    // single tap of the "In library" button (which reads as a status,
    // not an action). User lost their fiction + read progress with
    // zero confirmation. Gate the destructive path behind an
    // AlertDialog; the additive add-to-library path stays single-tap.
    var showRemoveConfirm by remember { mutableStateOf(false) }
    if (showRemoveConfirm) {
        val titleForDialog = state.fiction?.title ?: "this fiction"
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove $titleForDialog from your library?") },
            text = {
                Text(
                    "Your read progress will be lost. You can re-add it from " +
                        "Browse anytime, but the position you've reached won't be restored.",
                )
            },
            confirmButton = {
                BrassButton(
                    label = "Remove",
                    onClick = {
                        showRemoveConfirm = false
                        viewModel.toggleFollow(false)
                    },
                    variant = BrassButtonVariant.Primary,
                )
            },
            dismissButton = {
                BrassButton(
                    label = "Cancel",
                    onClick = { showRemoveConfirm = false },
                    variant = BrassButtonVariant.Secondary,
                )
            },
        )
    }

    val fiction = state.fiction
    Box(modifier = Modifier.fillMaxSize()) {
        if (fiction == null && state.error != null) {
            // First-load failure with no cached fiction. Issue #169 —
            // this path used to be a dead-end (no Back, no Retry, only
            // OS back). Now wires onBack so the user always has a way
            // out without leaning on the OS gesture. Still no Retry —
            // the underlying refreshDetail re-fires when the user
            // re-enters the screen via Back + re-tap, so a Retry CTA
            // here would just blink the same error.
            ErrorBlock(
                title = "Couldn't load this fiction",
                message = state.error ?: "We couldn't reach Royal Road. Go back and try again in a moment.",
                onRetry = null,
                onBack = onBack,
                placement = ErrorPlacement.FullScreen,
            )
        } else if (fiction == null) {
            FictionDetailSkeleton(modifier = Modifier.fillMaxSize())
        } else if (twoColumn) {
            // Wide layout: cover + meta + synopsis on the left, scrollable chapter list
            // on the right. Bottom bar still floats over both columns.
            Row(
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 96.dp),
                ) {
                    if (state.error != null) {
                        ErrorBlock(
                            title = "Couldn't refresh",
                            message = state.error ?: "We couldn't reach Royal Road.",
                            onRetry = null,
                            placement = ErrorPlacement.Banner,
                        )
                    }
                    Hero(fiction)
                    Synopsis(fiction.synopsis)
                }
                LazyColumn(
                    modifier = Modifier.weight(0.58f).fillMaxSize(),
                    contentPadding = PaddingValues(top = spacing.md, bottom = 96.dp),
                ) {
                    items(state.chapters) { ch ->
                        ChapterCard(
                            state = ch.toCardState(currentId = null),
                            onClick = { viewModel.listen(ch.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = spacing.md, vertical = spacing.xxs),
                        )
                    }
                }
            }

            BottomBar(
                isInLibrary = state.isInLibrary,
                onFollow = {
                    // Issue #169 — gate the destructive path behind a
                    // confirm dialog; the additive path stays single-tap.
                    if (state.isInLibrary) {
                        showRemoveConfirm = true
                    } else {
                        viewModel.toggleFollow(true)
                    }
                },
                onListen = { state.chapters.firstOrNull()?.id?.let(viewModel::listen) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                if (state.error != null) {
                    item {
                        ErrorBlock(
                            title = "Couldn't refresh",
                            message = state.error ?: "We couldn't reach Royal Road.",
                            onRetry = null,
                            placement = ErrorPlacement.Banner,
                        )
                    }
                }
                item { Hero(fiction) }
                item { Synopsis(fiction.synopsis) }
                items(state.chapters) { ch ->
                    ChapterCard(
                        state = ch.toCardState(currentId = null),
                        onClick = { viewModel.listen(ch.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = spacing.md, vertical = spacing.xxs),
                    )
                }
            }

            BottomBar(
                isInLibrary = state.isInLibrary,
                onFollow = {
                    // Issue #169 — gate the destructive path behind a
                    // confirm dialog; the additive path stays single-tap.
                    if (state.isInLibrary) {
                        showRemoveConfirm = true
                    } else {
                        viewModel.toggleFollow(true)
                    }
                },
                onListen = { state.chapters.firstOrNull()?.id?.let(viewModel::listen) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun Hero(fiction: UiFiction) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        FictionCoverThumb(
            coverUrl = fiction.coverUrl,
            title = fiction.title,
            authorInitial = fiction.author.firstOrNull()?.uppercaseChar() ?: '?',
            modifier = Modifier.size(width = 120.dp, height = 180.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        ) {
            Text(fiction.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3)
            Text(fiction.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(spacing.xxs))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Text("%.1f".format(fiction.rating), style = MaterialTheme.typography.labelMedium)
                Text("·", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${fiction.chapterCount} ch", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("·", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (fiction.isOngoing) "Ongoing" else "Completed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun Synopsis(text: String) {
    val spacing = LocalSpacing.current
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
        )
        BrassButton(
            label = if (expanded) "Show less" else "Read more",
            onClick = { expanded = !expanded },
            variant = BrassButtonVariant.Text,
        )
    }
}

@Composable
private fun BottomBar(
    isInLibrary: Boolean,
    onFollow: () -> Unit,
    onListen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrassButton(
                label = if (isInLibrary) "In library" else "Add to library",
                onClick = onFollow,
                variant = BrassButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            BrassButton(
                label = "Listen",
                onClick = onListen,
                variant = BrassButtonVariant.Primary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun UiChapter.toCardState(currentId: String?) = ChapterCardState(
    number = number,
    title = title,
    publishedRelative = publishedRelative,
    durationLabel = durationLabel,
    isDownloaded = isDownloaded,
    isFinished = isFinished,
    isCurrent = id == currentId,
)
