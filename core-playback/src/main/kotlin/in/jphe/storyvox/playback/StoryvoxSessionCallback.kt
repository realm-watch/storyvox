package `in`.jphe.storyvox.playback

import android.content.Intent
import android.view.KeyEvent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope

@UnstableApi
class StoryvoxSessionCallback(
    controller: DefaultPlaybackController,
    scope: CoroutineScope,
) : MediaSession.Callback {

    private val mediaButtonHandler = MediaButtonHandler(controller, scope)

    override fun onMediaButtonEvent(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
        intent: Intent,
    ): Boolean {
        val event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            ?: return false
        return mediaButtonHandler.handle(event)
    }
}
