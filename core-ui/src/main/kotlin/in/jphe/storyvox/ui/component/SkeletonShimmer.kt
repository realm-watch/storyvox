package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Library Nocturne loading shimmer — a calm 1200ms opacity pulse so it feels
 * deliberate, not anxious. Reverse repeat keeps rise/fall symmetric.
 */
@Composable
fun shimmerAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "skeleton-shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-alpha",
    )
    return alpha
}

/** A rounded surface that pulses opacity in the brass palette. */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    val alpha = shimmerAlpha()
    Box(
        modifier = modifier
            .clip(shape)
            .alpha(alpha)
            .background(color),
    )
}

/**
 * A skeleton tile with a slow-rotating brass arcane sigil at its center.
 *
 * Reads as "magic is happening" rather than the generic alpha-pulse rectangle
 * users associate with stale UI. The sigil is a six-pointed star inside a ring,
 * with three brass-graded layers rotating at different rates to give a mild
 * parallax. Drops onto the same `surfaceContainerHigh` substrate as
 * [SkeletonBlock] so the skeleton grid remains visually unified.
 *
 * @param modifier the cell shape — typically the cover slot.
 * @param shape clip shape for the tile (defaults to [MaterialTheme.shapes.medium]).
 * @param glyphSize the diameter of the sigil glyph; default 56dp reads at all
 *   reasonable card sizes (140dp+ wide).
 */
@Composable
fun MagicSkeletonTile(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    glyphSize: Dp = 56.dp,
) {
    val transition = rememberInfiniteTransition(label = "skeleton-sigil")
    // Outer ring rotates clockwise; the inner star rotates counter-clockwise
    // at a different period for visual depth.
    val outerRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12_000, easing = LinearEasing),
        ),
        label = "outer",
    )
    val innerRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = -360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18_000, easing = LinearEasing),
        ),
        label = "inner",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    val brass = MaterialTheme.colorScheme.primary
    val brassDim = brass.copy(alpha = 0.45f)
    val brassFaint = brass.copy(alpha = 0.18f)
    val substrate = MaterialTheme.colorScheme.surfaceContainerHigh

    Box(
        modifier = modifier.clip(shape).background(substrate),
        contentAlignment = Alignment.Center,
    ) {
        // Subtle radial wash so the sigil reads against a slightly-darker
        // center, like brass on aged leather.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            substrate.copy(alpha = 0.0f),
                            substrate.copy(alpha = 0.4f),
                        ),
                    ),
                ),
        )
        Canvas(modifier = Modifier.size(glyphSize)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f
            val ringStroke = (radius * 0.06f).coerceAtLeast(1.5f)
            val starStroke = (radius * 0.08f).coerceAtLeast(2.0f)

            // Faint outer ring (static)
            drawCircle(
                color = brassFaint,
                radius = radius * 0.95f,
                center = center,
                style = Stroke(width = ringStroke),
            )

            // Outer rotating ring with tick marks
            rotate(outerRotation, pivot = center) {
                drawCircle(
                    color = brassDim.copy(alpha = brassDim.alpha * pulse),
                    radius = radius * 0.78f,
                    center = center,
                    style = Stroke(
                        width = ringStroke * 0.8f,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(radius * 0.18f, radius * 0.10f),
                            phase = 0f,
                        ),
                    ),
                )
            }

            // Inner six-pointed star (two overlaid triangles)
            rotate(innerRotation, pivot = center) {
                val starRadius = radius * 0.55f
                drawTriangle(center, starRadius, 0f, brass.copy(alpha = pulse), starStroke)
                drawTriangle(center, starRadius, 60f, brass.copy(alpha = pulse * 0.85f), starStroke)
            }

            // Center dot — like the lit candle
            drawCircle(
                color = brass.copy(alpha = pulse),
                radius = radius * 0.06f,
                center = center,
            )
        }
    }
}

/**
 * Helper: draws an equilateral triangle inscribed in [radius] around [center],
 * rotated by [degreesOffset]. Stroke only, brass-tinted.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTriangle(
    center: Offset,
    radius: Float,
    degreesOffset: Float,
    color: Color,
    strokeWidth: Float,
) {
    val path = Path()
    for (i in 0..2) {
        val angleDeg = -90f + degreesOffset + i * 120f
        val rad = Math.toRadians(angleDeg.toDouble())
        val x = center.x + radius * cos(rad).toFloat()
        val y = center.y + radius * sin(rad).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )
}
