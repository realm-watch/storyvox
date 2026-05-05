package `in`.jphe.storyvox.playback

import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Bluetooth multi-tap mapper:
 * - 1 tap   → play/pause
 * - 2 taps  → skip forward 30s
 * - 3 taps  → previous chapter
 * - long press → toggle sleep timer (15min)
 *
 * `next` / `previous` button keycodes (3-button headphones) bypass the multi-tap layer
 * and map directly to chapter navigation.
 *
 * 400ms multi-tap window — single-tap play/pause has a 400ms latency. Acceptable cost
 * for reliable multi-tap detection.
 */
class MediaButtonHandler(
    private val controller: PlaybackController,
    private val scope: CoroutineScope,
) {
    private var tapCount = 0
    private var lastTapAt = 0L
    private var pendingDispatch: Job? = null

    fun handle(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                scope.launch { controller.nextChapter() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                scope.launch { controller.previousChapter() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                handleMultiTap(event)
                return true
            }
        }
        return false
    }

    private fun handleMultiTap(event: KeyEvent) {
        val isLong = event.isLongPress ||
            (event.eventTime - event.downTime) > LONG_PRESS_MS
        if (isLong) {
            controller.toggleSleepTimer()
            tapCount = 0
            pendingDispatch?.cancel()
            return
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastTapAt > MULTI_TAP_WINDOW_MS) tapCount = 0
        lastTapAt = now
        tapCount += 1

        pendingDispatch?.cancel()
        pendingDispatch = scope.launch {
            delay(MULTI_TAP_WINDOW_MS)
            when (tapCount) {
                1 -> controller.togglePlayPause()
                2 -> controller.skipForward30s()
                3 -> controller.previousChapter()
                else -> { /* 4+ ignored */ }
            }
            tapCount = 0
        }
    }

    companion object {
        private const val MULTI_TAP_WINDOW_MS = 400L
        private const val LONG_PRESS_MS = 600L
    }
}
