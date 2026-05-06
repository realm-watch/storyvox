package `in`.jphe.storyvox.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.session.SimpleBitmapLoader
import dagger.hilt.android.AndroidEntryPoint
import `in`.jphe.storyvox.playback.tts.EnginePlayer
import `in`.jphe.storyvox.playback.wear.PhoneWearBridge
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The MediaSessionService that hosts our [EnginePlayer] and surfaces the audiobook to
 * every Android-side controller: the notification shade, the lock screen, Bluetooth
 * media buttons, Wear, and Auto.
 *
 * Notification + lock-screen rendering is fully delegated to Media3:
 *  - [DefaultMediaNotificationProvider] turns the bound [MediaSession] into a
 *    `MediaStyleNotificationHelper.MediaStyle` notification, derives transport
 *    actions from `Player.Commands`, and re-issues on every state change.
 *  - [CacheBitmapLoader] over [SimpleBitmapLoader] resolves the `artworkUri` set
 *    by [EnginePlayer] in its [androidx.media3.common.MediaMetadata] and caches the
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
    @Inject lateinit var enginePlayerFactory: EnginePlayer.Factory
    @Inject lateinit var wearBridge: PhoneWearBridge
    @Inject lateinit var mediaSessionLocator: MediaSessionLocator

    private lateinit var session: MediaSession
    private lateinit var player: EnginePlayer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Reflects the current session-activity intent so it can be refreshed
     *  whenever fictionId/chapterId changes — notification tap then opens the
     *  correct reader, not whatever was playing the moment the service started. */
    private var sessionActivityJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()

        player = enginePlayerFactory.create(applicationContext)
        controller.bindPlayer(player)

        session = MediaSession.Builder(this, player)
            .setCallback(StoryvoxSessionCallback(controller, scope))
            .setBitmapLoader(CacheBitmapLoader(SimpleBitmapLoader()))
            .setSessionActivity(buildSessionActivity(null, null))
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

        // Refresh the session activity intent whenever the playing chapter
        // changes so notification taps open the right reader.
        sessionActivityJob = scope.launch {
            controller.state
                .map { it.currentFictionId to it.currentChapterId }
                .distinctUntilChanged()
                .collect { (fid, cid) ->
                    session.setSessionActivity(buildSessionActivity(fid, cid))
                }
        }
    }

    /**
     * Builds the PendingIntent that fires when the user taps the playback
     * notification (or the lock-screen tile). We launch [MAIN_ACTIVITY] with
     * extras that [in.jphe.storyvox.navigation.DeepLinkResolver] reads to
     * navigate straight to the reader for the playing chapter. With a null
     * fiction/chapter id (e.g. before playback starts) we still launch
     * MainActivity — the user lands on Library which is the right default.
     */
    private fun buildSessionActivity(fictionId: String?, chapterId: String?): PendingIntent {
        val launchIntent = Intent().apply {
            component = ComponentName(packageName, MAIN_ACTIVITY)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!fictionId.isNullOrBlank() && !chapterId.isNullOrBlank()) {
                putExtra(EXTRA_OPEN_READER_FICTION_ID, fictionId)
                putExtra(EXTRA_OPEN_READER_CHAPTER_ID, chapterId)
            }
        }
        // FLAG_UPDATE_CURRENT so the new fictionId/chapterId actually overwrite
        // any previous intent extras (PendingIntent equality ignores extras by
        // default — without UPDATE_CURRENT we'd keep firing the original chapter).
        return TaskStackBuilder.create(this)
            .addNextIntent(launchIntent)
            .getPendingIntent(
                /* requestCode = */ 0,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )!!
    }

    /** Placeholder posted once to satisfy Android's 5-sec FG deadline. After
     * Media3's [DefaultMediaNotificationProvider] takes over (when the player
     * reports a media item + isPlaying state), we stop touching the
     * notification — the same NOTIFICATION_ID gets re-targeted by Media3's
     * `startForeground(...)` calls and our placeholder dissolves. */
    private var placeholderPosted = false

    /**
     * Android requires a foreground service started via `startForegroundService()` to
     * call `startForeground()` within 5 seconds, or the OS kills the app with
     * `ForegroundServiceDidNotStartInTimeException`. Media3's
     * [DefaultMediaNotificationProvider] only posts the real MediaStyle notification
     * once the player is actually playing — but storyvox waits for an HTTP fetch +
     * DB write before play, which can easily exceed 5 s on a cold first listen.
     *
     * We post the placeholder only ONCE (on first start), then defer entirely to
     * Media3. If we re-posted on every onStartCommand, the placeholder would
     * overwrite the MediaStyle notification because both share NOTIFICATION_ID.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!placeholderPosted) {
            // Use a MediaStyle-shaped placeholder so Media3's
            // DefaultMediaNotificationProvider replaces it cleanly when the
            // player actually starts. A plain notification can fail to be
            // re-targeted if the system has already memoized it as
            // user-supplied chrome.
            val placeholder = NotificationCompat.Builder(this, CHANNEL_PLAYBACK)
                .setSmallIcon(R.drawable.ic_storyvox_notif)
                .setContentTitle("storyvox")
                .setContentText("Loading…")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setStyle(MediaStyleNotificationHelper.MediaStyle(session))
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    placeholder,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, placeholder)
            }
            placeholderPosted = true
        }
        return super.onStartCommand(intent, flags, startId)
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
        player.releaseEngine()
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
        /** FQN of the launcher Activity. Hardcoded so :core-playback doesn't
         *  need a dependency on :app. Mirrors the manifest entry. */
        private const val MAIN_ACTIVITY = "in.jphe.storyvox.MainActivity"
        /** Mirrors [in.jphe.storyvox.navigation.DeepLinkResolver.EXTRA_OPEN_READER_FICTION_ID]. */
        private const val EXTRA_OPEN_READER_FICTION_ID = "storyvox.open_reader.fiction_id"
        private const val EXTRA_OPEN_READER_CHAPTER_ID = "storyvox.open_reader.chapter_id"
    }
}
