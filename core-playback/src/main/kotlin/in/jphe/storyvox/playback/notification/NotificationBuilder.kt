package `in`.jphe.storyvox.playback.notification

import android.app.Notification
import android.content.Context
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.playback.PlaybackState
import `in`.jphe.storyvox.playback.R
import `in`.jphe.storyvox.playback.StoryvoxPlaybackService.Companion.CHANNEL_PLAYBACK
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Build a MediaStyle notification. [sessionToken] is the platform-level
     * MediaSession token (`session.platformToken` on a Media3 MediaSession), which
     * we wrap as a [MediaSessionCompat.Token] for [MediaStyle].
     */
    fun build(
        state: PlaybackState,
        sessionToken: android.media.session.MediaSession.Token,
    ): Notification {
        val compatToken = MediaSessionCompat.Token.fromToken(sessionToken)
        val style = MediaStyle()
            .setMediaSession(compatToken)
            .setShowActionsInCompactView(0, 1, 2)

        return NotificationCompat.Builder(context, CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_storyvox_notif)
            .setContentTitle(state.chapterTitle ?: "Storyvox")
            .setContentText(state.bookTitle ?: "")
            .setStyle(style)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(state.isPlaying)
            .setOnlyAlertOnce(true)
            .build()
    }
}
