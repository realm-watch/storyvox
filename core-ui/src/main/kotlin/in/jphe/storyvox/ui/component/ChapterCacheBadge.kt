package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.playback.cache.ChapterCacheState

/**
 * PR-H (#86) — small badge inside a [ChapterCard] showing the chapter's
 * PCM cache state for the active voice. Three visual states keyed off
 * [ChapterCacheState]:
 *
 *  - [ChapterCacheState.None]: composable renders nothing — zero
 *    footprint inside the parent `Row(spacedBy)` so empty-cache
 *    chapters look identical to pre-PR-H rows.
 *  - [ChapterCacheState.Partial]: pulsing
 *    [Icons.Outlined.HourglassTop], primary (brass) tint, alpha
 *    animating 0.4 ↔ 1.0 on a 700 ms half-cycle (1.4 s round-trip).
 *    Reads as "render in progress; will be ready soon."
 *  - [ChapterCacheState.Complete]: solid [Icons.Filled.Bolt], primary
 *    tint, no animation. Reads as "tap play and it starts instantly,
 *    no warm-up." Lightning over filled-disc because the *experience*
 *    the badge promises is speed-of-first-byte, not literal "data on
 *    disk" — see PR-H plan open-question #1 for the alternatives.
 *
 * Material Icons primitives only — no asset adds, no theme glyphs to
 * regenerate. Both icons inherit the Library Nocturne brass-on-warm-
 * dark palette through `MaterialTheme.colorScheme.primary`, which
 * resolves to the same brass tone used by the existing OfflineBolt /
 * CheckCircle icons in the chapter row.
 *
 * Accessibility: each state writes a `contentDescription` via the
 * Icon's own slot AND via a `semantics` block on the modifier, so
 * TalkBack reads the state independently when focused on the badge
 * AND as part of the parent row's merged description. The parent row
 * description appends a cache clause (`", cached, plays instantly"` /
 * `", caching in progress"`) — see [ChapterCard].
 */
@Composable
fun ChapterCacheBadge(
    state: ChapterCacheState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        // Render nothing — the parent Row's `Arrangement.spacedBy(...)`
        // won't budget space for an absent composable, so an empty-
        // cache chapter row collapses to its pre-PR-H width.
        ChapterCacheState.None -> Unit

        ChapterCacheState.Partial -> {
            // Pulse alpha 0.4 ↔ 1.0 over a 700 ms half-cycle (RepeatMode
            // .Reverse → 1.4 s round-trip). Slow enough to read as
            // ambient rather than as a notification flash; fast enough
            // to clearly differentiate from a static brass icon. The
            // `rememberInfiniteTransition` label feeds Compose's
            // inspection tools (e.g. layout inspector animation
            // timelines) — naming both the transition and its child
            // animateFloat keeps debugging readable.
            val transition = rememberInfiniteTransition(label = "pcm-cache-pulse")
            val alpha by transition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 700),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "pcm-cache-pulse-alpha",
            )
            Icon(
                imageVector = Icons.Outlined.HourglassTop,
                contentDescription = "Caching in progress",
                tint = MaterialTheme.colorScheme.primary,
                modifier = modifier
                    .size(20.dp)
                    .alpha(alpha)
                    .semantics { contentDescription = "Caching in progress" },
            )
        }

        ChapterCacheState.Complete -> {
            Icon(
                imageVector = Icons.Filled.Bolt,
                contentDescription = "Cached for instant play",
                tint = MaterialTheme.colorScheme.primary,
                modifier = modifier
                    .size(20.dp)
                    .semantics { contentDescription = "Cached for instant play" },
            )
        }
    }
}
