package `in`.jphe.storyvox.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import `in`.jphe.storyvox.auth.AuthWebViewScreen
import `in`.jphe.storyvox.auth.anthropic.AnthropicTeamsSignInScreen
import `in`.jphe.storyvox.auth.github.GitHubSignInScreen
import `in`.jphe.storyvox.source.github.auth.GitHubAuthConfig
import `in`.jphe.storyvox.feature.browse.BrowseScreen
import `in`.jphe.storyvox.feature.chat.ChatScreen
import `in`.jphe.storyvox.feature.debug.DebugScreen
import `in`.jphe.storyvox.feature.engine.VoicePickerGate
import `in`.jphe.storyvox.feature.fiction.FictionDetailScreen
import `in`.jphe.storyvox.feature.follows.FollowsScreen
import `in`.jphe.storyvox.feature.library.LibraryScreen
import `in`.jphe.storyvox.feature.reader.HybridReaderScreen
import `in`.jphe.storyvox.feature.settings.SettingsScreen
import `in`.jphe.storyvox.feature.settings.pronunciation.PronunciationDictScreen
import `in`.jphe.storyvox.feature.voicelibrary.VoiceLibraryScreen
import `in`.jphe.storyvox.ui.component.BottomTabBar
import `in`.jphe.storyvox.ui.component.HomeTab
import `in`.jphe.storyvox.ui.theme.LocalMotion
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion

object StoryvoxRoutes {
    const val PLAYING = "playing"
    const val LIBRARY = "library"
    const val FOLLOWS = "follows"
    const val BROWSE = "browse"
    const val FICTION_DETAIL = "fiction/{fictionId}"
    const val READER = "reader/{fictionId}/{chapterId}"
    const val AUDIOBOOK = "audiobook/{fictionId}/{chapterId}"
    const val SETTINGS = "settings"
    const val SETTINGS_PRONUNCIATION = "settings/pronunciation"
    const val VOICE_LIBRARY = "settings/voices"
    /** Issue #218 — Settings → AI → Sessions: review past chats and
     *  recap history, navigate back into the chat surface, delete
     *  individual sessions. */
    const val SETTINGS_AI_SESSIONS = "settings/ai/sessions"
    /** Vesper (v0.4.97) — Settings → Developer → Debug. Pipeline/engine/
     *  Azure live diagnostics + 20-event ring + clipboard export for bug
     *  reports. */
    const val SETTINGS_DEBUG = "settings/debug"
    /** Phase 3 / Plugin manager (#404) — Settings → Plugins. Registry-
     *  driven plugin manager: brass-edged card grid with toggle +
     *  capability chips + tap-for-details. */
    const val SETTINGS_PLUGINS = "settings/plugins"
    const val AUTH_WEBVIEW = "auth/webview"
    /** Issue #91 — GitHub Device Flow sign-in modal. */
    const val GITHUB_SIGN_IN = "auth/github/signin"
    /** Issue #181 — Anthropic Teams OAuth sign-in modal. */
    const val TEAMS_SIGN_IN = "auth/anthropic-teams/signin"
    /** Q&A chat about a fiction (#81 follow-up). One chat history per
     *  fictionId; the screen pulls fiction title + current chapter
     *  context internally for the system prompt.
     *
     *  Optional `prefill` query param (#188 character lookup): when the
     *  reader's long-press dialog routes here, it passes a prebuilt
     *  "Who is X?" question to seed the input field. The arg is read
     *  once on session creation by `ChatViewModel`; it does not auto-send.
     */
    const val CHAT = "chat/{fictionId}?prefill={prefill}"

