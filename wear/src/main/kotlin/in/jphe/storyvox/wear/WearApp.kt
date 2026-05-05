package `in`.jphe.storyvox.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import `in`.jphe.storyvox.wear.playback.WearPlaybackBridge
import `in`.jphe.storyvox.wear.screens.NowPlayingScreen

class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WearAppRoot() }
    }
}

@Composable
fun WearAppRoot() {
    val context = LocalContext.current
    val bridge = remember { WearPlaybackBridge(context.applicationContext) }
    DisposableEffect(bridge) {
        bridge.start()
        onDispose { bridge.stop() }
    }
    NowPlayingScreen(bridge = bridge)
}
