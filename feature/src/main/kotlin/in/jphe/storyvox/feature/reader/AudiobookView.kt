package `in`.jphe.storyvox.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.feature.api.UiPlaybackState
import `in`.jphe.storyvox.ui.component.BrassProgressTrack
import `in`.jphe.storyvox.ui.component.FictionCoverThumb
import `in`.jphe.storyvox.ui.component.VoiceChip
import `in`.jphe.storyvox.ui.theme.LocalSpacing

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
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxSize().padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Spacer(Modifier.height(spacing.lg))
        FictionCoverThumb(
            coverUrl = state.coverUrl,
            title = state.fictionTitle,
            authorInitial = state.fictionTitle.firstOrNull()?.uppercaseChar() ?: '?',
            modifier = Modifier.size(width = 220.dp, height = 330.dp),
        )
        Text(state.fictionTitle, style = MaterialTheme.typography.titleLarge)
        Text(state.chapterTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(spacing.xs))
        BrassProgressTrack(
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            onSeekTo = onSeekTo,
            modifier = Modifier.fillMaxWidth(),
        )
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
            IconButton(onClick = onSkipForward) {
                Icon(Icons.Filled.FastForward, contentDescription = "Skip forward 30 seconds", modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = onNextChapter) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next chapter", modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.height(spacing.xs))
        VoiceChip(voiceName = state.voiceLabel, onClick = onPickVoice)
    }
}
