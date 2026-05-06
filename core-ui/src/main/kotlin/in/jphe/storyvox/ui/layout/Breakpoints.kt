package `in`.jphe.storyvox.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Lightweight breakpoint thresholds for storyvox. We intentionally avoid the
 * material3-windowsizeclass dependency — `LocalConfiguration.screenWidthDp`
 * is enough for the few decisions we make (single vs multi-column).
 *
 * Phone:    < 600dp (Galaxy S-class portrait, foldable folded)
 * Tablet:   600..839dp (Tab A7 Lite at 800dp lives here, foldable unfolded portrait)
 * Expanded: >= 840dp (Tab S-class landscape, large foldables, desktop)
 */
object Breakpoints {
    val Tablet: Dp = 600.dp
    val Expanded: Dp = 840.dp
}

@Composable
@ReadOnlyComposable
fun screenWidthDp(): Dp = LocalConfiguration.current.screenWidthDp.dp

@Composable
@ReadOnlyComposable
fun isAtLeastTablet(): Boolean = screenWidthDp() >= Breakpoints.Tablet

@Composable
@ReadOnlyComposable
fun isAtLeastExpanded(): Boolean = screenWidthDp() >= Breakpoints.Expanded
