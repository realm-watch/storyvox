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
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val recapState by viewModel.recap.collectAsStateWithLifecycle()
    val recapPlayback by viewModel.recapPlayback.collectAsStateWithLifecycle()
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
                onSetSpeed = viewModel::setSpeed,
                onPersistSpeed = viewModel::persistSpeed,
                onSetPitch = viewModel::setPitch,
                onPersistPitch = viewModel::persistPitch,
                onStartSleepTimer = viewModel::startSleepTimer,
                onCancelSleepTimer = viewModel::cancelSleepTimer,
                onRequestRecap = viewModel::requestRecap,
                onOpenChat = {
                    playback.fictionId?.let { onOpenChat(it, null) }
                },
            )
        },
        readerContent = {
            ReaderTextView(
                state = playback,
                chapterText = state.chapterText,
                onPlayPause = viewModel::playPause,
                onSeekToChar = viewModel::seekToChar,
                onAskAiAbout = { question ->
                    // Long-press character lookup (#188): forward the
                    // prebuilt "Who is X?" question as a chat prefill.
                    // The chat surface auto-fills the input — the user
                    // can edit before sending or send as-is.
                    playback.fictionId?.let { onOpenChat(it, question) }
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
}
