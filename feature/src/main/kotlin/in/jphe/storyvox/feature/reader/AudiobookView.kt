package `in`.jphe.storyvox.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.feature.api.UiPlaybackState
import `in`.jphe.storyvox.feature.api.UiSleepTimerMode
import `in`.jphe.storyvox.ui.component.BrassProgressTrack
import `in`.jphe.storyvox.ui.component.FictionCoverThumb
import `in`.jphe.storyvox.ui.component.MagicSkeletonTile
import `in`.jphe.storyvox.ui.component.MagicSpinner
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
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
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
            val warmingUp = state.isPlaying && state.sentenceEnd == 0
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
                    // out as soon as audio actually starts (sentenceEnd > 0).
                    if (warmingUp) {
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
                when {
                    state.chapterTitle.isBlank() -> "Loading voice + chapter text"
                    warmingUp -> "Voice waking up…"
                    else -> state.chapterTitle
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (warmingUp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.xs))
            BrassProgressTrack(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                onSeekTo = onSeekTo,
                modifier = Modifier.fillMaxWidth(),
                loading = warmingUp,
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
                val warmingUp = state.isPlaying && state.sentenceEnd == 0
                Box(contentAlignment = Alignment.Center) {
                    if (warmingUp) {
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
            ModalBottomSheet(
                onDismissRequest = { showSheet = false },
                sheetState = sheetState,
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
                )
            }
        }
    }
}

@Composable
private fun SleepTimerCountdownChip(remainingMs: Long, onCancel: () -> Unit) {
    val mins = (remainingMs / 60_000L).toInt()
    val secs = ((remainingMs % 60_000L) / 1000L).toInt()
    AssistChip(
        onClick = onCancel,
        label = {
            Text("Sleeping in ${"%d:%02d".format(mins, secs)}", style = MaterialTheme.typography.labelMedium)
        },
        leadingIcon = {
            Icon(Icons.Outlined.Bedtime, contentDescription = null)
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
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
            steps = 9,
        )

        SheetHeader("Pitch", "${"%.2f".format(state.pitch)}")
        Slider(
            value = state.pitch,
            onValueChange = onSetPitch,
            onValueChangeFinished = { onPersistPitch(state.pitch) },
            valueRange = 0.5f..2.0f,
            steps = 5,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerChips(
    activeRemainingMs: Long?,
    onStart: (UiSleepTimerMode) -> Unit,
    onCancel: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val isActive = activeRemainingMs != null

    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
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
