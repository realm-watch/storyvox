package `in`.jphe.storyvox.wear.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.wear.theme.BrassPrimary
import `in`.jphe.storyvox.wear.theme.BrassRingTrack

/**
 * Brass-skinned linear progress for square Wear faces.
 *
 * Square watches are rare-but-supported per the issue; on these the circular
 * ring around the cover art reads awkwardly inside the rectangular safe area.
 * We fall back to a horizontal brass track. Read-only in v1 (touch-to-seek is
 * a follow-up; same constraint as [CircularScrubber]).
 */
@Composable
fun LinearScrubber(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val clamped = progress.coerceIn(0f, 1f)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp),
    ) {
        val mid = size.height / 2f
        val trackEnd = size.width
        // Track
        drawLine(
            color = BrassRingTrack,
            start = Offset(0f, mid),
            end = Offset(trackEnd, mid),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
        )
        // Filled portion
        if (clamped > 0f) {
            drawLine(
                color = BrassPrimary,
                start = Offset(0f, mid),
                end = Offset(trackEnd * clamped, mid),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
        }
    }
}