    // Encode ids: GitHubSource fictionIds contain `/` (e.g. "github:owner/repo")
    // and chapterIds contain even more (e.g. "github:owner/repo:src/chapter-01.md"),
    // which break single-segment route matching. Compose Navigation auto-decodes
    // when reading args from SavedStateHandle, so encoding only at the call site is safe.
    fun fictionDetail(fictionId: String) = "fiction/${Uri.encode(fictionId)}"
    fun reader(fictionId: String, chapterId: String) = "reader/${Uri.encode(fictionId)}/${Uri.encode(chapterId)}"
    fun audiobook(fictionId: String, chapterId: String) = "audiobook/${Uri.encode(fictionId)}/${Uri.encode(chapterId)}"
    /** Build a chat route. `prefill` (optional, #188) seeds the chat
     *  input with a starter question; pass null/empty to leave the
     *  input untouched on open. The query value is percent-encoded so
     *  free-text questions ("Who is Frodo?") survive the URL roundtrip.
     *
     *  Compose Navigation matches the optional `?prefill={prefill}`
     *  template against routes with OR without the query string —
     *  omitting it when there's no prefill keeps the route minimal AND
     *  preserves the pre-existing single-segment shape that
     *  StoryvoxRoutesTest pins. */
    fun chat(fictionId: String, prefill: String? = null): String {
        val base = "chat/${Uri.encode(fictionId)}"
        return if (prefill.isNullOrEmpty()) base
        else "$base?prefill=${Uri.encode(prefill)}"
    }

    private val HOME_ROUTES = setOf(PLAYING, LIBRARY, FOLLOWS, BROWSE, VOICE_LIBRARY)
    fun isHome(route: String?) = route in HOME_ROUTES

    /** Issue #267 — Reader / Audiobook routes ARE the player surface, just
     *  reached via drill-down from a chapter row instead of via the
     *  Playing tab. Keep the bottom nav visible on those so the user can
     *  switch tabs (Browse another book, hit Library) without backing out
     *  of the player. The drill-down is still a back-stack push (so OS
     *  Back returns to the chapter list); the bottom bar is purely a
     *  cross-cutting nav surface here.
     *
     *  When a full mini-player strip lands (sibling of #267 — collapse
     *  the player into a top-of-bottom-bar mini), this set goes away and
     *  the strip becomes the always-present transport surface. Until
     *  then, "bottom nav present + player fills the body" is the
     *  Apple-Books pattern and the lowest-cost win. */
    private val PLAYER_ROUTES_WITH_BOTTOM_NAV = setOf(READER, AUDIOBOOK)
    fun showsBottomNav(route: String?): Boolean =
        isHome(route) || route in PLAYER_ROUTES_WITH_BOTTOM_NAV
}

@Composable
fun StoryvoxNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    VoicePickerGate(
        onOpenVoiceLibrary = { navController.navigate(StoryvoxRoutes.VOICE_LIBRARY) },
    ) {
        StoryvoxNavHostContent(navController, modifier)
        // Calliope (v0.5.00) — one-time graduation milestone dialog.
        // Mounted at the nav host level so it shows on top of
        // whatever the first launch landed on (Playing tab today,
        // possibly a deep-linked chapter tomorrow). The MilestoneVM
        // gates on BuildConfig.VERSION_NAME + a DataStore flag and
        // emits false-forever after first dismissal, so this composable
        // is a no-op on every launch after the first qualifying one.
        MilestoneDialogHost()
    }
}

/**
 * Thin Hilt-injection wrapper around [MilestoneDialog]. Reads the
 * `showDialog` StateFlow from [MilestoneViewModel] and renders the
 * brass thank-you card when it's true. Separate from
 * [StoryvoxNavHost] so the VM scope is the dialog's lifetime, not
 * the whole nav host.
 */
@Composable
private fun MilestoneDialogHost(
    viewModel: `in`.jphe.storyvox.feature.milestone.MilestoneViewModel =
        androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val show by viewModel.showDialog.collectAsStateWithLifecycle()
    if (show) {
        `in`.jphe.storyvox.ui.component.MilestoneDialog(
            onDismiss = viewModel::dismiss,
        )
    }
}

