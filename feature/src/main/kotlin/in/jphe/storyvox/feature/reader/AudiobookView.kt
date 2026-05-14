package `in`.jphe.storyvox.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.ripple
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.feature.api.UiPlaybackState
import `in`.jphe.storyvox.feature.api.UiSleepTimerMode
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.BrassProgressTrack
import `in`.jphe.storyvox.ui.component.BrassVoiceIcon
import `in`.jphe.storyvox.ui.component.ErrorBlock
import `in`.jphe.storyvox.ui.component.ErrorPlacement
import `in`.jphe.storyvox.ui.component.FictionCoverThumb
import `in`.jphe.storyvox.ui.component.fictionMonogram
import `in`.jphe.storyvox.ui.component.MagicSkeletonTile
import `in`.jphe.storyvox.ui.component.MagicSpinner
import `in`.jphe.storyvox.ui.theme.LocalMotion
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookView(
    state: UiPlaybackState,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    /** #120 — step back one sentence boundary. Default no-op so older
     *  callsites (tests, previews) keep compiling. */
    onPreviousSentence: () -> Unit = {},
    /** #120 — step forward one sentence boundary. */
    onNextSentence: () -> Unit = {},
    onPickVoice: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onPersistSpeed: (Float) -> Unit,
    onSetPitch: (Float) -> Unit,
    onPersistPitch: (Float) -> Unit,
    onStartSleepTimer: (UiSleepTimerMode) -> Unit,
    onCancelSleepTimer: () -> Unit,
    /** Open the Chapter Recap modal. ReaderScreen wires this to
     *  [`in`.jphe.storyvox.feature.reader.ReaderViewModel.requestRecap].
     *  Issue #81. */
    onRequestRecap: () -> Unit = {},
    /** Open the Q&A chat surface for the currently-loaded fiction
     *  (#81 follow-up). HybridReaderScreen guards this against null
     *  fictionId on the playback state, so callees can rely on it
     *  being a fully-routed navigation. */
    onOpenChat: () -> Unit = {},
    /** Open the Settings screen. Surfaced as a leading gear icon in the
     *  top bar so playback's per-screen Settings affordance matches the
     *  other home screens (Library/Browse/Follows/Voices). */
    onOpenSettings: () -> Unit = {},
    /** Issue #278 — loading-phase from the ReaderViewModel. Drives the
     *  soft "Still working…" hint at 10s and the hard timeout/retry
     *  error block at 30s. Defaults to NotLoading so previews / tests
     *  that don't care about the loading lifecycle stay simple. */
    loadingPhase: LoadingPhase = LoadingPhase.NotLoading,
    /** Issue #278 — user tapped Retry on the timed-out error block. */
    onRetryLoading: () -> Unit = {},
    /** Issue #278 — user tapped "Pick a different voice" on the timed-out
     *  error block. Goes to the voice library; the controller will pick
     *  the new voice up next time play() is invoked. */
    onCancelLoading: () -> Unit = {},
    /** Issue #121 — drop a bookmark at the current playhead in the active
     *  chapter. One bookmark per chapter; setting again overwrites. */
    onBookmarkHere: () -> Unit = {},
    /** Issue #121 — seek to the active chapter's bookmark, if any. No-op
     *  when the chapter has none. */
    onJumpToBookmark: () -> Unit = {},
    /**
     * Issue #418 — live inter-sentence pause multiplier (#109) for the
     * magical-voice-icon quick sheet. Defaults to 1× (audiobook-tuned
     * default) so preview/test callsites that don't care about cadence
     * stay simple.
     */
    punctuationPauseMultiplier: Float = 1f,
    /**
     * Issue #418 — high-quality Sonic pitch-interpolation flag (#193)
     * for the quick sheet's "Sonic quality" toggle row. Defaults to
     * `true` to match Settings.
     */
    pitchInterpolationHighQuality: Boolean = true,
    /** Issue #418 — apply the inter-sentence pause multiplier. Wired by
     *  ReaderViewModel into both PlaybackControllerUi (live) +
     *  SettingsRepositoryUi (persist). */
    onSetPunctuationPause: (Float) -> Unit = {},
    /** Issue #418 — toggle Sonic high-quality flag. Persisted via
     *  SettingsRepositoryUi; engine reads at next chapter render. */
    onSetPitchHighQuality: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val motion = LocalMotion.current
    val reducedMotion = LocalReducedMotion.current
    // Spinner enter/exit transitions for the warmup state. Honors
    // LocalReducedMotion: when true, visibility flips instantly (reduce
    // motion = absent motion, not shorter motion — same pattern as
    // cascadeReveal). Token vocabulary stays consistent with the rest
    // of Library Nocturne via standardDurationMs + standardEasing.
    val spinnerEnter = if (reducedMotion) EnterTransition.None else
        fadeIn(animationSpec = tween(motion.standardDurationMs, easing = motion.standardEasing))
    val spinnerExit = if (reducedMotion) ExitTransition.None else
        fadeOut(animationSpec = tween(motion.standardDurationMs, easing = motion.standardEasing))
    // Issue #418 — two distinct sheets now: the magical-voice-icon
    // opens the voice quick sheet (speed/pitch/voice/pause/quality +
    // Advanced expander), the ⋮ overflow opens the remaining
    // non-voice items (sentence step, sleep timer, bookmark, recap,
    // chat). Splitting the state ensures one sheet's dismiss-animation
    // doesn't fight the other's open-animation.
    var showVoiceSheet by remember { mutableStateOf(false) }
    var showOverflowSheet by remember { mutableStateOf(false) }

    // Issue #278 — full-screen error block when the loading state has
    // been stuck for 30+ seconds. Replaces the eternal conjuring sigil
    // with Retry + Pick voice + an escape via the bottom nav. The
    // underlying load isn't cancelled; if it eventually completes the
    // state flow takes over and we route back to the normal player UI.
    if (loadingPhase == LoadingPhase.TimedOut) {
        ErrorBlock(
            title = "Couldn't load this chapter",
            message = "The voice or chapter text is taking longer than expected. " +
                "Try again, or pick a different voice — some take a moment to warm up.",
            onRetry = onRetryLoading,
            retryLabel = "Try again",
            onBack = onPickVoice,
            backLabel = "Pick a different voice",
            placement = ErrorPlacement.FullScreen,
            modifier = modifier,
        )
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // Issue #254 — the loading state used to show a bare row with
            // only an overflow button up top, so the user had no idea
            // *what* was loading (no title, no chapter, no escape). A
            // two-line title bar pins identity through every state —
            // loading, warming up, buffering, playing, paused. Title
            // comes from the queued PlaybackItem, available before
            // chapter text loads. A custom Box (not CenterAlignedTopAppBar)
            // so the bar grows with fontScale instead of clipping the
            // second line at the M3 bar's fixed container height.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = spacing.xs),
            ) {
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        // Issue #418 — bumped from 56 dp to 104 dp on the
                        // trailing side because the top bar now carries
                        // TWO trailing affordances (brass voice icon +
                        // ⋮ overflow). Leading side stays at 56 dp for
                        // the Settings gear. Asymmetric padding via
                        // start/end keeps the title visually centered
                        // across both sides' negative space.
                        .padding(start = 56.dp, end = 104.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = state.fictionTitle.ifBlank { "Loading…" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    if (state.chapterTitle.isNotBlank()) {
                        Text(
                            text = state.chapterTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                // Issue #418 — magical voice settings icon. Tap opens
                // the voice quick sheet; long-press jumps straight to
                // the Voice Library (same gesture as a "I want to
                // change voices entirely" shortcut). Sits to the left
                // of the overflow so the brass glyph reads as the
                // primary affordance and the ⋮ reads as the secondary
                // "more" surface — matching the issue's IA pitch.
                //
                // combinedClickable wraps a Box so we can attach both
                // onClick + onLongClick to the same brass-icon target.
                // IconButton can't host onLongClick directly. We
                // preserve the 48 dp touch target by sizing the Box.
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val voiceInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .combinedClickable(
                                interactionSource = voiceInteraction,
                                indication = ripple(bounded = false, radius = 24.dp),
                                role = androidx.compose.ui.semantics.Role.Button,
                                onClickLabel = "Voice settings",
                                onLongClickLabel = "Open Voice Library",
                                onClick = { showVoiceSheet = true },
                                onLongClick = onPickVoice,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        BrassVoiceIcon(size = 24.dp)
                    }
                    IconButton(onClick = { showOverflowSheet = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Player options")
                    }
                }
            }
            // While the chapter body + voice model are still loading we don't
            // have a cover URL or chapter title yet — show the brass arcane
            // sigil placeholder instead of a "?" thumb. As soon as state
            // fills in we swap to the real cover.
            val coverLoading = state.chapterTitle.isBlank() && state.coverUrl.isNullOrBlank()
            // "Warming up" = chapter loaded, user has hit play, but the TTS
            // engine hasn't produced the first sentence yet (no sentence
            // range emitted). Sherpa-onnx model load + first synth can take
            // 5-15s on modest hardware.
            //
            // Issue #98 Mode A — `isWarmingUp` is gated server-side: when
            // the user has Warm-up Wait off, this is always false even
            // during the genuine warmup window, so the UI doesn't show the
            // spinner. The listener trades visible feedback for silence at
            // chapter start.
            val warmingUp = state.isWarmingUp
            // "Buffering" = streaming pipeline ran out of generated PCM
            // mid-chapter (producer can't keep up — e.g. Piper-high on
            // Tab A7 Lite). Same brass spinner so the visual stays
            // consistent; status label distinguishes the two states so
            // the user knows what's happening.
            //
            // Issue #98 Mode B — `isBuffering` stays false through underrun
            // when Catch-up Pause is off, so the consumer drains through
            // the silence without surfacing a spinner.
            val showSpinner = warmingUp || state.isBuffering
            if (coverLoading) {
                MagicSkeletonTile(
                    modifier = Modifier.size(width = 220.dp, height = 330.dp),
                    shape = MaterialTheme.shapes.large,
                    glyphSize = 96.dp,
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    // Cover tap toggles play/pause — same convention as
                    // Spotify, Apple Music, Pocket Casts, etc. The big play
                    // button below remains the explicit affordance; the
                    // cover is the *convenient* one, since it's the largest
                    // surface on the player and one-handed users naturally
                    // thumb-tap it (#269). Long-press is left unbound so
                    // we don't accidentally fight system-level a11y gestures.
                    FictionCoverThumb(
                        coverUrl = state.coverUrl,
                        title = state.fictionTitle,
                        monogram = fictionMonogram(author = "", title = state.fictionTitle),
                        modifier = Modifier
                            .size(width = 220.dp, height = 330.dp)
                            .clickable(
                                role = androidx.compose.ui.semantics.Role.Button,
                                onClickLabel = if (state.isPlaying) "Pause" else "Play",
                                onClick = onPlayPause,
                            ),
                    )
                    // Subtle brass sigil ring orbiting the cover while the
                    // engine is producing the first sentence's audio. Fades
                    // in/out around the warmup transition rather than
                    // popping — visual swap to the playing state stays
                    // continuous instead of abrupt.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showSpinner,
                        enter = spinnerEnter,
                        exit = spinnerExit,
                    ) {
                        MagicSpinner(modifier = Modifier.size(width = 240.dp, height = 350.dp))
                    }
                }
            }
            Text(
                if (state.fictionTitle.isBlank()) "Conjuring your chapter…" else state.fictionTitle,
                style = MaterialTheme.typography.titleLarge,
                color = if (state.fictionTitle.isBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                // Issue #166 — when we have a chapter title, KEEP it visible
                // through warmup / mid-chapter buffering instead of replacing
                // it with the state label. The user just tapped a specific
                // chapter and needs that confirmation while audio loads;
                // replacing it costs them their tap-context confirmation
                // during the exact 3-15s window that mistake-recovery
                // matters most. Spinner state is already conveyed two
                // separate ways (the play-button ring + BrassProgressTrack's
                // `loading = showSpinner`), so the subtitle text doesn't
                // need to carry it alone. When title is present + we're in
                // a state tail, append the state to the title with a "·"
                // separator so the user gets both pieces of information.
                when {
                    state.chapterTitle.isBlank() -> "Loading voice + chapter text"
                    warmingUp -> "${state.chapterTitle} · Voice waking up…"
                    state.isBuffering -> "${state.chapterTitle} · Buffering…"
                    else -> state.chapterTitle
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (showSpinner) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Issue #278 — soft slow hint: after the loading state has been
            // stuck for 10s the user should know we're still trying. The
            // hint appears under the existing "Loading voice + chapter text"
            // / chapter-title subtitle and disappears as soon as state
            // arrives. At 30s we flip to the full error block above and
            // this hint never renders.
            if (loadingPhase == LoadingPhase.Slow) {
                Text(
                    "Still working… slow voice or network. Hang tight.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(spacing.xs))
            BrassProgressTrack(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                onSeekTo = onSeekTo,
                modifier = Modifier.fillMaxWidth(),
                loading = showSpinner,
            )
            if (state.sleepTimerRemainingMs != null) {
                SleepTimerCountdownChip(remainingMs = state.sleepTimerRemainingMs, onCancel = onCancelSleepTimer)
            }
            Spacer(Modifier.height(spacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPreviousChapter) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous chapter", modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = onSkipBack) {
                    // Issue #268 — Replay30 / Forward30 show the literal '30'
                    // inside a curved-arrow glyph, so the seek interval is
                    // legible at a glance instead of just an abstract
                    // double-chevron. The skip-30 duration is hard-coded to
                    // 30s today; if/when a configurable duration lands the
                    // icon will need a swap to a dynamic glyph too.
                    Icon(Icons.Filled.Replay30, contentDescription = "Skip back 30 seconds", modifier = Modifier.size(32.dp))
                }
                // "Warming up" = user has hit play and chapter has loaded, but
                // the TTS engine hasn't produced the first sentence yet (no
                // sentence range emitted). Sherpa-onnx model load + first
                // synth can take 5-15s on modest hardware; without this
                // indicator the play button looks dead during that gap.
                // "Buffering" = mid-chapter underrun on slow voice + slow
                // device; same spinner so the play button stays visually
                // consistent.
                val warmingUp = state.isWarmingUp
                val showSpinner = warmingUp || state.isBuffering
                Box(contentAlignment = Alignment.Center) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showSpinner,
                        enter = spinnerEnter,
                        exit = spinnerExit,
                    ) {
                        MagicSpinner(modifier = Modifier.size(96.dp))
                    }
                    FilledIconButton(
                        onClick = onPlayPause,
                        modifier = Modifier.size(72.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                IconButton(onClick = onSkipForward) {
                    Icon(Icons.Filled.Forward30, contentDescription = "Skip forward 30 seconds", modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = onNextChapter) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next chapter", modifier = Modifier.size(32.dp))
                }
            }
        }

        // Candlelight scrim — a translucent brass tone composited over
        // the default scrim color, instead of a flat black dim. Reads
        // as "the light's been turned down", not "the screen got eaten".
        // Shared between both sheets so they feel like the same
        // brass-edged "shade-down" affordance, not two different surfaces.
        val brassScrim = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            .compositeOver(MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f))

        // Issue #418 — magical voice settings sheet. Half-expanded by
        // default (skipPartiallyExpanded = false would force half-state;
        // we want the user to be able to drag UP for the Advanced
        // expander, so we keep true and let the sheet auto-size to its
        // content). The Speed slider is the first row so the most-used
        // knob lands under the user's thumb the instant the sheet opens.
        if (showVoiceSheet) {
            val voiceSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showVoiceSheet = false },
                sheetState = voiceSheetState,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                scrimColor = brassScrim,
                tonalElevation = 6.dp,
            ) {
                val voiceScope = rememberCoroutineScope()
                VoiceQuickSheetContent(
                    state = state,
                    punctuationPauseMultiplier = punctuationPauseMultiplier,
                    pitchInterpolationHighQuality = pitchInterpolationHighQuality,
                    onSetSpeed = onSetSpeed,
                    onPersistSpeed = onPersistSpeed,
                    onSetPitch = onSetPitch,
                    onPersistPitch = onPersistPitch,
                    onSetPunctuationPause = onSetPunctuationPause,
                    onSetPitchHighQuality = onSetPitchHighQuality,
                    onPickVoice = {
                        voiceScope.launch { voiceSheetState.hide() }
                        showVoiceSheet = false
                        onPickVoice()
                    },
                    onOpenAdvancedVoice = {
                        voiceScope.launch { voiceSheetState.hide() }
                        showVoiceSheet = false
                        // Deep-link into Voice Library — same destination
                        // as long-press on the icon. The lexicon +
                        // phonemizer pickers live there (VoiceLibraryScreen
                        // lines 364/367/451/454); replicating them here
                        // would force SAF + Kokoro detection into the
                        // player layer for no UX gain.
                        onPickVoice()
                    },
                )
            }
        }

        // Issue #418 — the post-split overflow sheet. Keeps only the
        // non-voice items: sentence-step transport (#120), sleep timer,
        // bookmark drop/jump (#121), recap (#81), AI chat. Voice
        // settings (speed/pitch/voice/pause/quality) have moved out
        // entirely to the voice quick sheet above.
        if (showOverflowSheet) {
            val overflowSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showOverflowSheet = false },
                sheetState = overflowSheetState,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                scrimColor = brassScrim,
                tonalElevation = 6.dp,
            ) {
                val overflowScope = rememberCoroutineScope()
                PlayerOverflowSheet(
                    state = state,
                    onStartSleepTimer = onStartSleepTimer,
                    onCancelSleepTimer = onCancelSleepTimer,
                    onPreviousSentence = onPreviousSentence,
                    onNextSentence = onNextSentence,
                    onRequestRecap = {
                        overflowScope.launch { overflowSheetState.hide() }
                        showOverflowSheet = false
                        onRequestRecap()
                    },
                    onOpenChat = {
                        overflowScope.launch { overflowSheetState.hide() }
                        showOverflowSheet = false
                        onOpenChat()
                    },
                    onBookmarkHere = {
                        overflowScope.launch { overflowSheetState.hide() }
                        showOverflowSheet = false
                        onBookmarkHere()
                    },
                    onJumpToBookmark = {
                        overflowScope.launch { overflowSheetState.hide() }
                        showOverflowSheet = false
                        onJumpToBookmark()
                    },
                )
            }
        }
    }
}

@Composable
private fun SleepTimerCountdownChip(remainingMs: Long, onCancel: () -> Unit) {
    val mins = (remainingMs / 60_000L).toInt()
    val secs = ((remainingMs % 60_000L) / 1000L).toInt()
    val reducedMotion = LocalReducedMotion.current
    val motion = LocalMotion.current

    // Last 60s: gentle alpha breath. Last 15s: cross-fade container toward
    // errorContainer hue to signal "almost out of time" without alarm-bell
    // urgency. Reduced motion → static at full alpha + base color.
    val isPulsing = !reducedMotion && remainingMs in 1..60_000
    val isUrgent = remainingMs in 1..15_000

    val pulseAlpha = if (isPulsing) {
        val transition = rememberInfiniteTransition(label = "sleep-timer-breath")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.55f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = motion.standardEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "sleep-timer-alpha",
        ).value
    } else 1f

    val baseContainer = MaterialTheme.colorScheme.primaryContainer
    val urgentContainer = MaterialTheme.colorScheme.errorContainer
    val baseLabel = MaterialTheme.colorScheme.onPrimaryContainer
    val urgentLabel = MaterialTheme.colorScheme.onErrorContainer

    val containerColor by animateColorAsState(
        targetValue = if (isUrgent && !reducedMotion) urgentContainer else baseContainer,
        animationSpec = tween(motion.standardDurationMs, easing = motion.standardEasing),
        label = "sleep-timer-container",
    )
    val labelColor by animateColorAsState(
        targetValue = if (isUrgent && !reducedMotion) urgentLabel else baseLabel,
        animationSpec = tween(motion.standardDurationMs, easing = motion.standardEasing),
        label = "sleep-timer-label",
    )

    AssistChip(
        onClick = onCancel,
        label = {
            Text("Sleeping in ${"%d:%02d".format(mins, secs)}", style = MaterialTheme.typography.labelMedium)
        },
        leadingIcon = {
            Icon(Icons.Outlined.Bedtime, contentDescription = null)
        },
        modifier = Modifier.alpha(pulseAlpha),
        colors = AssistChipDefaults.assistChipColors(
            containerColor = containerColor,
            labelColor = labelColor,
            leadingIconContentColor = labelColor,
        ),
    )
}

