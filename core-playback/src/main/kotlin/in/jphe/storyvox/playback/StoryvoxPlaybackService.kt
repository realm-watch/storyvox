package `in`.jphe.storyvox.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import `in`.jphe.storyvox.playback.notification.NotificationBuilder
import `in`.jphe.storyvox.playback.tts.TtsPlayer
import `in`.jphe.storyvox.playback.wear.PhoneWearBridge
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@UnstableApi
@AndroidEntryPoint
class StoryvoxPlaybackService : MediaSessionService() {

    @Inject lateinit var controller: DefaultPlaybackController
    @Inject lateinit var ttsPlayerFactory: TtsPlayer.Factory
    @Inject lateinit var notificationBuilder: NotificationBuilder
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
            .build()
        mediaSessionLocator.token = session.token

        scope.launch {
            controller.state.collect { state ->
                val notif = notificationBuilder.build(state, session.platformToken)
                ContextCompat.startForegroundService(
                    this@StoryvoxPlaybackService,
                    Intent(this@StoryvoxPlaybackService, StoryvoxPlaybackService::class.java),
                )
                startForeground(NOTIFICATION_ID, notif)
            }
        }

        wearBridge.start()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

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
