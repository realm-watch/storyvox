package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

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
