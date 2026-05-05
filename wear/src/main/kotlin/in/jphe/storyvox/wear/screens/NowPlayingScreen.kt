package `in`.jphe.storyvox.wear.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.wear.compose.material.Button
import `in`.jphe.storyvox.playback.wear.PhoneWearBridge
import kotlinx.coroutines.launch
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import `in`.jphe.storyvox.wear.playback.WearPlaybackBridge

/**
 * Minimal Wear OS now-playing screen — chapter title + play/pause toggle.
 *
 * v1 stub. The richer Library Nocturne styling and circular scrubber land in a
 * polish pass; this skeleton proves the bridge end-to-end and compiles against
 * Wear Material 1.4.0 (M2). Once Wear Material 3 stabilizes we can swap.
 */
@Composable
fun NowPlayingScreen(bridge: WearPlaybackBridge) {
    val state by bridge.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Scaffold(timeText = { TimeText() }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(16.dp),
            ) {
                Text(
                    text = state.bookTitle ?: "storyvox",
                    style = MaterialTheme.typography.title3,
                )
                Text(
                    text = state.chapterTitle ?: "Tap to play",
                    style = MaterialTheme.typography.body2,
                )
                Button(onClick = {
                    val cmd = if (state.isPlaying) PhoneWearBridge.CMD_PAUSE else PhoneWearBridge.CMD_PLAY
                    scope.launch { bridge.send(cmd) }
                }) {
                    Text(if (state.isPlaying) "⏸" else "▶")
                }
            }
        }
    }
}