@Composable
private fun StoryvoxNavHostContent(
    navController: NavHostController,
    modifier: Modifier,
) {
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val showBottomBar = StoryvoxRoutes.showsBottomNav(currentRoute)

    val reducedMotion = LocalReducedMotion.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                BottomTabBar(
                    selected = when {
                        currentRoute == StoryvoxRoutes.PLAYING -> HomeTab.Playing
                        currentRoute == StoryvoxRoutes.FOLLOWS -> HomeTab.Follows
                        currentRoute == StoryvoxRoutes.BROWSE -> HomeTab.Browse
                        currentRoute == StoryvoxRoutes.VOICE_LIBRARY -> HomeTab.Voices
                        // Issue #267 — the Reader / Audiobook drill-downs ARE
                        // the player surface, so light the Playing tab while
                        // we're on them. Without this branch they'd fall
                        // through to Library, which is misleading.
                        currentRoute == StoryvoxRoutes.READER ||
                            currentRoute == StoryvoxRoutes.AUDIOBOOK -> HomeTab.Playing
                        else -> HomeTab.Library
                    },
                    onSelect = { tab ->
                        val target = when (tab) {
                            HomeTab.Playing -> StoryvoxRoutes.PLAYING
                            HomeTab.Library -> StoryvoxRoutes.LIBRARY
                            HomeTab.Follows -> StoryvoxRoutes.FOLLOWS
                            HomeTab.Browse -> StoryvoxRoutes.BROWSE
                            HomeTab.Voices -> StoryvoxRoutes.VOICE_LIBRARY
                        }
                        if (target != currentRoute) {
                            // Pop everything above the start destination so
                            // tab switches don't accumulate, then push the
                            // target. `launchSingleTop` collapses repeated
                            // taps on the active tab.
                            //
                            // Deliberately NOT using `saveState`/`restoreState`:
                            // that pair, combined with the mixed enter/exit
                            // transition types between home tabs (fade) and
                            // drill-down routes (slide), caused the NavHost
                            // transition state machine to commit the back-
                            // stack change without ever rendering the target
                            // composable — taps on Library from inside a
                            // reader chapter appeared dead even though the
                            // OS back button returned to the reader, proving
                            // the navigation had landed on the back-stack.
                            // Net loss: tab state isn't preserved across
                            // switches (e.g. you start at the top of Library
                            // each time). Net win: the nav actually renders.
                            navController.navigate(target) {
                                // Pop everything above the start destination
                                // (PLAYING) so tab switches don't pile up the
                                // back stack. Using the start route name
                                // instead of `findStartDestination().id` to
                                // avoid the extra import; equivalent result.
                                popUpTo(StoryvoxRoutes.PLAYING)
                                launchSingleTop = true
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        val motion = LocalMotion.current

        // Library Nocturne motion vocabulary for screen transitions.
        //
        // Home tabs (Playing/Library/Follows/Browse/Settings) cross-fade only — the
        // bottom bar is shared across them, and a horizontal slide would shear the
        // bar visually. Drill-down routes slide in from the right + fade, the
        // standard "push" motion; the pop reverses direction.
        //
        // When the user has "Remove animations" / "Reduce motion" on, every factory
        // is null → NavHost interprets that as no transition (instant cut), which is
        // what motion-sensitive users actually want, not a "shorter" version.
        val homeEnter: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition)? =
            if (reducedMotion) null else {
                { fadeIn(animationSpec = tween(motion.standardDurationMs, easing = motion.standardEasing)) }
            }
        val homeExit: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition)? =
            if (reducedMotion) null else {
                { fadeOut(animationSpec = tween(motion.standardDurationMs, easing = motion.standardEasing)) }
            }

        // 320ms swipe with the existing swipeEasing curve — distinct from the 280ms
        // standard so a forward navigation feels intentional, not abrupt.
        val pushEnter: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition)? =
            if (reducedMotion) null else {
                {
                    slideInHorizontally(
                        animationSpec = tween(motion.swipeDurationMs, easing = motion.swipeEasing),
                        initialOffsetX = { fullWidth -> fullWidth / 6 },
                    ) + fadeIn(animationSpec = tween(motion.swipeDurationMs, easing = motion.swipeEasing))
                }
            }
        val pushExit: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition)? =
            if (reducedMotion) null else {
                { fadeOut(animationSpec = tween(motion.swipeDurationMs, easing = motion.swipeEasing)) }
            }
        val popEnter: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition)? =
            if (reducedMotion) null else {
                { fadeIn(animationSpec = tween(motion.swipeDurationMs, easing = motion.swipeEasing)) }
            }
        val popExit: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition)? =
            if (reducedMotion) null else {
                {
                    slideOutHorizontally(
                        animationSpec = tween(motion.swipeDurationMs, easing = motion.swipeEasing),
                        targetOffsetX = { fullWidth -> fullWidth / 6 },
                    ) + fadeOut(animationSpec = tween(motion.swipeDurationMs, easing = motion.swipeEasing))
                }
            }

        NavHost(
            navController = navController,
            // Default to the Playing tab on launch — when the user
            // returns to the app they almost always want to resume what
            // they were listening to, not browse the library shelf.
            startDestination = StoryvoxRoutes.PLAYING,
            modifier = Modifier.padding(padding),
        ) {
            composable(
                StoryvoxRoutes.PLAYING,
                enterTransition = homeEnter,
                exitTransition = homeExit,
                popEnterTransition = homeEnter,
                popExitTransition = homeExit,
            ) {
                HybridReaderScreen(
                    onPickVoice = { navController.navigate(StoryvoxRoutes.VOICE_LIBRARY) },
                    onOpenAiSettings = { navController.navigate(StoryvoxRoutes.SETTINGS) },
                    onOpenSettings = { navController.navigate(StoryvoxRoutes.SETTINGS) },
                    onOpenChat = { fId, prefill -> navController.navigate(StoryvoxRoutes.chat(fId, prefill)) },
                    // ResumeEmptyPrompt's "Browse the realms" CTA — only
                    // matters when the user has no continue-listening
                    // entry at all (first launch / wiped data). The
                    // populated ResumePrompt doesn't navigate; tapping
                    // Resume reloads the chapter in place.
                    onBrowse = {
                        navController.navigate(StoryvoxRoutes.BROWSE) {
                            // Same pop-and-singleTop discipline as the
                            // bottom-tab handler so the Browse-tab back
                            // stack doesn't accumulate.
                            popUpTo(StoryvoxRoutes.PLAYING)
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(
                StoryvoxRoutes.LIBRARY,
                enterTransition = homeEnter,
                exitTransition = homeExit,
                popEnterTransition = homeEnter,
                popExitTransition = homeExit,
            ) {
                LibraryScreen(
                    onOpenFiction = { id -> navController.navigate(StoryvoxRoutes.fictionDetail(id)) },
                    onOpenReader = { f, c -> navController.navigate(StoryvoxRoutes.reader(f, c)) },
                    onOpenSettings = { navController.navigate(StoryvoxRoutes.SETTINGS) },
                    // Issue #383 — Inbox row tap deep-link. The URI is a
                    // pre-resolved `storyvox://reader/<fid>/<cid>` or
                    // `storyvox://fiction/<fid>` string. Decode here and
                    // route via the existing nav graph — same destinations
                    // as the History tap path, just chosen by URL prefix.
                    onOpenInboxLink = { uri ->
                        val readerPrefix = "storyvox://reader/"
                        val fictionPrefix = "storyvox://fiction/"
                        when {
                            uri.startsWith(readerPrefix) -> {
                                val rest = uri.removePrefix(readerPrefix)
                                val slash = rest.indexOf('/')
                                if (slash > 0) {
                                    val fid = rest.substring(0, slash)
                                    val cid = rest.substring(slash + 1)
                                    navController.navigate(StoryvoxRoutes.reader(fid, cid))
                                }
                            }
                            uri.startsWith(fictionPrefix) -> {
                                val fid = uri.removePrefix(fictionPrefix)
                                if (fid.isNotBlank()) {
                                    navController.navigate(StoryvoxRoutes.fictionDetail(fid))
                                }
                            }
                            else -> {
                                // Unknown scheme — silently ignore. The
                                // event row stays marked-read since the VM
                                // already fired markRead before this
                                // callback. Future-source URIs that we
                                // don't decode here just become quiet
                                // dismissals rather than crashes.
                            }
                        }
                    },
                )
            }
            composable(
                StoryvoxRoutes.FOLLOWS,
                enterTransition = homeEnter,
                exitTransition = homeExit,
                popEnterTransition = homeEnter,
                popExitTransition = homeExit,
            ) {
                FollowsScreen(
                    onOpenFiction = { id -> navController.navigate(StoryvoxRoutes.fictionDetail(id)) },
                    onOpenSignIn = { navController.navigate(StoryvoxRoutes.AUTH_WEBVIEW) },
                    onOpenSettings = { navController.navigate(StoryvoxRoutes.SETTINGS) },
                )
            }
            composable(
                StoryvoxRoutes.BROWSE,
                enterTransition = homeEnter,
                exitTransition = homeExit,
                popEnterTransition = homeEnter,
                popExitTransition = homeExit,
            ) {
                BrowseScreen(
                    onOpenFiction = { id -> navController.navigate(StoryvoxRoutes.fictionDetail(id)) },
                    // #241 / #211 — RR sign-in CTA shared between Browse
                    // (anonymous-listing CTA) and FictionDetail (Follow
                    // button) and Settings → Royal Road.
                    onOpenRoyalRoadSignIn = { navController.navigate(StoryvoxRoutes.AUTH_WEBVIEW) },
                    onOpenSettings = { navController.navigate(StoryvoxRoutes.SETTINGS) },
                )
            }

            composable(
                route = StoryvoxRoutes.FICTION_DETAIL,
                arguments = listOf(navArgument("fictionId") { type = NavType.StringType }),
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                FictionDetailScreen(
                    onOpenReader = { f, c -> navController.navigate(StoryvoxRoutes.reader(f, c)) },
                    onBack = { navController.popBackStack() },
                    // #211 — Follow on Royal Road button routes to the
                    // shared sign-in WebView when the user is anonymous.
                    onOpenRoyalRoadSignIn = { navController.navigate(StoryvoxRoutes.AUTH_WEBVIEW) },
                )
            }

            composable(
                route = StoryvoxRoutes.READER,
                arguments = listOf(
                    navArgument("fictionId") { type = NavType.StringType },
                    navArgument("chapterId") { type = NavType.StringType },
                ),
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                HybridReaderScreen(
                    onPickVoice = { navController.navigate(StoryvoxRoutes.VOICE_LIBRARY) },
                    onOpenAiSettings = { navController.navigate(StoryvoxRoutes.SETTINGS) },
                    onOpenSettings = { navController.navigate(StoryvoxRoutes.SETTINGS) },
                    onOpenChat = { fId, prefill -> navController.navigate(StoryvoxRoutes.chat(fId, prefill)) },
                )
            }

            composable(
                route = StoryvoxRoutes.AUDIOBOOK,
                arguments = listOf(
                    navArgument("fictionId") { type = NavType.StringType },
                    navArgument("chapterId") { type = NavType.StringType },
                ),
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                HybridReaderScreen(
                    onPickVoice = { navController.navigate(StoryvoxRoutes.VOICE_LIBRARY) },
                    onOpenAiSettings = { navController.navigate(StoryvoxRoutes.SETTINGS) },
                    onOpenSettings = { navController.navigate(StoryvoxRoutes.SETTINGS) },
                    onOpenChat = { fId, prefill -> navController.navigate(StoryvoxRoutes.chat(fId, prefill)) },
                )
            }

            composable(
                route = StoryvoxRoutes.CHAT,
                arguments = listOf(
                    navArgument("fictionId") { type = NavType.StringType },
                    // Optional prefill for the input field, passed by the
                    // reader's long-press character lookup (#188). Default
                    // is empty string — ChatViewModel treats blank as "no
                    // prefill" and leaves the input untouched. Compose
                    // Navigation only honors `defaultValue` when the param
                    // is declared optional via `?prefill={prefill}` in the
                    // route template (see StoryvoxRoutes.CHAT).
                    navArgument("prefill") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                ChatScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAiSettings = { navController.navigate(StoryvoxRoutes.SETTINGS) },
                    onOpenVoiceLibrary = { navController.navigate(StoryvoxRoutes.VOICE_LIBRARY) },
                )
            }

            composable(
                StoryvoxRoutes.SETTINGS,
                enterTransition = homeEnter,
                exitTransition = homeExit,
                popEnterTransition = homeEnter,
                popExitTransition = homeExit,
            ) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                SettingsScreen(
                    onOpenVoiceLibrary = { navController.navigate(StoryvoxRoutes.VOICE_LIBRARY) },
                    onOpenSignIn = { navController.navigate(StoryvoxRoutes.AUTH_WEBVIEW) },
                    onOpenGitHubSignIn = { navController.navigate(StoryvoxRoutes.GITHUB_SIGN_IN) },
                    onOpenGitHubRevoke = {
                        // Remote revoke deep-link — opens
                        // `github.com/settings/applications` in the system
                        // browser. Local sign-out alone leaves storyvox's
                        // authorization recorded on GitHub's side; this is
                        // how users tear it down fully. Issue #91.
                        runCatching {
                            ctx.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(GitHubAuthConfig.SETTINGS_APPLICATIONS_URL),
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                            )
                        }
                    },
                    onOpenTeamsSignIn = { navController.navigate(StoryvoxRoutes.TEAMS_SIGN_IN) },
                    onOpenPronunciationDict = { navController.navigate(StoryvoxRoutes.SETTINGS_PRONUNCIATION) },
                    onOpenAiSessions = { navController.navigate(StoryvoxRoutes.SETTINGS_AI_SESSIONS) },
                    onOpenDebug = { navController.navigate(StoryvoxRoutes.SETTINGS_DEBUG) },
                    onOpenPluginManager = { navController.navigate(StoryvoxRoutes.SETTINGS_PLUGINS) },
                )
            }
            composable(
                StoryvoxRoutes.SETTINGS_PLUGINS,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                `in`.jphe.storyvox.feature.settings.plugins.PluginManagerScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(
                StoryvoxRoutes.SETTINGS_DEBUG,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                DebugScreen(onBack = { navController.popBackStack() })
            }
            composable(
                StoryvoxRoutes.SETTINGS_AI_SESSIONS,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                `in`.jphe.storyvox.feature.sessions.SessionsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenChat = { fictionId ->
                        navController.navigate(StoryvoxRoutes.chat(fictionId))
                    },
                )
            }
            composable(
                StoryvoxRoutes.SETTINGS_PRONUNCIATION,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                PronunciationDictScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                StoryvoxRoutes.VOICE_LIBRARY,
                enterTransition = homeEnter,
                exitTransition = homeExit,
                popEnterTransition = homeEnter,
                popExitTransition = homeExit,
            ) {
                VoiceLibraryScreen(
                    onOpenSettings = { navController.navigate(StoryvoxRoutes.SETTINGS) },
                )
            }
            composable(
                StoryvoxRoutes.AUTH_WEBVIEW,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                AuthWebViewScreen(
                    onSignedIn = { navController.popBackStack() },
                    onCancelled = { navController.popBackStack() },
                )
            }
            composable(
                StoryvoxRoutes.GITHUB_SIGN_IN,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                GitHubSignInScreen(
                    onSignedIn = { navController.popBackStack() },
                    onCancelled = { navController.popBackStack() },
                )
            }
            composable(
                StoryvoxRoutes.TEAMS_SIGN_IN,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                AnthropicTeamsSignInScreen(
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
