package `in`.jphe.storyvox.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.debug.DebugOverlay
import `in`.jphe.storyvox.feature.debug.DebugViewModel
import `in`.jphe.storyvox.ui.component.HybridReaderShell
import `in`.jphe.storyvox.ui.component.MilestoneConfetti

@Composable
fun HybridReaderScreen(
    onPickVoice: () -> Unit,
    /** Open Settings → AI when the recap modal hits a NotConfigured /
     *  AuthFailed state. **AppNav must wire this** — leaving the default
     *  no-op causes the recap empty-state's "Open Settings" CTA to look
     *  primary but be a dead button (issue #152, fixed in this PR by
     *  wiring all three [HybridReaderScreen] composables in
     *  `StoryvoxNavHost.kt`). The default stays for preview/test use,
     *  but any new production callsite *must* pass a real navigation
     *  callback or the unconfigured-AI user has no path forward. */
    onOpenAiSettings: () -> Unit = {},
    /** Open the Q&A chat surface for the currently-loaded fiction
     *  (#81 follow-up). No-op default is preview/test-only — production
     *  callsites pass a real
     *  `navController.navigate(chat(fictionId, prefill))`.
     *  Surfaced in the player-options sheet's "Smart features" group, and
     *  by long-press character-lookup in the reader (#188), which passes
     *  a non-null prefill of the form `Who is X?`. Pass `null` for
     *  prefill from non-lookup entry points. */
    onOpenChat: (fictionId: String, prefill: String?) -> Unit = { _, _ -> },
    /**
     * Route the empty-empty Resume prompt's "Browse the realms" CTA to
     * the Browse tab. Default no-op for previews/tests; production
     * callsites pass a real `navController.navigate(BROWSE)`. The
     * populated Resume prompt's two buttons don't need any nav — they
     * load the chapter into the playback controller, and the state flow
     * naturally swaps the prompt for the player view in place.
     */
    onBrowse: () -> Unit = {},
    /** Open the Settings screen. Kept on the surface for source-compat
     *  with existing call sites; the player's top-bar gear icon was
     *  replaced by a Back arrow in v0.5.40 (#437) and Settings now
     *  lives in primary nav (#469). Default no-op for previews/tests. */
    onOpenSettings: () -> Unit = {},
    /** Issue #437 — pop the player back to whichever surface launched
     *  it (FictionDetail, Library, Browse, Follows, History). Wired
     *  by [`in`.jphe.storyvox.navigation.StoryvoxNavHost] to
     *  `navController.popBackStack()` with a fallback to LIBRARY when
     *  the back stack is empty (deep-link / cold-launch into the
     *  player). Default no-op for previews. */
    onBack: () -> Unit = {},
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val recapState by viewModel.recap.collectAsStateWithLifecycle()
    val recapPlayback by viewModel.recapPlayback.collectAsStateWithLifecycle()
    val resumeEntry by viewModel.resumeEntry.collectAsStateWithLifecycle()
    val playback = state.playback

    // Calliope (v0.5.00) — first-natural-chapter-completion confetti.
    // The VM's confettiTrigger fires Unit once per qualifying event;
    // we flip a local visible flag that drives the [MilestoneConfetti]
    // overlay, then close the gate persistently via markConfettiShown
    // when the overlay tells us it's done.
    var celebrationVisible by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.confettiTrigger.collect {
            celebrationVisible = true
        }
    }

    // Vesper (v0.4.97) — debug overlay. The DebugViewModel pulls the
    // master switch from SettingsRepositoryUi so toggling in Settings →
    // Developer immediately reflects here without a navController round-
    // trip. The overlay is mounted INSIDE the shell so the reader's
    // playback controls still respond to taps (the overlay only takes
    // pointer events on its own bounding box). Hoisting outside the
    // shell would intercept reader gestures.
    //
    // Issue #529 follow-up (v0.5.58): the overlay is intentionally
    // available in the release variant. JP relies on the live "sent
    // #N · queue X/12" strip + Voice-Roster / Playback-Speed metrics
    // for diagnostic gold on the same APK end users run. The Settings
    // → Advanced → "Show debug overlay" toggle (defaulting OFF — see
    // [UiSettings.showDebugOverlay] = false) is the SOLE gate. We
    // dropped the earlier `BuildConfig.DEBUG` compile-time gate
    // because, with `release` now being the shipped variant, that
    // would have permanently hidden the overlay even for users who
    // explicitly opted in — exactly the opposite of what we want.
    // Pre-existing default of `false` means a fresh install still
    // shows nothing; only the Settings toggle reveals the strip.
    val debugVm: DebugViewModel = hiltViewModel()
    val debugEnabled by debugVm.overlayEnabled.collectAsStateWithLifecycle()
    val debugOverlayVisible = debugEnabled

    // Playing-tab "no chapter loaded" path — replace the bare
    // "No chapter loaded." stub with the magical Resume prompt. Two
    // sub-cases:
    //  - we have a most-recent continue-listening entry → ResumePrompt
    //    (cover + sigil ring + brass-shimmer Resume CTA + "From the
    //    start"). Tapping Resume loads via the playback controller; the
    //    state flow flips `playback` non-null and the prompt naturally
    //    dissolves into the player view — no nav transition.
    //  - no entry at all (first launch, wiped data) → ResumeEmptyPrompt
    //    with a "Browse the realms" CTA into the Browse tab.
    //
    // We also short-circuit through the same prompt when the loader hit
    // [LoadingPhase.TimedOut] AND we have a resume entry — same user
    // outcome (give them a clean way back into their book) without the
    // generic error-block surface. The retry path inside AudiobookView
    // still fires for the case where there's no resume entry to fall
    // back on.
    val timedOut = state.loadingPhase == LoadingPhase.TimedOut
    // Show the Resume prompt whenever we don't have a real chapter to
    // render — three cases:
    //  (a) playback is null (cold-start, app-killed)
    //  (b) playback exists but its fictionId/chapterId are still null
    //      (controller initialized but no chapter queued yet)
    //  (c) the loading timer hit TimedOut AND we have a resume entry to
    //      fall back on (otherwise AudiobookView's friendlier
    //      Retry/Pick-voice error block handles the dead-end case).
    //
    // We compute the cases below in two steps so Kotlin's smart-cast
    // on `playback != null` stays usable for the rest of the screen.
    val showPromptForNullPlayback = playback == null
    val showPromptForBlankIds = playback != null &&
        playback.fictionId == null &&
        playback.chapterId == null
    val showPromptForTimedOutWithEntry =
        playback != null && timedOut && resumeEntry != null
    if (showPromptForNullPlayback || showPromptForBlankIds ||
        showPromptForTimedOutWithEntry
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val entry = resumeEntry
            if (entry != null) {
                ResumePrompt(
                    entry = entry,
                    onResume = { viewModel.resume(fromStart = false) },
                    onFromStart = { viewModel.resume(fromStart = true) },
                )
            } else {
                ResumeEmptyPrompt(onBrowse = onBrowse)
            }
            // Debug overlay still mounts on top so the inspector can see
            // the loading-phase state machine even before a chapter is
            // loaded. Same gating as below.
            if (debugOverlayVisible) {
                DebugOverlay(viewModel = debugVm)
            }
        }
        return
    }
    // Past here, `playback` is guaranteed non-null — the compound
    // predicate above covered the null case. The local `playback` val
    // doesn't smart-cast through that, so explicitly bind a non-null
    // alias here. `playbackState` for clarity at call sites (the
    // existing AudiobookView arg is called `state`).
    val playbackState = requireNotNull(playback) {
        "playback should be non-null past the resume-prompt branch"
    }

    Box(modifier = Modifier.fillMaxSize()) {
    HybridReaderShell(
        current = state.activePane,
        onViewChange = viewModel::setActivePane,
        audiobookContent = {
            AudiobookView(
                state = playbackState,
                onPlayPause = viewModel::playPause,
                onSeekTo = viewModel::seekTo,
                onSkipForward = viewModel::skipForward,
                onSkipBack = viewModel::skipBack,
                onNextChapter = viewModel::nextChapter,
                onPreviousChapter = viewModel::previousChapter,
                onPreviousSentence = viewModel::previousSentence,
                onNextSentence = viewModel::nextSentence,
                onPickVoice = onPickVoice,
                onSetSpeed = viewModel::setSpeed,
                onPersistSpeed = viewModel::persistSpeed,
                onSetPitch = viewModel::setPitch,
                onPersistPitch = viewModel::persistPitch,
                onStartSleepTimer = viewModel::startSleepTimer,
                onCancelSleepTimer = viewModel::cancelSleepTimer,
                onRequestRecap = viewModel::requestRecap,
                onOpenChat = {
                    playbackState.fictionId?.let { onOpenChat(it, null) }
                },
                onOpenSettings = onOpenSettings,
                onBack = onBack,
                // Issue #278 — surface loading-phase + retry path. The
                // view decides what to render based on phase (regular /
                // slow-hint at 10s / full error block at 30s).
                loadingPhase = state.loadingPhase,
                onRetryLoading = viewModel::retryLoading,
                // Issue #121 — bookmark drop / jump. The controller side
                // resolves "current chapter" + char-offset from the
                // playback state, so the UI just forwards the verbs.
                onBookmarkHere = viewModel::bookmarkHere,
                onJumpToBookmark = viewModel::jumpToBookmark,
                // Issue #418 — magical voice icon's quick sheet
                // surfaces the live pause multiplier (#109) + Sonic
                // high-quality flag (#193). State sourced from
                // SettingsRepositoryUi via ReaderViewModel's combine;
                // setters dual-write to PlaybackControllerUi (immediate
                // engine apply) + SettingsRepositoryUi (persistence).
                punctuationPauseMultiplier = state.punctuationPauseMultiplier,
                pitchInterpolationHighQuality = state.pitchInterpolationHighQuality,
                onSetPunctuationPause = viewModel::setPunctuationPauseMultiplier,
                onSetPitchHighQuality = viewModel::setPitchInterpolationHighQuality,
                // "Why are we waiting?" — pipe AudioOutputMonitor's
                // diagnostic through the view so the brass panel above
                // the cover surfaces a typed reason whenever no audio
                // is reaching the speakers.
                waitReason = state.waitReason,
            )
        },
        readerContent = {
            ReaderTextView(
                state = playbackState,
                chapterText = state.chapterText,
                onPlayPause = viewModel::playPause,
                onSeekToChar = viewModel::seekToChar,
                onAskAiAbout = { question ->
                    // Long-press character lookup (#188): forward the
                    // prebuilt "Who is X?" question as a chat prefill.
                    // The chat surface auto-fills the input — the user
                    // can edit before sending or send as-is.
                    playbackState.fictionId?.let { onOpenChat(it, question) }
                },
            )
        },
    )

    // Recap modal — overlays everything when not Hidden. Driven by
    // ReaderViewModel.requestRecap().
    RecapModal(
        state = recapState,
        recapPlayback = recapPlayback,
        onCancel = viewModel::cancelRecap,
        onRetry = viewModel::requestRecap,
        onOpenSettings = {
            viewModel.cancelRecap()
            onOpenAiSettings()
        },
        onToggleReadAloud = viewModel::toggleRecapAloud,
    )

    // Debug overlay — sits on top of everything (including the recap
    // modal) when enabled. Pinned to the top of the screen via
    // statusBarsPadding inside DebugOverlay itself, so the player
    // controls at the bottom stay free.
    if (debugOverlayVisible) {
        DebugOverlay(viewModel = debugVm)
    }

    // Calliope (v0.5.00) — confetti easter-egg, drifts across the
    // player for ~3.5s then fades. Sits ABOVE the debug overlay so
    // power users still see the celebration; the overlay can wait.
    // markConfettiShown persists the one-time flag so this never
    // reappears for this install. Non-blocking — no pointer
    // interception, just a Canvas drawing on top.
    if (celebrationVisible) {
        MilestoneConfetti(
            onFinished = {
                celebrationVisible = false
                viewModel.markConfettiShown()
            },
        )
    }
    }
}
