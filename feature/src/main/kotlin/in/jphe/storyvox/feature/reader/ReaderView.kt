package `in`.jphe.storyvox.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.feature.api.UiPlaybackState
import `in`.jphe.storyvox.ui.component.SentenceHighlight
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import kotlin.math.roundToInt

/**
 * Manual-scroll grace window. While the user is dragging or for this many millis after,
 * the auto-scroll-to-sentence behavior is suppressed so we don't yank them away from
 * whatever they're reading ahead.
 */
private const val MANUAL_SCROLL_GRACE_MS = 5_000L

/** Where on the viewport the highlighted sentence should land — 40% from the top. */
private const val HIGHLIGHT_VIEWPORT_FRACTION = 0.40f

@Composable
fun ReaderTextView(
    state: UiPlaybackState,
    chapterText: String,
    onPlayPause: () -> Unit,
    onSeekToChar: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val scroll = rememberScrollState()

    // Y offset (px) of the SentenceHighlight composable's top inside the scrolling Column,
    // measured at the moment the body settles. The Column's contentPadding shifts everything
    // down by spacing.lg + the title's measured height; we capture both via onGloballyPositioned.
    var bodyTopPx by remember { mutableFloatStateOf(0f) }
    var viewportHeightPx by remember { mutableFloatStateOf(0f) }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    // Manual-scroll grace: each time the user starts dragging we stamp wall-clock time;
    // auto-scroll respects the grace window before resuming.
    var lastManualScrollAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress }.collect { dragging ->
            if (dragging) lastManualScrollAt = System.currentTimeMillis()
        }
    }

    // Auto-scroll target: when sentence range or layout changes, settle the highlighted
    // line at 40% from the top of the viewport — unless we're inside the manual-scroll
    // grace window, in which case the user has the wheel.
    LaunchedEffect(state.sentenceStart, state.sentenceEnd, textLayout, viewportHeightPx, bodyTopPx, chapterText) {
        val layout = textLayout ?: return@LaunchedEffect
        if (chapterText.isEmpty()) return@LaunchedEffect
        if (viewportHeightPx <= 0f) return@LaunchedEffect
        val start = state.sentenceStart
        val end = state.sentenceEnd
        if (end <= start) return@LaunchedEffect
        if (start !in 0..chapterText.length) return@LaunchedEffect
        if (System.currentTimeMillis() - lastManualScrollAt < MANUAL_SCROLL_GRACE_MS) return@LaunchedEffect

        val safeStart = start.coerceIn(0, (chapterText.length - 1).coerceAtLeast(0))
        val line = layout.getLineForOffset(safeStart)
        val lineTopWithinText = layout.getLineTop(line)
        val targetY = (bodyTopPx + lineTopWithinText - viewportHeightPx * HIGHLIGHT_VIEWPORT_FRACTION)
            .roundToInt()
            .coerceIn(0, scroll.maxValue.coerceAtLeast(0))

        scroll.animateScrollTo(targetY)
    }

    Box(modifier = modifier.fillMaxSize().onSizeChanged { viewportHeightPx = it.height.toFloat() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = spacing.lg)
                .padding(top = spacing.lg, bottom = 96.dp),
        ) {
            Text(
                state.chapterTitle,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = spacing.md),
            )
            if (chapterText.isEmpty()) {
                EmptyChapterPlaceholder()
            } else {
                Box(modifier = Modifier.onGloballyPositioned { coords ->
                    // Position relative to the scrolling Column root: walk up to the parent that holds
                    // the same content as `scroll`. Compose's positionInParent() is enough since the
                    // Column itself is what scrolls — its children move with it.
                    bodyTopPx = coords.positionInParent().y
                }) {
                    SentenceHighlight(
                        text = chapterText,
                        highlightStart = state.sentenceStart,
                        highlightEnd = state.sentenceEnd,
                        onTapWord = onSeekToChar,
                        onLayout = { layout -> textLayout = layout },
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(start = spacing.xs)) {
                    Text(
                        state.chapterTitle.ifEmpty { "—" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    Text(
                        state.fictionTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                FilledIconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyChapterPlaceholder() {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Magical brass sigil while the chapter body is being fetched + parsed.
        `in`.jphe.storyvox.ui.component.MagicSkeletonTile(
            modifier = Modifier.size(width = 200.dp, height = 280.dp),
            shape = MaterialTheme.shapes.medium,
            glyphSize = 96.dp,
        )
        Spacer(Modifier.height(spacing.lg))
        Text(
            "Conjuring the chapter…",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            "The text will appear here as VoxSherpa reads it aloud.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
