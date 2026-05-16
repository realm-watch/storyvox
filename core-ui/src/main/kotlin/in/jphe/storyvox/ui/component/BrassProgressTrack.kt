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
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion
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
    // Issue #620 — pre-fix the rail used `outlineVariant` (very low
    // contrast against the surface) and the fill used `primary` at
    // default alpha. On a dim cover the scrubber was nearly invisible
    // — the brass rail blended into the surface and the user couldn't
    // tell where in the chapter they were. Bump the rail to a slightly
    // brighter `outline` (still subtle, doesn't compete with the cover)
    // and let the fill use the full brass primary so the filled
    // portion of the rail reads as a confident "you are here" marker
    // against both light and dark themes.
    val rail = MaterialTheme.colorScheme.outline
    val fill = MaterialTheme.colorScheme.primary
    val spacing = LocalSpacing.current

    var scrubFraction by remember { mutableFloatStateOf(-1f) }
    val displayed = if (scrubFraction >= 0f) scrubFraction
    else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    // Brass thumb pulse while audio isn't flowing. Two channels: alpha (so
    // the thumb glows softer-then-brighter) and radius (a subtle 8→11dp
    // breath). At 1100ms it's fast enough to feel alive but slow enough not
    // to look like a fault indicator.
    //
    // #486 Phase 2 / #480 — under LocalReducedMotion the pulse goes
    // static (alpha=1f, radius boost=0). The thumb still renders at
    // a clear, slightly-brighter resting state so the user can tell
    // the rail is loading-vs-active without the breath animation
    // (which can trigger vestibular discomfort).
    val reducedMotion = LocalReducedMotion.current
    val pulse = rememberInfiniteTransition(label = "thumb-pulse")
    val pulseAlpha by if (reducedMotion) {
        remember { mutableFloatStateOf(1f) }
    } else {
        pulse.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "alpha",
        )
    }
    val pulseRadiusBoost by if (reducedMotion) {
        remember { mutableFloatStateOf(0f) }
    } else {
        pulse.animateFloat(
            initialValue = 0f,
            targetValue = 3f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "radius",
        )
    }

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
                    // Issue #620 — bump rail height from 3 dp to 4 dp so
                    // the filled portion has more visual weight against
                    // the cover. 4 dp is the upper limit before the
                    // scrubber starts to fight the play button visually;
                    // verified on R83W80CAFZB (tablet) + Flip3 (phone).
                    val railH = 4.dp.toPx()
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
            // Issue #620 — timecodes previously used onSurfaceVariant
            // which dropped to ~50 % contrast on dark surfaces; on a
            // dim cover the "0:00 / 47:12" was illegible. Bump to
            // onSurface for full contrast so the chapter position
            // reads at a glance, and use labelMedium (was labelSmall)
            // for a slightly larger digit — the scrubber timecode is
            // a critical at-a-glance field, it shouldn't be the
            // tiniest text on the screen.
            Text(formatMs(positionMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            Box(modifier = Modifier.weight(1f))
            Text(formatMs(durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
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
