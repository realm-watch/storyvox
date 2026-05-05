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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme
import `in`.jphe.storyvox.navigation.DeepLinkResolver
import `in`.jphe.storyvox.navigation.StoryvoxNavHost

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialIntent = intent
        setContent {
            LibraryNocturneTheme {
                val navController = rememberNavController()
                var pendingIntent by remember { mutableStateOf<Intent?>(initialIntent) }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                ) { /* No-op: the user's choice persists; we'll just lack a notification if denied. */ }

                LaunchedEffect(Unit) {
                    maybeRequestNotificationPermission(notificationPermissionLauncher::launch)
                }

                LaunchedEffect(pendingIntent) {
                    pendingIntent?.let { i ->
                        DeepLinkResolver.resolve(i)?.let { route ->
                            navController.navigate(route)
                        }
                        pendingIntent = null
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
        // Intent picked up on next composition via setContent state.
        // For deep-link-after-launch we'd hoist a SharedFlow; v1 only handles cold-start deep links.
    }
}
