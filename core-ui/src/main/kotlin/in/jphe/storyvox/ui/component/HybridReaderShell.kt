package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import `in`.jphe.storyvox.ui.theme.LocalMotion
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion
import kotlin.math.roundToInt

enum class ReaderView { Audiobook, Reader }

/**
 * Two-pane horizontal swipe shell.
 *
 * Audiobook lives at offset 0; Reader sits one screen-width to the right.
 * Drag updates an offset in pixels; on release we settle to the nearest pane
 * based on a 40% positional threshold.
 *
 * Implementation note: we use [draggable] + a settle animation rather than
 * `AnchoredDraggableState` to avoid Compose-version churn around its API.
 *
 * @param current which view is currently active (hoisted)
 * @param onViewChange invoked when the user settles on a different view
 */
@Composable
fun HybridReaderShell(
    current: ReaderView,
    onViewChange: (ReaderView) -> Unit,
    audiobookContent: @Composable () -> Unit,
    readerContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotion.current
    // #486 Phase 2 / #480 — settle-spring is functional (it's how the
    // user knows the swipe "completed" into the next pane), so we
    // keep it on, just with a snap spec instead of a 360ms ease.
    // Snap-to-target preserves the structural feedback (the pane
    // does settle) while removing the lateral motion.
    val reducedMotion = LocalReducedMotion.current

    var width by remember { mutableFloatStateOf(0f) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // Target offset — Audiobook=0, Reader=-width
    val target = when (current) {
        ReaderView.Audiobook -> 0f
        ReaderView.Reader -> -width
    }

    val animatedOffset by animateFloatAsState(
        targetValue = if (isDragging) dragOffset else target,
        animationSpec = if (reducedMotion) snap() else tween(motion.swipeDurationMs, easing = motion.swipeEasing),
        label = "shellOffset",
    )

    LaunchedEffect(current, width) {
        if (!isDragging) dragOffset = target
    }

    val draggableState = rememberDraggableState { delta ->
        if (width > 0f) {
            dragOffset = (dragOffset + delta).coerceIn(-width, 0f)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                width = size.width.toFloat()
                if (!isDragging) dragOffset = target
            }
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                onDragStarted = { isDragging = true },
                onDragStopped = { velocity ->
                    isDragging = false
                    val settled = settle(dragOffset, width, velocity)
                    if (settled != current) onViewChange(settled)
                    dragOffset = when (settled) {
                        ReaderView.Audiobook -> 0f
                        ReaderView.Reader -> -width
                    }
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offsetXBy { animatedOffset.roundToInt() },
        ) { audiobookContent() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .offsetXBy { (animatedOffset + width).roundToInt() },
        ) { readerContent() }
    }
}

private fun Modifier.offsetXBy(provider: () -> Int): Modifier =
    this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.place(IntOffset(provider(), 0))
        }
    }

/** 40% positional threshold OR strong velocity flips the pane. */
private fun settle(offset: Float, width: Float, velocity: Float): ReaderView {
    if (width <= 0f) return ReaderView.Audiobook
    val pulledFraction = -offset / width
    val velocityFlip = velocity < -800f // fast leftward fling → Reader
    val velocityBack = velocity > 800f  // fast rightward fling → Audiobook
    return when {
        velocityFlip -> ReaderView.Reader
        velocityBack -> ReaderView.Audiobook
        pulledFraction >= 0.4f -> ReaderView.Reader
        else -> ReaderView.Audiobook
    }
}
