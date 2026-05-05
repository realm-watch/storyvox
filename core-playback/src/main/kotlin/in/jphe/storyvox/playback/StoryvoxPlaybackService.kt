package `in`.jphe.storyvox.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SimpleBitmapLoader
import dagger.hilt.android.AndroidEntryPoint
import `in`.jphe.storyvox.playback.tts.TtsPlayer
import `in`.jphe.storyvox.playback.wear.PhoneWearBridge
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The MediaSessionService that hosts our [TtsPlayer] and surfaces the audiobook to
 * every Android-side controller: the notification shade, the lock screen, Bluetooth
 * media buttons, Wear, and Auto.
 *
 * Notification + lock-screen rendering is fully delegated to Media3:
 *  - [DefaultMediaNotificationProvider] turns the bound [MediaSession] into a
 *    `MediaStyleNotificationHelper.MediaStyle` notification, derives transport
 *    actions from `Player.Commands`, and re-issues on every state change.
 *  - [CacheBitmapLoader] over [SimpleBitmapLoader] resolves the `artworkUri` set
 *    by [TtsPlayer] in its [androidx.media3.common.MediaMetadata] and caches the
 *    decoded bitmap — that bitmap is used as the notification's large icon and as
 *    the lock-screen background.
 *
 * We don't drive `startForeground` ourselves; the framework promotes the service
 * automatically once a controller binds and a notification is posted.
 */
@UnstableApi
@AndroidEntryPoint
class StoryvoxPlaybackService : MediaSessionService() {

    @Inject lateinit var controller: DefaultPlaybackController
    @Inject lateinit var ttsPlayerFactory: TtsPlayer.Factory
    @Inject lateinit var wearBridge: PhoneWearBridge
    @Inject lateinit var mediaSessionLocator: MediaSessionLocator

    private lateinit var session: MediaSession
    private lateinit var player: TtsPlayer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()

        player = ttsPlayerFactory.create(applicationContext)
        controller.bindPlayer(player)

        session = MediaSession.Builder(this, player)
            .setCallback(StoryvoxSessionCallback(controller, scope))
            .setBitmapLoader(CacheBitmapLoader(SimpleBitmapLoader()))
            .build()
        mediaSessionLocator.token = session.token

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_PLAYBACK)
                .setNotificationId(NOTIFICATION_ID)
                .build()
                .apply { setSmallIcon(R.drawable.ic_storyvox_notif) },
        )

        wearBridge.start()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    /**
     * If the user swipes the app off recents while audio is still playing, keep the
     * service alive — playback continues from the foreground notification. Only stop
     * if we were idle.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!controller.state.value.isPlaying) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        wearBridge.stop()
        controller.unbindPlayer()
        session.release()
        player.releaseTts()
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_PLAYBACK) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_PLAYBACK,
                        "Playback",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = "Audiobook playback controls"
                        setShowBadge(false)
                    },
                )
            }
        }
    }

    companion object {
        const val CHANNEL_PLAYBACK = "playback"
        const val NOTIFICATION_ID = 1042
    }
}
