package `in`.jphe.storyvox.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

object StoryvoxRoutes {
    const val LIBRARY = "library"
    const val FOLLOWS = "follows"
    const val BROWSE = "browse"
    const val FICTION_DETAIL = "fiction/{fictionId}"
    const val READER = "reader/{fictionId}/{chapterId}"
    const val AUDIOBOOK = "audiobook/{fictionId}/{chapterId}"
    const val SETTINGS = "settings"
    const val SETTINGS_VOICE = "settings/voice"
    const val AUTH_WEBVIEW = "auth/webview"

    fun fictionDetail(fictionId: String) = "fiction/$fictionId"
    fun reader(fictionId: String, chapterId: String) = "reader/$fictionId/$chapterId"
    fun audiobook(fictionId: String, chapterId: String) = "audiobook/$fictionId/$chapterId"
}

/**
 * Top-level navigation host. Feature screens are referenced as composables that the
 * `:feature` module will provide; for v1 scaffolding they're rendered as labelled
 * placeholders so the graph compiles before the feature module lands.
 */
@Composable
fun StoryvoxNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        NavHost(
            navController = navController,
            startDestination = StoryvoxRoutes.LIBRARY,
            modifier = Modifier.padding(padding),
        ) {
            composable(StoryvoxRoutes.LIBRARY)  { Placeholder("Library") }
            composable(StoryvoxRoutes.FOLLOWS)  { Placeholder("Follows") }
            composable(StoryvoxRoutes.BROWSE)   { Placeholder("Browse") }

            composable(
                route = StoryvoxRoutes.FICTION_DETAIL,
                arguments = listOf(navArgument("fictionId") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("fictionId").orEmpty()
                Placeholder("Fiction · $id")
            }

            composable(
                route = StoryvoxRoutes.READER,
                arguments = listOf(
                    navArgument("fictionId") { type = NavType.StringType },
                    navArgument("chapterId") { type = NavType.StringType },
                ),
            ) { entry ->
                val f = entry.arguments?.getString("fictionId").orEmpty()
                val c = entry.arguments?.getString("chapterId").orEmpty()
                Placeholder("Reader · $f / $c")
            }

            composable(
                route = StoryvoxRoutes.AUDIOBOOK,
                arguments = listOf(
                    navArgument("fictionId") { type = NavType.StringType },
                    navArgument("chapterId") { type = NavType.StringType },
                ),
            ) { entry ->
                val f = entry.arguments?.getString("fictionId").orEmpty()
                val c = entry.arguments?.getString("chapterId").orEmpty()
                Placeholder("Audiobook · $f / $c")
            }

            composable(StoryvoxRoutes.SETTINGS)        { Placeholder("Settings") }
            composable(StoryvoxRoutes.SETTINGS_VOICE)  { Placeholder("Voice picker") }
            composable(StoryvoxRoutes.AUTH_WEBVIEW)    { Placeholder("WebView login") }
        }
    }
}

@Composable
private fun Placeholder(label: String) {
    Box(modifier = Modifier.fillMaxSize()) {
        Text(text = label)
    }
}

/**
 * Resolves an incoming `VIEW` intent to a navigation route, or null if the intent
 * isn't a recognized storyvox deep link.
 *
 * Supported:
 *   https://(www.)royalroad.com/fiction/{id}[...]   →  fiction/{id}
 */
object DeepLinkResolver {
    private val FICTION_PATH = Regex("^/fiction/(\\d+)(/.*)?$")

    fun resolve(intent: Intent): String? {
        if (intent.action != Intent.ACTION_VIEW) return null
        val data: Uri = intent.data ?: return null
        if (data.scheme != "https") return null
        val host = data.host ?: return null
        if (host != "royalroad.com" && host != "www.royalroad.com") return null
        val path = data.path ?: return null
        val match = FICTION_PATH.matchEntire(path) ?: return null
        val id = match.groupValues[1]
        return StoryvoxRoutes.fictionDetail(id)
    }
}
