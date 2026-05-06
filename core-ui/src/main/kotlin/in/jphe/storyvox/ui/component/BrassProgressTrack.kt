package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import kotlin.math.roundToLong

/**
 * The chapter scrubber. Brass thumb on a thin rail.
 *
 * @param positionMs current playback position
 * @param durationMs total chapter duration
 * @param onSeekTo invoked on scrub end with the requested ms
 * @param loading when true, the thumb pulses (alpha + radius) to signal
 *   that audio isn't actively playing right now even though [isPlaying] may
 *   be true (e.g. voice warming up). Position is the caller's responsibility
 *   to keep stable in that state — this only controls the thumb animation.
 */
@Composable
fun BrassProgressTrack(
    positionMs: Long,
    durationMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    val rail = MaterialTheme.colorScheme.outlineVariant
    val fill = MaterialTheme.colorScheme.primary
    val spacing = LocalSpacing.current

    var scrubFraction by remember { mutableFloatStateOf(-1f) }
    val displayed = if (scrubFraction >= 0f) scrubFraction
    else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    // Brass thumb pulse while audio isn't flowing. Two channels: alpha (so
    // the thumb glows softer-then-brighter) and radius (a subtle 8→11dp
    // breath). At 1100ms it's fast enough to feel alive but slow enough not
    // to look like a fault indicator.
    val pulse = rememberInfiniteTransition(label = "thumb-pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    val pulseRadiusBoost by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "radius",
    )

    Box(
        modifier = modifier.semantics {
            contentDescription = "Playback progress, ${formatMs(positionMs)} of ${formatMs(durationMs)}"
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .pointerInput(durationMs) {
                    // Hand-rolled gesture so a single tap also seeks. The built-in
                    // detectHorizontalDragGestures only fires after enough drag
                    // distance, swallowing taps and tiny drags — which is what JP
                    // ran into where the seek bar felt unresponsive.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        scrubFraction = (down.position.x / size.width).coerceIn(0f, 1f)
                        var lastFraction = scrubFraction
                        while (true) {
                            val ev = awaitPointerEvent()
                            val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            lastFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            scrubFraction = lastFraction
                            if (change.positionChange().x != 0f) change.consume()
                        }
                        val target = (lastFraction * durationMs).roundToLong()
                        scrubFraction = -1f
                        onSeekTo(target)
                    }
                }
                .drawBehind {
                    val railY = size.height / 2f
                    val railH = 3.dp.toPx()
                    drawRect(rail, topLeft = Offset(0f, railY - railH / 2f), size = Size(size.width, railH))
                    drawRect(fill, topLeft = Offset(0f, railY - railH / 2f), size = Size(size.width * displayed, railH))
                    val thumbX = size.width * displayed
                    val baseRadius = 8.dp.toPx()
                    if (loading) {
                        // Soft brass halo that breathes with the pulse; sits behind
                        // the solid thumb so the user reads "alive, not stuck".
                        drawCircle(
                            color = fill,
                            radius = baseRadius + pulseRadiusBoost.dp.toPx() + 4.dp.toPx(),
                            center = Offset(thumbX, railY),
                            alpha = (pulseAlpha - 0.4f).coerceAtLeast(0f) * 0.5f,
                        )
                        drawCircle(
                            color = fill,
                            radius = baseRadius + pulseRadiusBoost.dp.toPx(),
                            center = Offset(thumbX, railY),
                            alpha = pulseAlpha,
                        )
                    } else {
                        drawCircle(fill, radius = baseRadius, center = Offset(thumbX, railY))
                    }
                    drawCircle(rail, radius = baseRadius, center = Offset(thumbX, railY), style = Stroke(width = 1.dp.toPx()))
                },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, start = spacing.xxs, end = spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatMs(positionMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(modifier = Modifier.weight(1f))
            Text(formatMs(durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}
