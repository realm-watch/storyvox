package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Brass arcane sigil ring — single rotating dashed circle in the realm
 * brass tone. Used wherever storyvox is waiting on something (Royal Road
 * fetch, voice-model download, sherpa-onnx warming up to first sentence).
 *
 * Visually quieter than [MagicSkeletonTile] (which adds a six-pointed
 * star + pulsing alpha), so it composes cleanly as an overlay behind a
 * play button or as a small inline indicator.
 *
 * @param strokeWidth thickness of the ring; defaults to 2.dp.
 * @param dashLengthFraction fraction of the circumference used by the
 *   dash pattern. Smaller = more "magical sweep" feel.
 */
@Composable
fun MagicSpinner(
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.dp,
    dashLengthFraction: Float = 0.18f,
    durationMs: Int = 1600,
) {
    val brass = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "magic-spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )

    Canvas(modifier = modifier.rotate(angle)) {
        val stroke = strokeWidth.toPx()
        val diameter = size.minDimension - stroke
        val circumference = (Math.PI * diameter).toFloat()
        val dash = circumference * dashLengthFraction.coerceIn(0.05f, 0.5f)
        val gap = circumference - dash
        drawArc(
            color = brass,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(stroke / 2f, stroke / 2f),
            size = androidx.compose.ui.geometry.Size(diameter, diameter),
            style = Stroke(
                width = stroke,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), 0f),
            ),
        )
    }
}

/**
 * MagicSpinner + status text, vertically stacked. The standard "we're
 * waiting on something magical" inline composable.
 */
@Composable
fun MagicLoadingStatus(
    text: String,
    modifier: Modifier = Modifier,
    spinnerSize: Dp = 28.dp,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.padding(spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MagicSpinner(modifier = Modifier.size(spinnerSize))
        Spacer(Modifier.height(spacing.xs))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
    }
}
