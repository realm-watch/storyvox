package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.theme.LocalMotion
import kotlinx.coroutines.delay

/**
 * Library Nocturne cascade — fade + soft rise as items reveal.
 *
 * The animation runs once per `key`. Stagger by item index so a fresh grid
 * pours in like candlelight catching pages, instead of all snapping at once.
 *
 * Pass [LocalMotion.current.standardDurationMs] (default 280ms) and the
 * `standardEasing` curve from the theme — keep motion vocabulary consistent.
 *
 * @param index item index in the lazy list/grid; drives stagger timing
 * @param key  identity for the entry animation; reset to re-play (e.g. on
 *             search/filter change). Pass `Unit` to play once per composition
 *             of this item.
 * @param staggerMs delay between successive items, capped so off-screen items
 *                  catch up quickly when scrolled into view
 * @param riseFrom vertical offset items rise from
 */
fun Modifier.cascadeReveal(
    index: Int,
    key: Any = Unit,
    staggerMs: Int = 28,
    riseFrom: Dp = 10.dp,
): Modifier = composed {
    val motion = LocalMotion.current
    val density = LocalDensity.current
    val riseFromPx = with(density) { riseFrom.toPx() }

    val alpha = remember(key) { Animatable(0f) }
    val rise = remember(key) { Animatable(1f) }

    // Modulo so paged-in items at index 32, 33, ... don't all wait the
    // maximum delay; the cascade refreshes per visible page of ~12 items.
    val cappedDelay = ((index.coerceAtLeast(0) % 12) * staggerMs).toLong()

    LaunchedEffect(key) {
        if (cappedDelay > 0) delay(cappedDelay)
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(motion.standardDurationMs, easing = motion.standardEasing),
        )
    }
    LaunchedEffect(key) {
        if (cappedDelay > 0) delay(cappedDelay)
        rise.animateTo(
            targetValue = 0f,
            animationSpec = tween(motion.standardDurationMs, easing = motion.standardEasing),
        )
    }

    graphicsLayer {
        this.alpha = alpha.value
        this.translationY = rise.value * riseFromPx
    }
}