/**
 * Issue #418 — the post-split overflow sheet (was `PlayerOptionsSheet`).
 * Voice settings (speed / pitch / voice picker) have moved to the
 * VoiceQuickSheet driven by the brass voice icon. This sheet keeps the
 * remaining non-voice surface: sentence-step transport (#120), sleep
 * timer, bookmark (#121), recap (#81), AI chat.
 *
 * Pre-#418 history: this composable was the single "Player options"
 * sheet behind ⋮; the issue body identified the voice-tuning subset as
 * the high-frequency, low-discoverability slice and pulled it out into
 * its own always-visible icon. Splitting keeps the overflow lean.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerOverflowSheet(
    state: UiPlaybackState,
    onStartSleepTimer: (UiSleepTimerMode) -> Unit,
    onCancelSleepTimer: () -> Unit,
    /** #120 — step back one sentence boundary. No-op at sentence 0. */
    onPreviousSentence: () -> Unit = {},
    /** #120 — step forward one sentence boundary. No-op at chapter end. */
    onNextSentence: () -> Unit = {},
    onRequestRecap: () -> Unit = {},
    onOpenChat: () -> Unit = {},
    /** Issue #121 — bookmark the current playback position. */
    onBookmarkHere: () -> Unit = {},
    /** Issue #121 — seek to the chapter's bookmark, if any. */
    onJumpToBookmark: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        // #120 — sentence-step transport. The main bottom-bar buttons
        // do ±30 s (consistent with audiobook-player muscle memory);
        // these sit in the options sheet for users who want
        // sentence-precision rewind/fast-forward (re-listen the line
        // you just heard, or skip a sentence you didn't want).
        SheetHeader("Step by sentence", null)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrassButton(
                label = "← Previous",
                onClick = onPreviousSentence,
                variant = BrassButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            BrassButton(
                label = "Next →",
                onClick = onNextSentence,
                variant = BrassButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
        }

        SheetHeader("Sleep timer", null)
        SleepTimerChips(
            activeRemainingMs = state.sleepTimerRemainingMs,
            onStart = onStartSleepTimer,
            onCancel = onCancelSleepTimer,
        )

        // Issue #121 — in-chapter bookmark. Two rows: "Bookmark here"
        // drops a marker at the current playback position; "Jump to
        // bookmark" seeks to it. Both fire-and-forget; the controller
        // no-ops gracefully when nothing is loaded / no bookmark exists.
        SheetHeader("Bookmark", null)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onBookmarkHere)
                .padding(vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                Icons.Outlined.BookmarkAdd,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Bookmark here", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Drop a marker at the current position",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onJumpToBookmark)
                .padding(vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                Icons.Outlined.Bookmark,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Jump to bookmark", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Resume from the marker you set",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Issue #418 — the Voice row that used to live here has moved
        // to the dedicated VoiceQuickSheet driven by the brass voice
        // icon in the top bar. The whole voice-tuning surface
        // (speed / pitch / voice picker / pause / sonic quality +
        // Advanced expander) is now one-tap-away on the icon rather
        // than two-tap behind the ⋮.

        // ── Chapter Recap (issue #81) — opens the librarian modal ──
        SheetHeader("Smart features", null)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onRequestRecap)
                .padding(vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                Icons.Outlined.AutoStories,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Recap so far", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Ask the librarian to summarize the last few chapters",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── Q&A chat (#81 follow-up) — opens the librarian chat surface ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenChat)
                .padding(vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                Icons.Outlined.AutoStories,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Ask the AI", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Chat about plot, characters, pacing, and craft",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(spacing.md))
    }
}

