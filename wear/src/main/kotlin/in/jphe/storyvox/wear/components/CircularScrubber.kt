package `in`.jphe.storyvox.wear.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.CircularProgressIndicator
import `in`.jphe.storyvox.wear.theme.BrassPrimary
import `in`.jphe.storyvox.wear.theme.BrassRingTrack

/**
 * Circular brass scrubber for round Wear faces.
 *
 * The chapter cover art sits in the center, the brass ring wraps around it
 * showing playback progress. Stroke width is deliberately chunky (6dp) so the
 * brass reads at a glance from the wrist; ring track is the warm-dark outline
 * variant so the unfilled portion still has presence.
 *
 * The touch-to-scrub overlay isn't implemented in v1 — wiring scrub commands
 * back through `WearPlaybackBridge` needs a `CMD_SEEK` path on the bridge that
 * doesn't exist yet. Filed as a follow-up; for now the ring is read-only.
 * Touching the ring is reserved (no-op) so we don't accidentally swallow a
 * swipe-to-dismiss gesture before the seek protocol lands.
 *
 * @param progress 0f..1f scrub position, typically from
 *   [in.jphe.storyvox.playback.scrubProgress].
 * @param indeterminate when true (buffering), animates a sweep around the ring
 *   instead of a frozen progress arc — matches the phone's buffering spinner.
 */
@Composable
fun CircularScrubber(
    progress: Float,
    modifier: Modifier = Modifier,
    indeterminate: Boolean = false,
    strokeWidth: Dp = 6.dp,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (indeterminate) {
            CircularProgressIndicator(
                modifier = Modifier.fillMaxSize(),
                indicatorColor = BrassPrimary,
                trackColor = BrassRingTrack,
                strokeWidth = strokeWidth,
            )
        } else {
            CircularProgressIndicator(
                progress = progress.coerceIn(0f, 1f),
                modifier = Modifier.fillMaxSize(),
                indicatorColor = BrassPrimary,
                trackColor = BrassRingTrack,
                strokeWidth = strokeWidth,
                startAngle = 270f,
            )
        }
        // Cover artwork sits inside the ring with a small inset so the brass
        // doesn't visually graze the artwork edge.
        Box(
            modifier = Modifier
                .padding(strokeWidth * 2)
                .fillMaxSize()
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}
