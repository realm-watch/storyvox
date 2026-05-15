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
import `in`.jphe.storyvox.feature.settings.AboutSettingsScreen
import `in`.jphe.storyvox.feature.settings.AccessibilitySettingsScreen
import `in`.jphe.storyvox.feature.settings.AccountSettingsScreen
import `in`.jphe.storyvox.feature.settings.AiSettingsScreen
import `in`.jphe.storyvox.feature.settings.MemoryPalaceSettingsScreen
import `in`.jphe.storyvox.feature.settings.PerformanceSettingsScreen
import `in`.jphe.storyvox.feature.settings.ReadingSettingsScreen
import `in`.jphe.storyvox.feature.settings.SettingsHubScreen
import `in`.jphe.storyvox.feature.settings.SettingsScreen
import `in`.jphe.storyvox.feature.settings.VoiceAndPlaybackSettingsScreen
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
    /** Issue #440 — Settings hub (section index). The gear-icon
     *  destination as of v0.5.38; previously dumped users into the
     *  flat-scroll [SETTINGS] page with no top-of-page map. Each row
     *  on the hub routes into a dedicated subscreen; the legacy long
     *  [SETTINGS] page is preserved as an "All settings" escape hatch
     *  for power users who want everything on one searchable page. */
    const val SETTINGS_HUB = "settings/hub"
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
    // ─── Follow-up to #440 — dedicated subscreen routes for the seven
    // hub cards that previously fell through to the legacy long-scroll
    // [SETTINGS] page. Grouped together so a rebase against a parallel
    // nav-restructure branch is mechanical. ───────────────────────────
    /** Settings → Voice & Playback. Voice library link, Speed, Pitch,
     *  punctuation cadence, HQ pitch interpolation, Pronunciation link. */
    const val SETTINGS_VOICE_PLAYBACK = "settings/voice-playback"
    /** Settings → Reading. Theme override, sleep-shake. */
    const val SETTINGS_READING = "settings/reading"
    /** Settings → Performance. Catch-up Pause, buffer slider, expert
     *  expander with warm-up, voice determinism, parallel synth. */
    const val SETTINGS_PERFORMANCE = "settings/performance"
    /** Settings → AI. Provider chip strip, per-provider config rows,
     *  test connection, grounding switches, memory toggle, actions
     *  toggle, Sessions link. */
    const val SETTINGS_AI = "settings/ai"
    /** Settings → Accessibility (Phase 1 scaffold, v0.5.42). High-
     *  contrast toggle, reduced-motion toggle, larger-touch-targets,
     *  screen-reader pause slider, speak-chapter-mode radio, font-
     *  scale override, reading-direction override. Phase 2 agents wire
     *  the actual behavior; Phase 1 only persists the prefs. */
    const val SETTINGS_ACCESSIBILITY = "settings/accessibility"
    /** Settings → Account. Royal Road sign-in, GitHub OAuth + scope. */
    const val SETTINGS_ACCOUNT = "settings/account"
    /** Settings → Memory Palace. Daemon host, API key, test probe. */
    const val SETTINGS_MEMORY_PALACE = "settings/memory-palace"
    /** Settings → About. Version + sigil name + build hash + the
     *  v0.5.00 milestone pill. */
    const val SETTINGS_ABOUT = "settings/about"
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

    /** Issue #472 — Library tab opened with a Magic-add prefill payload.
     *  The shared URL is URL-encoded so Compose Navigation can carry it
     *  through the query-string; the LibraryScreen decodes and hands
     *  it to the viewmodel on first composition. We deliberately use
     *  a query parameter (not a path segment) so a bare /library hit
     *  still matches and a missing `sharedUrl` is null at the receiver. */
    fun libraryWithShare(sharedUrl: String): String =
        "$LIBRARY?${DeepLinkResolver.ARG_SHARED_URL}=${Uri.encode(sharedUrl)}"
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

    /**
     * Routes treated as "home" surfaces — bottom nav stays visible while
     * the user is on one of these. The set was pruned in the v0.5.40
     * restructure: only [LIBRARY] and [SETTINGS_HUB] are bottom-bar
     * destinations now, but FOLLOWS / BROWSE / VOICE_LIBRARY / PLAYING /
     * SETTINGS stay in HOME_ROUTES because they still render at the
     * "primary surface" depth (deep-linked or reached via in-Library
     * sub-tab navigation), and we want the bottom bar visible there
     * too. The bar's *selected* mapping (below) collapses any of these
     * to the LIBRARY pill since that's the umbrella destination now.
     */
    private val HOME_ROUTES = setOf(PLAYING, LIBRARY, FOLLOWS, BROWSE, VOICE_LIBRARY, SETTINGS_HUB, SETTINGS)
    /** Strip any nav query-arg suffix before checking — PR #475 (magic-link)
     *  registered LIBRARY as `library?sharedUrl={sharedUrl}`, so
     *  `currentBackStackEntryAsState()` reports `destination.route` with the
     *  full pattern attached, not the bare LIBRARY constant. Without this
     *  substring, the bottom nav vanishes the moment the user is on the
     *  Library tab — the home surface this set is supposed to govern.
     *  Reported on tablet + Flip3 v0.5.47. */
    fun isHome(route: String?) = route?.substringBefore("?") in HOME_ROUTES

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
                    selected = when (currentRoute) {
                        // Restructure (v0.5.40) — Settings is the second
                        // primary destination. Both the hub and the
                        // legacy long-scroll page (reached from inside
                        // the hub) light the Settings pill.
                        StoryvoxRoutes.SETTINGS_HUB,
                        StoryvoxRoutes.SETTINGS -> HomeTab.Settings
                        // Everything else — Library, Browse, Follows,
                        // Voice Library, Playing, Reader, Audiobook —
                        // lights the Library pill since Library is now
                        // the umbrella destination for all of those
                        // surfaces (Browse / Follows live as Library
                        // sub-tabs; Reader / Audiobook are drill-downs
                        // from a Library entry).
                        else -> HomeTab.Library
                    },
                    onSelect = { tab ->
                        val target = when (tab) {
                            HomeTab.Library -> StoryvoxRoutes.LIBRARY
                            HomeTab.Settings -> StoryvoxRoutes.SETTINGS_HUB
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
                                // (LIBRARY after v0.5.40 restructure) so tab
                                // switches don't pile up the back stack.
                                // Using the start route name instead of
                                // `findStartDestination().id` to avoid the
                                // extra import; equivalent result.
                                popUpTo(StoryvoxRoutes.LIBRARY)
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
            // Restructure (v0.5.40) — Library is the start destination
            // and primary umbrella surface. Playing (HybridReaderScreen)
            // is reached via the Resume card on the Library tab when
            // the user has a continue-listening entry; if they don't,
            // landing on Library showed them an empty grid before too,
            // but the empty-state copy now invites them to Browse
            // (which is one Library sub-tab away, not a separate
            // bottom-bar destination).
            startDestination = StoryvoxRoutes.LIBRARY,
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
                    onOpenSettings = { navController.navigate(StoryvoxRoutes.SETTINGS_HUB) },
                    onOpenChat = { fId, prefill -> navController.navigate(StoryvoxRoutes.chat(fId, prefill)) },
                    // ResumeEmptyPrompt's "Browse the realms" CTA — only
                    // matters when the user has no continue-listening
                    // entry at all (first launch / wiped data). The
                    // populated ResumePrompt doesn't navigate; tapping
                    // Resume reloads the chapter in place.
                    onBrowse = {
                        // Restructure (v0.5.40) — Browse is no longer a
                        // bottom-bar destination; it lives inside the
                        // Library tab. The empty-state "Browse the
                        // realms" CTA could open the standalone BROWSE
                        // route (still in the nav graph) or route to
                        // LIBRARY and let the user tap the Browse
                        // sub-tab. We open BROWSE directly here so the
                        // CTA's verb still matches its destination
                        // exactly.
                        navController.navigate(StoryvoxRoutes.BROWSE) {
                            popUpTo(StoryvoxRoutes.LIBRARY)
                            launchSingleTop = true
                        }
                    },
                    // Issue #437 — Back arrow on the PLAYING destination.
                    // When PLAYING was entered directly (notification tap,
                    // bottom-nav tap, deep link) there's nothing to pop
                    // back to; fall back to LIBRARY (the v0.5.39 start
                    // destination) so the back arrow always feels active.
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate(StoryvoxRoutes.LIBRARY) {
                                popUpTo(StoryvoxRoutes.LIBRARY) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    },
                )
            }
            composable(
                // Issue #472 — Library route accepts an optional
                // `sharedUrl` query param so a system share-intent can
                // route a URL into the Magic-add sheet. Default null;
                // the LibraryScreen reads the arg via the
                // [DeepLinkResolver.ARG_SHARED_URL] key.
                "${StoryvoxRoutes.LIBRARY}?${DeepLinkResolver.ARG_SHARED_URL}={${DeepLinkResolver.ARG_SHARED_URL}}",
                arguments = listOf(
                    androidx.navigation.navArgument(DeepLinkResolver.ARG_SHARED_URL) {
                        type = androidx.navigation.NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
                enterTransition = homeEnter,
                exitTransition = homeExit,
                popEnterTransition = homeEnter,
                popExitTransition = homeExit,
            ) { backStackEntry ->
                val sharedUrl = backStackEntry.arguments
                    ?.getString(DeepLinkResolver.ARG_SHARED_URL)
                    ?.let { Uri.decode(it) }
                LibraryScreen(
                    sharedUrl = sharedUrl,
                    onOpenFiction = { id -> navController.navigate(StoryvoxRoutes.fictionDetail(id)) },
                    onOpenReader = { f, c -> navController.navigate(StoryvoxRoutes.reader(f, c)) },
                    onOpenSettings = { navController.navigate(StoryvoxRoutes.SETTINGS_HUB) },
                    // Restructure (v0.5.40) — Browse + Follows are
                    // embedded sub-tabs now. Both rely on the host
                    // (this NavHost) to surface the auth WebView for
                    // Royal Road sign-in; we route to the same shared
                    // sign-in surface used by FictionDetail #211 and
                    // standalone Browse #241.
                    onOpenRoyalRoadSignIn = { navController.navigate(StoryvoxRoutes.AUTH_WEBVIEW) },
                    onOpenFollowsSignIn = { navController.navigate(StoryvoxRoutes.AUTH_WEBVIEW) },
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
                    onOpenSettings = { navController.navigate(StoryvoxRoutes.SETTINGS_HUB) },
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
                    onOpenSettings = { navController.navigate(StoryvoxRoutes.SETTINGS_HUB) },
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
                    onOpenSettings = { navController.navigate(StoryvoxRoutes.SETTINGS_HUB) },
                    onOpenChat = { fId, prefill -> navController.navigate(StoryvoxRoutes.chat(fId, prefill)) },
                    // Issue #437 — Back arrow on deep-linked reader /
                    // audiobook destinations. Falls back to LIBRARY if
                    // the back stack was empty (cold-launch deep link).
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate(StoryvoxRoutes.LIBRARY) {
                                popUpTo(StoryvoxRoutes.LIBRARY) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    },
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
                    onOpenSettings = { navController.navigate(StoryvoxRoutes.SETTINGS_HUB) },
                    onOpenChat = { fId, prefill -> navController.navigate(StoryvoxRoutes.chat(fId, prefill)) },
                    // Issue #437 — Back arrow on deep-linked reader /
                    // audiobook destinations. Falls back to LIBRARY if
                    // the back stack was empty (cold-launch deep link).
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate(StoryvoxRoutes.LIBRARY) {
                                popUpTo(StoryvoxRoutes.LIBRARY) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    },
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

            // Issue #440 — Settings hub. New gear-icon destination as of
            // v0.5.38. The hub presents an ordered list of section cards;
            // each row routes either to a dedicated subscreen (Voice
            // library, Plugins, AI sessions, Pronunciation, Debug) or to
            // [SETTINGS] (the legacy long-scroll page) for sections that
            // haven't been broken out yet. Uses homeEnter/Exit (same as
            // SETTINGS) so the hub feels like a peer of the bottom-tab
            // surfaces, not a deep stack push.
            composable(
                StoryvoxRoutes.SETTINGS_HUB,
                enterTransition = homeEnter,
                exitTransition = homeExit,
                popEnterTransition = homeEnter,
                popExitTransition = homeExit,
            ) {
                SettingsHubScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onOpenAllSettings = { navController.navigate(StoryvoxRoutes.SETTINGS) },
                    onOpenVoiceLibrary = { navController.navigate(StoryvoxRoutes.VOICE_LIBRARY) },
                    onOpenPluginManager = { navController.navigate(StoryvoxRoutes.SETTINGS_PLUGINS) },
                    onOpenAiSessions = { navController.navigate(StoryvoxRoutes.SETTINGS_AI_SESSIONS) },
                    onOpenPronunciationDict = { navController.navigate(StoryvoxRoutes.SETTINGS_PRONUNCIATION) },
                    onOpenDebug = { navController.navigate(StoryvoxRoutes.SETTINGS_DEBUG) },
                    onOpenVoicePlayback = { navController.navigate(StoryvoxRoutes.SETTINGS_VOICE_PLAYBACK) },
                    onOpenReading = { navController.navigate(StoryvoxRoutes.SETTINGS_READING) },
                    onOpenPerformance = { navController.navigate(StoryvoxRoutes.SETTINGS_PERFORMANCE) },
                    onOpenAi = { navController.navigate(StoryvoxRoutes.SETTINGS_AI) },
                    onOpenAccessibility = { navController.navigate(StoryvoxRoutes.SETTINGS_ACCESSIBILITY) },
                    onOpenAccount = { navController.navigate(StoryvoxRoutes.SETTINGS_ACCOUNT) },
                    onOpenMemoryPalace = { navController.navigate(StoryvoxRoutes.SETTINGS_MEMORY_PALACE) },
                    onOpenAbout = { navController.navigate(StoryvoxRoutes.SETTINGS_ABOUT) },
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
            // ─── Follow-up to #440 — dedicated subscreens for the seven
            // hub cards that previously fell through to the legacy long-
            // scroll [SETTINGS] page. Each uses push enter/exit because
            // it's reached from the hub via drill-down, not as a peer
            // home tab. ───────────────────────────────────────────────
            composable(
                StoryvoxRoutes.SETTINGS_VOICE_PLAYBACK,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                VoiceAndPlaybackSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenVoiceLibrary = { navController.navigate(StoryvoxRoutes.VOICE_LIBRARY) },
                    onOpenPronunciationDict = { navController.navigate(StoryvoxRoutes.SETTINGS_PRONUNCIATION) },
                )
            }
            composable(
                StoryvoxRoutes.SETTINGS_READING,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                ReadingSettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                StoryvoxRoutes.SETTINGS_PERFORMANCE,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                PerformanceSettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                StoryvoxRoutes.SETTINGS_AI,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                AiSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAiSessions = { navController.navigate(StoryvoxRoutes.SETTINGS_AI_SESSIONS) },
                    onOpenTeamsSignIn = { navController.navigate(StoryvoxRoutes.TEAMS_SIGN_IN) },
                )
            }
            // Phase 1 scaffold (v0.5.42) — Accessibility subscreen. Push
            // transitions mirror the rest of the hub-routed subscreens
            // (Voice & Playback, Reading, Performance, AI, Account,
            // Memory Palace, About). Phase 2 wires the actual a11y
            // behavior; Phase 1 only persists the prefs.
            composable(
                StoryvoxRoutes.SETTINGS_ACCESSIBILITY,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                AccessibilitySettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                StoryvoxRoutes.SETTINGS_ACCOUNT,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                AccountSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSignIn = { navController.navigate(StoryvoxRoutes.AUTH_WEBVIEW) },
                    onOpenGitHubSignIn = { navController.navigate(StoryvoxRoutes.GITHUB_SIGN_IN) },
                    onOpenGitHubRevoke = {
                        // Mirrors the SETTINGS legacy page's revoke handler:
                        // opens github.com/settings/applications in the
                        // system browser so users can tear down storyvox's
                        // authorization remotely. Local sign-out alone
                        // leaves it recorded on GitHub's side. Issue #91.
                        runCatching {
                            ctx.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(GitHubAuthConfig.SETTINGS_APPLICATIONS_URL),
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                            )
                        }
                    },
                )
            }
            composable(
                StoryvoxRoutes.SETTINGS_MEMORY_PALACE,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                MemoryPalaceSettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                StoryvoxRoutes.SETTINGS_ABOUT,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                AboutSettingsScreen(
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
                    onOpenSettings = { navController.navigate(StoryvoxRoutes.SETTINGS_HUB) },
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

    /** Issue #472 — query-string carried on the Library route when a
     *  shared URL needs to land in the Magic-add sheet. The Library
     *  screen pulls this off the nav arguments on first composition
     *  and pre-fills the sheet via `viewModel.openAddByUrlPrefilled`. */
    const val ARG_SHARED_URL = "sharedUrl"

    fun resolve(intent: Intent): String? {
        // Notification tap → reader for the currently-playing chapter.
        val rf = intent.getStringExtra(EXTRA_OPEN_READER_FICTION_ID)
        val rc = intent.getStringExtra(EXTRA_OPEN_READER_CHAPTER_ID)
        if (!rf.isNullOrBlank() && !rc.isNullOrBlank()) {
            return StoryvoxRoutes.reader(rf, rc)
        }
        // Issue #472 — ACTION_SEND share-intent path. Any app (browser,
        // podcast client, RSS reader, social share menu) can share a
        // URL into storyvox; the activity routes to Library with the
        // URL surfaced as a query arg, and LibraryScreen opens the
        // Magic-add sheet pre-populated. The resolver itself doesn't
        // run UrlResolver here — that decision is the viewmodel's
        // job and would couple this pure-function helper to Hilt-
        // resolved deps.
        if (intent.action == Intent.ACTION_SEND) {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            if (shared.isNotBlank() && looksLikeUrl(shared)) {
                return StoryvoxRoutes.libraryWithShare(shared)
            }
            return null
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

    /** Lightweight URL sniffer — accepts http(s) URLs only. Apps that
     *  share plaintext frequently emit "title\nURL" or "URL extra
     *  text" via Intent.EXTRA_TEXT; we extract the first http(s)
     *  token if the body is multi-line, otherwise require the whole
     *  string to look like a URL. */
    private fun looksLikeUrl(text: String): Boolean {
        if (text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true)
        ) return true
        // Multi-line share — find the first http(s) token.
        return text.lineSequence()
            .mapNotNull { line ->
                line.split(' ', '\t', '\n').firstOrNull { tok ->
                    tok.startsWith("http://", ignoreCase = true) ||
                        tok.startsWith("https://", ignoreCase = true)
                }
            }
            .firstOrNull() != null
    }
}
