package `in`.jphe.storyvox.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import `in`.jphe.storyvox.auth.AuthWebViewScreen
import `in`.jphe.storyvox.feature.browse.BrowseScreen
import `in`.jphe.storyvox.feature.fiction.FictionDetailScreen
import `in`.jphe.storyvox.feature.follows.FollowsScreen
import `in`.jphe.storyvox.feature.library.LibraryScreen
import `in`.jphe.storyvox.feature.reader.HybridReaderScreen
import `in`.jphe.storyvox.feature.settings.SettingsScreen
import `in`.jphe.storyvox.feature.settings.VoicePickerScreen
import `in`.jphe.storyvox.ui.component.BottomTabBar
import `in`.jphe.storyvox.ui.component.HomeTab

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

    private val HOME_ROUTES = setOf(LIBRARY, FOLLOWS, BROWSE, SETTINGS)
    fun isHome(route: String?) = route in HOME_ROUTES
}

@Composable
fun StoryvoxNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val showBottomBar = StoryvoxRoutes.isHome(currentRoute)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                BottomTabBar(
                    selected = when (currentRoute) {
                        StoryvoxRoutes.FOLLOWS -> HomeTab.Follows
                        StoryvoxRoutes.BROWSE -> HomeTab.Browse
                        StoryvoxRoutes.SETTINGS -> HomeTab.Settings
                        else -> HomeTab.Library
                    },
                    onSelect = { tab ->
                        val target = when (tab) {
                            HomeTab.Library -> StoryvoxRoutes.LIBRARY
                            HomeTab.Follows -> StoryvoxRoutes.FOLLOWS
                            HomeTab.Browse -> StoryvoxRoutes.BROWSE
                            HomeTab.Settings -> StoryvoxRoutes.SETTINGS
                        }
                        if (target != currentRoute) {
                            navController.navigate(target) {
                                popUpTo(StoryvoxRoutes.LIBRARY) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = StoryvoxRoutes.LIBRARY,
            modifier = Modifier.padding(padding),
        ) {
            composable(StoryvoxRoutes.LIBRARY) {
                LibraryScreen(
                    onOpenFiction = { id -> navController.navigate(StoryvoxRoutes.fictionDetail(id)) },
                    onOpenReader = { f, c -> navController.navigate(StoryvoxRoutes.reader(f, c)) },
                )
            }
            composable(StoryvoxRoutes.FOLLOWS) {
                FollowsScreen(
                    onOpenFiction = { id -> navController.navigate(StoryvoxRoutes.fictionDetail(id)) },
                    onOpenSignIn = { navController.navigate(StoryvoxRoutes.AUTH_WEBVIEW) },
                )
            }
            composable(StoryvoxRoutes.BROWSE) {
                BrowseScreen(
                    onOpenFiction = { id -> navController.navigate(StoryvoxRoutes.fictionDetail(id)) },
                )
            }

            composable(
                route = StoryvoxRoutes.FICTION_DETAIL,
                arguments = listOf(navArgument("fictionId") { type = NavType.StringType }),
            ) {
                FictionDetailScreen(
                    onOpenReader = { f, c -> navController.navigate(StoryvoxRoutes.reader(f, c)) },
                )
            }

            composable(
                route = StoryvoxRoutes.READER,
                arguments = listOf(
                    navArgument("fictionId") { type = NavType.StringType },
                    navArgument("chapterId") { type = NavType.StringType },
                ),
            ) {
                HybridReaderScreen(
                    onPickVoice = { navController.navigate(StoryvoxRoutes.SETTINGS_VOICE) },
                )
            }

            composable(
                route = StoryvoxRoutes.AUDIOBOOK,
                arguments = listOf(
                    navArgument("fictionId") { type = NavType.StringType },
                    navArgument("chapterId") { type = NavType.StringType },
                ),
            ) {
                HybridReaderScreen(
                    onPickVoice = { navController.navigate(StoryvoxRoutes.SETTINGS_VOICE) },
                )
            }

            composable(StoryvoxRoutes.SETTINGS) {
                SettingsScreen(
                    onOpenVoicePicker = { navController.navigate(StoryvoxRoutes.SETTINGS_VOICE) },
                    onOpenSignIn = { navController.navigate(StoryvoxRoutes.AUTH_WEBVIEW) },
                )
            }
            composable(StoryvoxRoutes.SETTINGS_VOICE) {
                VoicePickerScreen(
                    onPicked = { navController.popBackStack() },
                )
            }
            composable(StoryvoxRoutes.AUTH_WEBVIEW) {
                AuthWebViewScreen(
                    onSignedIn = { navController.popBackStack() },
                    onCancelled = { navController.popBackStack() },
                )
            }
        }
    }
}

object DeepLinkResolver {
    private val FICTION_PATH = Regex("^/fiction/(\\d+)(/.*)?$")

    /** Extras the playback notification's tap intent carries — see
     *  [in.jphe.storyvox.playback.StoryvoxPlaybackService] session activity. */
    const val EXTRA_OPEN_READER_FICTION_ID = "storyvox.open_reader.fiction_id"
    const val EXTRA_OPEN_READER_CHAPTER_ID = "storyvox.open_reader.chapter_id"

    fun resolve(intent: Intent): String? {
        // Notification tap → reader for the currently-playing chapter.
        val rf = intent.getStringExtra(EXTRA_OPEN_READER_FICTION_ID)
        val rc = intent.getStringExtra(EXTRA_OPEN_READER_CHAPTER_ID)
        if (!rf.isNullOrBlank() && !rc.isNullOrBlank()) {
            return StoryvoxRoutes.reader(rf, rc)
        }
        // Open-with deep link from royalroad.com.
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
