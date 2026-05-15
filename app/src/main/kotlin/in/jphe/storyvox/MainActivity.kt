package `in`.jphe.storyvox

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import androidx.compose.foundation.isSystemInDarkTheme
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import `in`.jphe.storyvox.feature.api.ReadingDirection
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.SpeakChapterMode
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.feature.settings.AccessibilityState
import `in`.jphe.storyvox.feature.settings.AccessibilityStateBridge
import `in`.jphe.storyvox.ui.a11y.A11ySpeakChapterMode
import `in`.jphe.storyvox.ui.a11y.LocalA11ySpeakChapterMode
import `in`.jphe.storyvox.ui.a11y.LocalAccessibleTouchTargets
import `in`.jphe.storyvox.ui.a11y.LocalIsTalkBackActive
import `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme
import `in`.jphe.storyvox.navigation.DeepLinkResolver
import `in`.jphe.storyvox.navigation.StoryvoxNavHost
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Hot stream of incoming intents. Cold-start intent is seeded in
     * [onCreate]; subsequent intents (e.g. notification taps via
     * `MediaSession.setSessionActivity`) update via [onNewIntent].
     * The Compose layer collects this and runs the deep-link resolver.
     */
    private val intentFlow = MutableStateFlow<Intent?>(null)

    /**
     * Issue #412 — the user's [ThemeOverride] preference (System / Dark /
     * Light) must reach [LibraryNocturneTheme] so the renderer honors the
     * Settings → Reading → Theme picker. Before this Hilt injection the
     * theme wrapper defaulted to `isSystemInDarkTheme()` and the saved
     * preference was purely cosmetic (showed checked in Settings, never
     * applied).
     *
     * Issue #409 — wrapped in [Lazy] so the 20-arg [SettingsRepositoryUi]
     * graph isn't materialised inside `@AndroidEntryPoint` activity
     * injection (which runs on the main thread during `super.onCreate`).
     * We resolve it inside [setContent]'s lambda, which Compose already
     * runs after the first measure pass and on a frame that's allowed
     * to take its time. On the Helio P22T tablet that lift was a
     * material chunk of cold-launch wall-clock.
     */
    @Inject lateinit var settingsRepo: Lazy<SettingsRepositoryUi>

    /**
     * Accessibility scaffold (Phase 2, #486) — live snapshot of the
     * assistive services Android reports as active for this process.
     * Folded into the theme decision so TalkBack-active users land on
     * the high-contrast variant automatically (unless they've toggled
     * it off), Switch-Access-active users land with widened tap
     * targets, and OS-level "Remove animations" reaches the same
     * [LocalReducedMotion] CompositionLocal as the per-app pref.
     */
    @Inject lateinit var accessibilityStateBridge: AccessibilityStateBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ forces edge-to-edge by default for apps targeting
        // API 35; the legacy windowOptOutEdgeToEdgeEnforcement escape
        // hatch is gone for API 36. Opting in deliberately (#145) so
        // status/nav bar transparency is consistent across versions
        // and Compose draws under both bars. Per-screen content uses
        // Modifier.systemBarsPadding() (or Scaffold's default insets)
        // to keep transport/FAB/headers off the bars.
        enableEdgeToEdge()
        intentFlow.value = intent
        setContent {
            // #412 — collect the user's theme preference + map it to
            // the explicit darkTheme boolean LibraryNocturneTheme reads.
            // System falls back to isSystemInDarkTheme(); Dark and Light
            // force the corresponding palette regardless of device
            // setting. Without this, the Settings → Reading → Theme
            // picker was cosmetic-only.
            //
            // #409 — wrap the [Lazy] resolution in a flow so the actual
            // graph construction happens once, lazily, off the
            // composition-creation hot path. The initial `null` keeps
            // first-frame rendering on the system-theme fallback while
            // the real preference loads.
            val settingsFlow = remember {
                flow { emitAll(settingsRepo.get().settings) }
            }
            val settings by settingsFlow.collectAsState(initial = null)
            // #486 Phase 2 — live accessibility-service state. Folded
            // with the user's explicit pref so the effective flag is
            // `pref || detected`. The bridge defaults to all-false
            // before its first emission, so the first frame on cold
            // start matches the stored-pref-only state.
            val a11yStateFlow = remember {
                flow { emitAll(accessibilityStateBridge.state) }
            }
            val a11yState by a11yStateFlow.collectAsState(initial = AccessibilityState())
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (settings?.themeOverride ?: ThemeOverride.System) {
                ThemeOverride.System -> systemDark
                ThemeOverride.Dark -> true
                ThemeOverride.Light -> false
            }

            // #486 — effective accessibility-adapt flags. Each is
            // `user explicit pref OR detected service state`. Users
            // who have explicitly turned a toggle off can still pull
            // their TalkBack pref to false, but the bridge will flip
            // it back ON the next time TalkBack lights up; that's
            // the design call ("auto-on when assistive service
            // detected" — the prefs are the user's explicit intent,
            // the detected state lives alongside, and the adapter
            // uses the OR fold).
            val useHighContrast = (settings?.a11yHighContrast ?: false) ||
                a11yState.isTalkBackActive
            val effectiveReducedMotion = (settings?.a11yReducedMotion ?: false) ||
                a11yState.isReduceMotionRequested
            val effectiveLargerTouchTargets = (settings?.a11yLargerTouchTargets ?: false) ||
                a11yState.isSwitchAccessActive
            val effectiveFontScale = settings?.a11yFontScaleOverride ?: 1.0f
            val readingDirection = settings?.a11yReadingDirection ?: ReadingDirection.FollowSystem

            LibraryNocturneTheme(
                darkTheme = darkTheme,
                useHighContrast = useHighContrast,
                reducedMotion = effectiveReducedMotion,
                fontScale = effectiveFontScale,
            ) {
                val navController = rememberNavController()
                val pending by intentFlow.collectAsState()

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                ) { /* No-op: the user's choice persists; we'll just lack a notification if denied. */ }

                LaunchedEffect(Unit) {
                    maybeRequestNotificationPermission(notificationPermissionLauncher::launch)
                }

                LaunchedEffect(pending) {
                    pending?.let { i ->
                        // Wait until StoryvoxNavHost has attached its graph + pushed
                        // the start destination — otherwise navigate() throws
                        // "Navigation graph has not been set". This is a race on
                        // cold-start from a notification tap, where the LaunchedEffect
                        // body runs before NavHost finishes composing.
                        snapshotFlow { navController.currentBackStackEntry }
                            .first { it != null }
                        DeepLinkResolver.resolve(i)?.let { route ->
                            navController.navigate(route)
                        }
                        intentFlow.value = null
                    }
                }

                // #486 Phase 2 — RTL/LTR override + widened-targets
                // CompositionLocal. The override only kicks in when
                // the user has explicitly chosen a non-FollowSystem
                // direction; otherwise Compose's parent-provided
                // layout direction (from locale config) wins.
                val forcedDirection: LayoutDirection? = when (readingDirection) {
                    ReadingDirection.FollowSystem -> null
                    ReadingDirection.ForceLtr -> LayoutDirection.Ltr
                    ReadingDirection.ForceRtl -> LayoutDirection.Rtl
                }
                // #486 — chapter-header readout pref + TalkBack-active
                // flag mirrored into `:core-ui` CompositionLocals so
                // [ChapterCard] (and any other widget that builds a
                // long-form content-description for TalkBack) can
                // branch without depending on `:feature`.
                val speakChapterMode = when (settings?.a11ySpeakChapterMode ?: SpeakChapterMode.Both) {
                    SpeakChapterMode.Both -> A11ySpeakChapterMode.Both
                    SpeakChapterMode.NumbersOnly -> A11ySpeakChapterMode.NumbersOnly
                    SpeakChapterMode.TitlesOnly -> A11ySpeakChapterMode.TitlesOnly
                }
                val providers = buildList {
                    add(LocalAccessibleTouchTargets provides effectiveLargerTouchTargets)
                    add(LocalA11ySpeakChapterMode provides speakChapterMode)
                    add(LocalIsTalkBackActive provides a11yState.isTalkBackActive)
                    if (forcedDirection != null) {
                        add(LocalLayoutDirection provides forcedDirection)
                    }
                }.toTypedArray()
                CompositionLocalProvider(values = providers) {
                    StoryvoxNavHost(navController = navController)
                }
            }
        }
    }

    /**
     * On Android 13+ (API 33+), POST_NOTIFICATIONS is a runtime permission. Without
     * it the foreground-service notification still keeps the service alive but is
     * invisible to the user — which means no lock-screen tile and no transport.
     * We ask once on first launch; the system handles the "don't ask again" state.
     */
    private fun maybeRequestNotificationPermission(launch: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentFlow.value = intent
    }
}
