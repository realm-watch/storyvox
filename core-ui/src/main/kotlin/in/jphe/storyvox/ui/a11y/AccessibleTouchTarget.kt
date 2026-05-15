package `in`.jphe.storyvox.ui.a11y

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Accessibility scaffold (Phase 2, v0.5.43, #486 / #479) — composition
 * local for "the user wants enlarged touch targets right now."
 *
 * MainActivity wires this from
 * `pref_a11y_larger_touch_targets || accessibilityState.isSwitchAccessActive`.
 * Consumer sites apply [accessibleTouchTarget] / [accessibleSize] /
 * [accessibleMinSize] to opt their tap target into the enlargement
 * when the flag is on.
 *
 * Default `false` — previews and tests that don't wire a provider
 * see the M3 default 48dp surface, which is what every screen looked
 * like before the Accessibility subscreen shipped.
 */
val LocalAccessibleTouchTargets = staticCompositionLocalOf { false }

/** Standard widened tap target — replaces 48dp sites with 64dp when on. */
const val ACCESSIBLE_TOUCH_TARGET_DP = 64

/**
 * Tap-surface widener (#486 Phase 2, #479).
 *
 * When [enlarged] is true (typically `LocalAccessibleTouchTargets.current`),
 * forces the minimum interactive surface to [target]; otherwise leaves
 * the modifier chain unchanged.
 *
 * Use at clickable wrap sites where the visual target is below 48dp —
 * a `Modifier.sizeIn(minWidth = target, minHeight = target)` keeps the
 * visual size intact but expands the tap surface beneath it. For
 * IconButton-style sites where the visual size IS the tap surface
 * (the icon scales with the button), use [accessibleSize] instead so
 * the icon resizes too.
 */
fun Modifier.accessibleTouchTarget(
    enlarged: Boolean,
    target: Dp = ACCESSIBLE_TOUCH_TARGET_DP.dp,
): Modifier = if (enlarged) {
    this.then(Modifier.sizeIn(minWidth = target, minHeight = target))
} else {
    this
}

/**
 * Replacement for `Modifier.size(48.dp)` at clickable sites — when the
 * enlarged-targets flag is on, swaps to [enlarged] (default 64dp);
 * otherwise applies [base].
 *
 * Use for buttons where the visual SIZE = the tap surface (IconButton
 * sized via `.size(40.dp)` etc.). For sites where you want to keep
 * the visual size and only expand the tap area, prefer
 * [accessibleTouchTarget].
 */
fun Modifier.accessibleSize(
    enlargedFlag: Boolean,
    base: Dp,
    enlarged: Dp = ACCESSIBLE_TOUCH_TARGET_DP.dp,
): Modifier = this.then(Modifier.size(if (enlargedFlag) enlarged else base))

/**
 * Apply a minimum interactive surface, scaling up when the enlarged
 * flag is on. Use at sites where M3's
 * `Modifier.minimumInteractiveComponentSize()` (which is fixed at
 * 48dp by the LocalMinimumInteractiveComponentEnforcement system) is
 * already in play — this is the storyvox-aware equivalent that
 * widens the minimum to 64dp under Switch Access / the user toggle.
 */
fun Modifier.accessibleMinSize(
    enlargedFlag: Boolean,
    base: Dp = 48.dp,
    enlarged: Dp = ACCESSIBLE_TOUCH_TARGET_DP.dp,
): Modifier = this.then(
    Modifier.sizeIn(
        minWidth = if (enlargedFlag) enlarged else base,
        minHeight = if (enlargedFlag) enlarged else base,
    ),
)
