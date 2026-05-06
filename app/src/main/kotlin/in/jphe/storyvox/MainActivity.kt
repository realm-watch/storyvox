package `in`.jphe.storyvox

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme
import `in`.jphe.storyvox.navigation.DeepLinkResolver
import `in`.jphe.storyvox.navigation.StoryvoxNavHost
import kotlinx.coroutines.flow.MutableStateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Hot stream of incoming intents. Cold-start intent is seeded in
     * [onCreate]; subsequent intents (e.g. notification taps via
     * `MediaSession.setSessionActivity`) update via [onNewIntent].
     * The Compose layer collects this and runs the deep-link resolver.
     */
    private val intentFlow = MutableStateFlow<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intentFlow.value = intent
        setContent {
            LibraryNocturneTheme {
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
                        DeepLinkResolver.resolve(i)?.let { route ->
                            navController.navigate(route)
                        }
                        // Clear so re-emission of the same intent doesn't re-navigate.
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