@Composable
private fun SheetHeader(title: String, valueLabel: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (valueLabel != null) {
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SleepTimerChips(
    activeRemainingMs: Long?,
    onStart: (UiSleepTimerMode) -> Unit,
    onCancel: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val isActive = activeRemainingMs != null

    // #249 — FlowRow instead of Row so the 6 chips wrap to a second
    // line on phone-narrow screens (Flip3 = 1080 px) instead of
    // squashing the rightmost chip to ~67 px wide. FlowRow preserves
    // natural chip widths and breaks at chip boundaries; on tablets
    // and unfolded foldables they still render in one row.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
        modifier = Modifier.fillMaxWidth(),
    ) {
        FilterChip(
            selected = !isActive,
            onClick = { onCancel() },
            label = { Text("Off") },
            colors = brassFilterChipColors(),
        )
        listOf(15, 30, 45, 60).forEach { minutes ->
            FilterChip(
                selected = false,
                onClick = { onStart(UiSleepTimerMode.Duration(minutes)) },
                label = { Text("${minutes}m") },
                colors = brassFilterChipColors(),
            )
        }
        FilterChip(
            selected = false,
            onClick = { onStart(UiSleepTimerMode.EndOfChapter) },
            label = { Text("End") },
            colors = brassFilterChipColors(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun brassFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

/**
 * Issue #284 — format the voiceId carried on [UiPlaybackState.voiceLabel]
 * into a human-readable `[engine] · [voice]` string for the player-
 * options sheet.
 *
 *  - `piper:en_US-amy-medium`            → `Piper · en_US-amy-medium`
 *  - `azure:en-US-AvaMultilingualNeural` → `Azure · en-US-AvaMultilingualNeural`
 *  - `voxsherpa:tier3/narrator-warm`     → `VoxSherpa · tier3/narrator-warm`
 *  - `Default` (no active voice yet)     → `Default`
 *  - bare strings without `:` prefix     → returned untouched
 *
 *  We deliberately don't try to resolve the voice's *display name* from
 *  the voice catalog here — that would couple this composable to the
 *  voicelibrary module + force a Hilt-injected lookup at every Player
 *  Options sheet open. The raw voice id is already engine + voice name;
 *  reformatting the prefix is enough for the QA / verification use case
 *  the issue calls out.
 */
internal fun formatVoiceLabel(raw: String): String {
    if (raw.isBlank() || !raw.contains(':')) return raw
    val (engineId, voiceId) = raw.split(':', limit = 2)
    val engine = when (engineId.lowercase()) {
        "piper" -> "Piper"
        "azure" -> "Azure"
        "voxsherpa", "sherpa", "kokoro" -> "VoxSherpa"
        "android", "system" -> "System TTS"
        else -> engineId.replaceFirstChar { it.uppercase() }
    }
    return "$engine · $voiceId"
}
