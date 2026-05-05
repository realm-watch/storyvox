package `in`.jphe.storyvox.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.ui.component.HybridReaderShell

@Composable
fun HybridReaderScreen(
    onPickVoice: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playback = state.playback

    if (playback == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No chapter loaded.", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    HybridReaderShell(
        current = state.activePane,
        onViewChange = viewModel::setActivePane,
        audiobookContent = {
            AudiobookView(
                state = playback,
                onPlayPause = viewModel::playPause,
                onSeekTo = viewModel::seekTo,
                onSkipForward = viewModel::skipForward,
                onSkipBack = viewModel::skipBack,
                onNextChapter = viewModel::nextChapter,
                onPreviousChapter = viewModel::previousChapter,
                onPickVoice = onPickVoice,
            )
        },
        readerContent = {
            ReaderTextView(
                state = playback,
                chapterText = state.chapterText,
                onPlayPause = viewModel::playPause,
            )
        },
    )
}
