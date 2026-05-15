package `in`.jphe.storyvox.di

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.feature.settings.AccessibilityState
import `in`.jphe.storyvox.feature.settings.AccessibilityStateBridge
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Accessibility scaffold (Phase 1, v0.5.42) — Hilt bindings for the
 * [AccessibilityStateBridge] interface declared in `:feature`.
 *
 * The implementation reads from [AccessibilityManager] for the
 * TalkBack / Switch Access flags and from [Settings.Global.ANIMATOR_DURATION_SCALE]
 * for the reduce-motion flag, conflating both into a single
 * [AccessibilityState] hot stream.
 *
 * Phase 1 surface only — no consumers in storyvox today. The bridge
 * exists so Phase 2 agents (high-contrast theme adapter, TalkBack
 * auto-adapt, reduced-motion enforcer) have a stable contract to read
 * against. None of those agents land in this PR; the bridge is
 * deliberately built ahead of them so Phase 2 work is a pure consumer-
 * side change, no re-plumbing required.
 *
 * Threading note: [callbackFlow] runs the listener on the collecting
 * coroutine's context. Consumers should collect on the main dispatcher
 * if they're going to feed the state into Compose state, since
 * `AccessibilityManager.addAccessibilityStateChangeListener` invokes
 * the listener on whatever thread Android decides — typically the main
 * thread, but not guaranteed across OEM forks.
 */
@Module
@InstallIn(SingletonComponent::class)
object AccessibilityModule {

    @Provides
    @Singleton
    fun provideAccessibilityStateBridge(
        @ApplicationContext context: Context,
    ): AccessibilityStateBridge = RealAccessibilityStateBridge(context)
}

/**
 * Phase 1 [AccessibilityStateBridge] impl backed by Android's
 * [AccessibilityManager] and a one-shot read of
 * [Settings.Global.ANIMATOR_DURATION_SCALE].
 *
 * Limitations (intentional for Phase 1 — addressed in Phase 2 if a
 * consumer actually needs them):
 *  - The reduce-motion flag is snapshotted once per emission. A
 *    Phase 2 consumer that needs live tracking of the OS-level
 *    "Remove animations" setting can layer a
 *    [`android.database.ContentObserver`] on top of this Flow; for
 *    Phase 1 the listener-driven re-emission on a11y service change
 *    is good enough to refresh the value at the moments users care
 *    about (right after they toggle TalkBack from the system shade).
 *  - Switch Access detection looks for any
 *    [AccessibilityServiceInfo] with the [FLAG_REQUEST_FILTER_KEY_EVENTS]
 *    capability bit. AOSP's Switch Access ships with this set; the
 *    same heuristic also lights up other filter-key services like
 *    voice control add-ons, which is the correct effective behavior
 *    for "should we enlarge touch targets" — both Switch Access and
 *    voice-controlled input benefit from larger targets.
 */
internal class RealAccessibilityStateBridge(
    private val context: Context,
) : AccessibilityStateBridge {

    private val accessibilityManager: AccessibilityManager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

    /**
     * Issue #409 — bridge scope owns the listener subscription so the
     * first frame doesn't pay for `getEnabledAccessibilityServiceList`
     * (a binder call into system_server). v0.5.43's MainActivity wraps
     * this bridge's flow in `flow { emitAll(state) }.collectAsState(...)`,
     * which made the upstream `callbackFlow`'s `trySend(readSnapshot())`
     * fire on the Compose Main dispatcher during the first composition.
     * Samsung tablets with vendor a11y services (Voice Assistant, etc.)
     * route that binder call through several listeners and the round-
     * trip on the Helio P22T is roughly 250-400 ms — well into the
     * "first frame is jank" budget.
     *
     * Switching to a hot [StateFlow] backed by an IO-dispatched
     * SharingStarted.Eagerly subscription means:
     *  - The initial snapshot is computed on Dispatchers.IO at provider
     *    construction time (Hilt singleton scope; runs immediately
     *    after Hilt builds the graph, but on IO not Main).
     *  - Subsequent collectors get the cached value synchronously and
     *    never re-run `readSnapshot()` on their own thread.
     *  - The AccessibilityManager listener still re-emits on change,
     *    just on IO via the bridge scope.
     */
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val state: StateFlow<AccessibilityState> = callbackFlow {
        // Seed the flow with the current snapshot — consumers don't
        // have to wait for the first listener fire (which only
        // happens on actual change) before getting a value.
        trySend(readSnapshot())

        val listener = AccessibilityManager.AccessibilityStateChangeListener {
            // The bool the listener receives only reflects the master
            // "accessibility services enabled" toggle — re-read the
            // full snapshot so individual service deltas (TalkBack
            // toggled but Switch Access still off, etc.) are correct.
            trySend(readSnapshot())
        }
        accessibilityManager.addAccessibilityStateChangeListener(listener)
        awaitClose {
            accessibilityManager.removeAccessibilityStateChangeListener(listener)
        }
    }
        .onStart { /* no-op anchor for Phase 2 consumers to layer on */ }
        .distinctUntilChanged()
        // Issue #409 — keep the upstream subscription alive across all
        // consumers so `trySend(readSnapshot())` runs exactly once on
        // bridge construction (on Dispatchers.IO via [bridgeScope]),
        // not once per composer re-collect on the Main dispatcher.
        // `Eagerly` means the snapshot is computed at provider-build
        // time; the all-false [AccessibilityState] default keeps the
        // first frame on a known-safe path until the real read lands.
        .stateIn(
            scope = bridgeScope,
            started = SharingStarted.Eagerly,
            initialValue = AccessibilityState(),
        )

    private fun readSnapshot(): AccessibilityState {
        val enabledServices = accessibilityManager
            .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?: emptyList()

        // TalkBack-shaped: any enabled service whose feedback type
        // includes FEEDBACK_SPOKEN (the screen-reader feedback class).
        // Filtering on the capability bit rather than a package name
        // is forward-compatible with OEM screen readers and side-
        // loaded alternatives.
        val talkBackActive = enabledServices.any { svc ->
            (svc.feedbackType and android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN) != 0
        }

        // Switch-Access-shaped: any enabled service with the
        // FLAG_REQUEST_FILTER_KEY_EVENTS capability. This is the
        // canonical Switch Access flag; other filter-key services
        // (voice control add-ons) light up too, which is correct
        // effective behavior for "should touch targets be bigger".
        val switchAccessActive = enabledServices.any { svc ->
            (svc.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0
        }

        // Reduce-motion: Settings.Global.ANIMATOR_DURATION_SCALE = 0
        // is the canonical "Remove animations" signal. The default
        // is 1.0f; the user setting it to 0 means "no animations
        // please." Read defensively — some OEM forks let this be
        // missing, in which case we fall back to "motion allowed."
        val animatorScale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f,
            )
        }.getOrDefault(1.0f)
        val reduceMotion = animatorScale == 0.0f

        return AccessibilityState(
            isTalkBackActive = talkBackActive,
            isSwitchAccessActive = switchAccessActive,
            isReduceMotionRequested = reduceMotion,
        )
    }
}
