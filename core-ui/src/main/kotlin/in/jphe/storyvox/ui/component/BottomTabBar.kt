package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Immutable
enum class HomeTab(val label: String, val filled: ImageVector, val outlined: ImageVector) {
    Playing("Playing", Icons.Filled.Headphones, Icons.Outlined.Headphones),
    Library("Library", Icons.Filled.AutoStories, Icons.Outlined.AutoStories),
    Follows("Follows", Icons.Filled.Bookmarks, Icons.Outlined.Bookmarks),
    Browse("Browse", Icons.Filled.Explore, Icons.Outlined.Explore),
    Settings("Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
}

/**
 * Issue #280 — custom bottom navigation with a single sliding indicator
 * pill that animates between tabs rather than fading per-item.
 *
 * Material 3's `NavigationBar` + `NavigationBarItem` defaults render an
 * indicator pill **per item**, with the selected item's pill fading in
 * and the unselected items' pills fading out. The visual effect reads
 * as a "pop" — even with M3's built-in fade duration, there's no shared
 * element morphing between tab positions, which is the convention users
 * expect from Apple Music / Spotify / Pocket Casts.
 *
 * The fix here is to drop M3's NavigationBar entirely and lay out the
 * bar as `BoxWithConstraints(Row(tabs), <sliding indicator>)`. The
 * indicator is a single Box whose `offset.x` is driven by
 * `animateDpAsState(targetValue = cellWidth * selectedIdx + ...)`. One
 * shared element, slides across all tab cells with a 280ms ease.
 *
 * `BoxWithConstraints` is required because per-cell width is derived
 * from the parent's measured pixel width — we can't hard-code it
 * (foldable hinge, tablet portrait/landscape, large fonts all change
 * the available width). The constraints subcompose costs one extra
 * measurement pass but is bounded by the bar's 80dp height.
 */
@Composable
fun BottomTabBar(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = HomeTab.entries
    val selectedIndex = tabs.indexOf(selected).coerceAtLeast(0)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                // Pad above the Android system navigation gesture/bar so
                // the tab row doesn't get covered by Samsung's three-button
                // nav (or the gesture pill). M3's NavigationBar does this
                // automatically; the custom layout has to opt in.
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(BAR_HEIGHT),
        ) {
            val cellWidthDp = with(LocalDensity.current) {
                (constraints.maxWidth.toFloat() / tabs.size).toDp()
            }
            // Center the indicator pill horizontally within the cell —
            // the pill is narrower than a cell so the math is
            // `cellLeft + (cellWidth - pillWidth) / 2`.
            val indicatorXTarget = cellWidthDp * selectedIndex +
                (cellWidthDp - INDICATOR_WIDTH) / 2
            val indicatorX by animateDpAsState(
                targetValue = indicatorXTarget,
                animationSpec = tween(
                    durationMillis = SLIDE_DURATION_MS,
                    easing = FastOutSlowInEasing,
                ),
                label = "nav-indicator-slide",
            )

            // Indicator behind the icons. Brass primary-container fill,
            // same shape M3 uses for its per-item pill (32dp rounded
            // rect). Positioned at the M3 default indicator vertical
            // offset (~12dp from the top, leaving ~36dp for icon + 16dp
            // padding + 16dp label below).
            Box(
                modifier = Modifier
                    .offset(x = indicatorX, y = INDICATOR_TOP_OFFSET)
                    .size(INDICATOR_WIDTH, INDICATOR_HEIGHT)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(INDICATOR_HEIGHT / 2),
                    ),
            )

            // Tab row in front of the indicator. Each cell handles its
            // own clickable + ripple; the indicator is purely visual.
            Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                tabs.forEach { tab ->
                    TabCell(
                        tab = tab,
                        isSelected = tab == selected,
                        onClick = { onSelect(tab) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TabCell(
    tab: HomeTab,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Inner column to wrap icon + label tightly; the outer Column
        // centers this group inside the cell.
        Box(
            modifier = Modifier
                .size(width = ICON_TARGET_WIDTH, height = INDICATOR_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isSelected) tab.filled else tab.outlined,
                contentDescription = tab.label,
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private val BAR_HEIGHT = 80.dp

/** M3-ish indicator pill: 64dp wide × 32dp tall, rounded ends. Wide
 *  enough to hug the 24dp Material icon with ~20dp of slack on each
 *  side; matches the visual weight of the existing per-item indicator. */
private val INDICATOR_WIDTH = 64.dp
private val INDICATOR_HEIGHT = 32.dp

/** Vertical offset of the indicator from the top of the bar. Leaves
 *  ~12dp above the icon and ~36dp below for label + bottom padding. */
private val INDICATOR_TOP_OFFSET = 12.dp

/** Same 24dp Material icon size; the surrounding Box gives the icon
 *  a hit target matching the indicator pill so taps near the icon
 *  edges still trigger the click. */
private val ICON_TARGET_WIDTH = 64.dp

/** Slide duration. 280ms with FastOutSlowInEasing matches Material's
 *  motion-medium-1 token — slow enough to read as "I'm navigating",
 *  fast enough to feel responsive. */
private const val SLIDE_DURATION_MS = 280
