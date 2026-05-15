package `in`.jphe.storyvox.widget

import `in`.jphe.storyvox.playback.PlaybackState

/**
 * Issue #159 — minimal projection of [PlaybackState] that the widget
 * surface actually needs. Keeping the renderer working from this
 * narrow type rather than the full PlaybackState means:
 *  - tests can construct exact widget scenarios without standing up
 *    the full PlaybackState constructor (which transitively pulls
 *    SentenceRange, PlaybackError, etc.),
 *  - the equality check WidgetStateObserver uses for distinctUntilChanged
 *    only fires when a widget-relevant field actually changes, not
 *    when (e.g.) a sentence-range crosses a boundary.
 *
 * `coverUri` carries the same string format `PlaybackState.coverUri`
 * does — file path, content:// uri, or http(s) url. The renderer
 * passes it straight through to `RemoteViews.setImageViewUri` for
 * local file/content URIs; remote URLs fall back to the placeholder
 * (RemoteViews can't load arbitrary HTTP; a follow-up issue can wire
 * a Coil-side bitmap fetch + setImageViewBitmap if anyone asks).
 */
data class NowPlayingSnapshot(
    val isIdle: Boolean,
    val isPlaying: Boolean,
    val bookTitle: String?,
    val chapterTitle: String?,
    val coverUri: String?,
    val fictionId: String?,
    val chapterId: String?,
    val sleepTimerRemainingMs: Long?,
) {
    companion object {
        /** No fiction loaded — drives the "Nothing playing" copy and
         *  routes taps to MainActivity → Library instead of a reader. */
        val Idle = NowPlayingSnapshot(
            isIdle = true,
            isPlaying = false,
            bookTitle = null,
            chapterTitle = null,
            coverUri = null,
            fictionId = null,
            chapterId = null,
            sleepTimerRemainingMs = null,
        )

        fun from(state: PlaybackState): NowPlayingSnapshot {
            val idle = state.currentFictionId.isNullOrBlank() ||
                state.currentChapterId.isNullOrBlank()
            return NowPlayingSnapshot(
                isIdle = idle,
                isPlaying = state.isPlaying,
                bookTitle = state.bookTitle?.takeIf { it.isNotBlank() },
                chapterTitle = state.chapterTitle?.takeIf { it.isNotBlank() },
                coverUri = state.coverUri?.takeIf { it.isNotBlank() },
                fictionId = state.currentFictionId,
                chapterId = state.currentChapterId,
                sleepTimerRemainingMs = state.sleepTimerRemainingMs,
            )
        }
    }
}
