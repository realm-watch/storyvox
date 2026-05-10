package `in`.jphe.storyvox.feature.reader

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RecordVoiceOver
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
import `in`.jphe.storyvox.ui.component.BrassProgressTrack
import `in`.jphe.storyvox.ui.component.FictionCoverThumb
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
    var showSheet by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = { showSheet = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Player options")
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
                    FictionCoverThumb(
                        coverUrl = state.coverUrl,
                        title = state.fictionTitle,
                        authorInitial = state.fictionTitle.firstOrNull()?.uppercaseChar() ?: '?',
                        modifier = Modifier.size(width = 220.dp, height = 330.dp),
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
                    Icon(Icons.Filled.FastRewind, contentDescription = "Skip back 30 seconds", modifier = Modifier.size(32.dp))
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
                    Icon(Icons.Filled.FastForward, contentDescription = "Skip forward 30 seconds", modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = onNextChapter) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next chapter", modifier = Modifier.size(32.dp))
                }
            }
        }

        if (showSheet) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val coroutineScope = rememberCoroutineScope()
            // Candlelight scrim — a translucent brass tone composited over
            // the default scrim color, instead of a flat black dim. Reads
            // as "the light's been turned down", not "the screen got eaten".
            val brassScrim = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                .compositeOver(MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f))
            ModalBottomSheet(
                onDismissRequest = { showSheet = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                scrimColor = brassScrim,
                tonalElevation = 6.dp,
            ) {
                PlayerOptionsSheet(
                    state = state,
                    onSetSpeed = onSetSpeed,
                    onPersistSpeed = onPersistSpeed,
                    onSetPitch = onSetPitch,
                    onPersistPitch = onPersistPitch,
                    onStartSleepTimer = onStartSleepTimer,
                    onCancelSleepTimer = onCancelSleepTimer,
                    onPickVoice = {
                        coroutineScope.launch { sheetState.hide() }
                        showSheet = false
                        onPickVoice()
                    },
                    onRequestRecap = {
                        coroutineScope.launch { sheetState.hide() }
                        showSheet = false
                        onRequestRecap()
                    },
                    onOpenChat = {
                        coroutineScope.launch { sheetState.hide() }
                        showSheet = false
                        onOpenChat()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerOptionsSheet(
    state: UiPlaybackState,
    onSetSpeed: (Float) -> Unit,
    onPersistSpeed: (Float) -> Unit,
    onSetPitch: (Float) -> Unit,
    onPersistPitch: (Float) -> Unit,
    onStartSleepTimer: (UiSleepTimerMode) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onPickVoice: () -> Unit,
    onRequestRecap: () -> Unit = {},
    onOpenChat: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        SheetHeader("Speed", "${"%.2f".format(state.speed)}×")
        Slider(
            value = state.speed,
            onValueChange = onSetSpeed,
            onValueChangeFinished = { onPersistSpeed(state.speed) },
            valueRange = 0.5f..3.0f,
            steps = 49, // 0.05× per step
            // TalkBack #160 — without these, the slider announces a raw
            // float ("1.25") instead of a meaningful value ("Speech speed,
            // 1.25 times").
            modifier = Modifier.semantics {
                contentDescription = "Speech speed"
                stateDescription = "%.2f times".format(state.speed)
            },
        )

        SheetHeader("Pitch", "${"%.2f".format(state.pitch)}×")
        Slider(
            value = state.pitch,
            onValueChange = onSetPitch,
            onValueChangeFinished = { onPersistPitch(state.pitch) },
            // Narration-friendly band — matches Settings → Reading. Widened
            // from 0.85..1.15 (Thalia's VoxSherpa P0 #1, 2026-05-08) for
            // narrator-baritone headroom. Hard floor at 0.6 — below ~0.7
            // Sonic introduces audible artifacts on Piper-medium voices.
            valueRange = 0.6f..1.4f,
            steps = 79, // 0.01 per step
            // TalkBack #160 — neutral pitch is 1.0 (no shift); semantics
            // calls that out so users know what the number references.
            modifier = Modifier.semantics {
                contentDescription = "Pitch"
                stateDescription = "%.2f, neutral at one".format(state.pitch)
            },
        )

        SheetHeader("Sleep timer", null)
        SleepTimerChips(
            activeRemainingMs = state.sleepTimerRemainingMs,
            onStart = onStartSleepTimer,
            onCancel = onCancelSleepTimer,
        )

        SheetHeader("Voice", null)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(Icons.Outlined.RecordVoiceOver, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(state.voiceLabel, style = MaterialTheme.typography.bodyMedium)
                Text("Tap to change", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onPickVoice) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = "Pick voice")
            }
        }

        // ── Chapter Recap (issue #81) — opens the librarian modal ──
        SheetHeader("Smart features", null)
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
            IconButton(onClick = onRequestRecap) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = "Recap so far",
                )
            }
        }

        // ── Q&A chat (#81 follow-up) — opens the librarian chat surface ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
            IconButton(onClick = onOpenChat) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = "Ask the AI",
                )
            }
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
