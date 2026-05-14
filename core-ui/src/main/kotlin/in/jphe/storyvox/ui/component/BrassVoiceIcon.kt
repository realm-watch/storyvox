package `in`.jphe.storyvox.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Issue #418 — magical voice settings icon. A brass-toned soundwave
 * (five vertical bars of varying height, the audiogram silhouette) with
 * a four-pointed sparkle off the upper-right corner. Drawn with native
 * Compose [Path] / [Canvas] rather than a vector drawable so it
 * scales cleanly at any density and inherits its color from the theme
 * (brass `colorScheme.primary` by default).
 *
 * **Why this glyph and not, say, a tuning fork**: the issue's spec
 * called out four candidate semantics (tuning fork, quill+wave,
 * rune+mic, soundwave+sparkle). JP picked soundwave+sparkle as the
 * most direct — "this changes how the voice sounds" reads instantly
 * from a 24dp glyph without translation. The sparkle ties it back to
 * the realm-magic vocabulary (matches `MilestoneConfetti`'s sparkles
 * and the `MagicSpinner`'s brass-arcane ring).
 *
 * **Layout**: the five bars sit on a baseline at ~75% of the canvas
 * height (centered horizontally), heights varying from 30% → 70% →
 * 50% → 90% → 40% so it reads as an asymmetric audio waveform rather
 * than a static equalizer. The sparkle floats at ~(78%, 22%) — upper-
 * right corner, off the top-right bar — with four short rays.
 *
 * **Sizing**: defaults to 24.dp (Material icon-button standard). The
 * [Stroke] caps + joins are rounded so the bars don't pixel-ladder on
 * Flip3's outer 1.9" display, where each pixel matters.
 */
@Composable
fun BrassVoiceIcon(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String? = "Voice settings",
) {
    val semanticModifier = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    Canvas(modifier = modifier.then(semanticModifier).then(Modifier.size(size))) {
        val w = this.size.width
        val h = this.size.height

        // ── Soundwave: five vertical bars ──────────────────────────
        // Bars sit on a baseline at 75% height (centered around a
        // midline at ~50%). Heights are picked to look like a snapshot
        // of natural speech amplitude rather than a flat row.
        val barCount = 5
        // Total wave width is 60% of canvas (leaves room for the
        // sparkle on the right and a small left margin).
        val waveAreaWidth = w * 0.60f
        // Left edge of the wave area, with a small inset from the
        // canvas's leading edge.
        val waveLeft = w * 0.08f
        val gap = waveAreaWidth / (barCount - 1)
        val barStrokeWidth = w * 0.085f
        val midY = h * 0.50f
        // Heights as a fraction of canvas height. Asymmetric on purpose.
        val heights = floatArrayOf(0.30f, 0.55f, 0.40f, 0.70f, 0.32f)

        val stroke = Stroke(
            width = barStrokeWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )

        for (i in 0 until barCount) {
            val cx = waveLeft + gap * i
            val halfH = heights[i] * h / 2f
            val path = Path().apply {
                moveTo(cx, midY - halfH)
                lineTo(cx, midY + halfH)
            }
            drawPath(path = path, color = color, style = stroke)
        }

        // ── Sparkle: 4-pointed star at the upper right ─────────────
        // Center of the sparkle.
        val sparkleCx = w * 0.82f
        val sparkleCy = h * 0.22f
        // Long rays (cardinal: up/down/left/right) and short rays
        // (diagonals at 45°), so the star has a primary "+" axis with
        // softer "x" highlights. Long ray = 18% of canvas size; short
        // ray = 10%.
        val longRay = w * 0.16f
        val shortRay = w * 0.09f
        val sparkleStroke = Stroke(
            width = w * 0.05f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )

        // Cardinal rays.
        val cardinalAngles = floatArrayOf(0f, 90f, 180f, 270f)
        for (deg in cardinalAngles) {
            val rad = Math.toRadians(deg.toDouble())
            val dx = (cos(rad) * longRay).toFloat()
            val dy = (sin(rad) * longRay).toFloat()
            val p = Path().apply {
                moveTo(sparkleCx, sparkleCy)
                lineTo(sparkleCx + dx, sparkleCy + dy)
            }
            drawPath(path = p, color = color, style = sparkleStroke)
        }

        // Diagonal short rays (the gentle "x" overlay on the "+").
        val diagonalAngles = floatArrayOf(45f, 135f, 225f, 315f)
        for (deg in diagonalAngles) {
            val rad = Math.toRadians(deg.toDouble())
            val dx = (cos(rad) * shortRay).toFloat()
            val dy = (sin(rad) * shortRay).toFloat()
            val p = Path().apply {
                moveTo(sparkleCx, sparkleCy)
                lineTo(sparkleCx + dx, sparkleCy + dy)
            }
            drawPath(path = p, color = color, style = sparkleStroke)
        }

        // Tiny brass node at the sparkle center for visual weight.
        drawCircle(
            color = color,
            radius = w * 0.035f,
            center = Offset(sparkleCx, sparkleCy),
        )
    }
}
