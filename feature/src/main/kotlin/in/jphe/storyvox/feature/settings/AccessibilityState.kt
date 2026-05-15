package `in`.jphe.storyvox.feature.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Accessibility scaffold (Phase 1) — live snapshot of the assistive
 * services Android currently reports as active for this process.
 *
 * Phase 1 surface only — no consumers in storyvox today. The data
 * class exists so Phase 2 agents (TalkBack auto-adapt, high-contrast
 * theme, reduced-motion enforcer) have a stable contract to read
 * against without each agent rewriting the AccessibilityManager bridge.
 *
 * Field semantics:
 *  - [isTalkBackActive] — true when ANY screen-reader-shaped
 *    AccessibilityService is enabled (TalkBack ships with AOSP; on
 *    OEM forks the equivalent service might carry a different
 *    package). The bridge in `:app` filters on the
 *    `FEEDBACK_SPOKEN` feedback-type flag rather than a hardcoded
 *    package list so vendor-specific screen readers (Samsung Voice
 *    Assistant, etc.) are also covered.
 *  - [isSwitchAccessActive] — true when Switch Access (or an
 *    equivalent input-redirection service) is on. Detected via the
 *    `FLAG_REQUEST_FILTER_KEY_EVENTS` capability since Switch Access
 *    is the canonical filter-key-events service.
 *  - [isReduceMotionRequested] — true when the user has set
 *    `Settings.Global.ANIMATOR_DURATION_SCALE` to 0 OR the per-app
 *    accessibility "Remove animations" preference is on. Mirrors
 *    [`in.jphe.storyvox.ui.theme.LocalReducedMotion`], which the rest
 *    of the app already reads from a Compose CompositionLocal. This
 *    field exists alongside the CompositionLocal so non-Compose
 *    callers (e.g., a future synthesis-pacing rule that lives in
 *    `:core-playback`) can observe the same signal without bouncing
 *    through Compose runtime.
 *
 * Defaults are all false — safe for test fakes and previews that
 * don't wire a provider, and matches the "no assistive service
 * active" baseline.
 */
data class AccessibilityState(
    val isTalkBackActive: Boolean = false,
    val isSwitchAccessActive: Boolean = false,
    val isReduceMotionRequested: Boolean = false,
)

/**
 * Accessibility scaffold (Phase 1) — Hilt-provided bridge into the
 * platform [`android.view.accessibility.AccessibilityManager`].
 *
 * The interface lives in `:feature` (alongside the screen that
 * eventually consumes it) so test fakes and the `:app` implementation
 * can both reference it without `:feature` depending on Android
 * platform APIs directly. The real implementation in `:app` wires up
 * `addAccessibilityStateChangeListener` + an
 * [`android.database.ContentObserver`] on
 * `Settings.Global.ANIMATOR_DURATION_SCALE` and folds both into a hot
 * `StateFlow<AccessibilityState>`.
 *
 * Phase 2 consumers:
 *  - High-contrast theme adapter — reads [isTalkBackActive] and
 *    auto-enables [UiSettings.a11yHighContrast]-equivalent behavior
 *    unless the user has explicitly overridden the toggle.
 *  - Reduced-motion enforcer — folds [isReduceMotionRequested] with
 *    the user's [UiSettings.a11yReducedMotion] toggle.
 *  - Larger-touch-targets adapter — reads [isSwitchAccessActive].
 *
 * Default implementation emits a single [AccessibilityState] with all
 * fields false, so test fakes that don't override see a quiet, all-
 * off baseline.
 */
interface AccessibilityStateBridge {
    /**
     * Hot stream of [AccessibilityState] updates. Emits the current
     * state immediately on collect (so consumers don't need to seed
     * a default) and re-emits whenever any field changes.
     *
     * Implementations MUST conflate redundant emissions (same value
     * back-to-back) so a Phase 2 consumer doing a non-trivial reaction
     * (e.g. theme swap) doesn't churn on no-op listener fires.
     */
    val state: Flow<AccessibilityState>
        get() = flowOf(AccessibilityState())
}
