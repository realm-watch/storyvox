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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme
import `in`.jphe.storyvox.navigation.DeepLinkResolver
import `in`.jphe.storyvox.navigation.StoryvoxNavHost
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

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
     */
    @Inject lateinit var settingsRepo: SettingsRepositoryUi

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
            val settings by settingsRepo.settings.collectAsState(initial = null)
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (settings?.themeOverride ?: ThemeOverride.System) {
                ThemeOverride.System -> systemDark
                ThemeOverride.Dark -> true
                ThemeOverride.Light -> false
            }
            LibraryNocturneTheme(darkTheme = darkTheme) {
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

                StoryvoxNavHost(navController = navController)
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
