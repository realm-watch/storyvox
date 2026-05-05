package `in`.jphe.storyvox

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Intent picked up on next composition via setContent state.
        // For deep-link-after-launch we'd hoist a SharedFlow; v1 only handles cold-start deep links.
    }
}
