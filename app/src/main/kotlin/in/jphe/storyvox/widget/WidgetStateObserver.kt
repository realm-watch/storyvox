package `in`.jphe.storyvox.widget

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #159 — pushes [NowPlayingWidgetProvider] refresh broadcasts
 * whenever the user-visible slice of [PlaybackController.state]
 * changes. Owned by [`in`.jphe.storyvox.StoryvoxApp]; started in
 * the deferred-init coroutine that materializes the rest of the Hilt
 * graph so the collector spins up after the cold-launch frame budget.
 *
 * Why not WorkManager periodic?
 *   PlaybackController.state is already a hot StateFlow with the
 *   exact data we render. A WorkManager periodic adds 15-minute
 *   latency and burns battery sampling state that hasn't moved. A
 *   collector on the existing StateFlow is push-driven and costs
 *   one coroutine + one extra broadcast per state change.
 *
 * Why not call AppWidgetManager.updateAppWidget directly here?
 *   The broadcast roundtrip keeps the host-id enumeration + layout-
 *   picking in ONE place ([NowPlayingWidgetProvider.onReceive]).
 *   Without it, this class would need to know about every layout
 *   bucket and every PendingIntent the provider wires — duplicating
 *   the renderer. The broadcast overhead is microseconds; the code-
 *   shape win is worth it.
 *
 * Why a singleton on the app classpath?
 *   :app is the only module that can take a hard dep on
 *   [PlaybackController] AND own the widget receiver, since
 *   AppWidgetProvider must live in the application's manifest. The
 *   :core-playback module doesn't know about widgets; the widget
 *   receiver couldn't go there anyway per Android constraints.
 */
@Singleton
class WidgetStateObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: PlaybackController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /**
     * Idempotent — calling [start] twice is a no-op. The caller
     * (StoryvoxApp.onCreate's deferred-init block) invokes this
     * once at startup after the Hilt graph is materialized.
     */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            controller.state
                // Project to the same slice the renderer consumes.
                // Without distinctUntilChanged the widget would re-
                // render at every sentence boundary (the
                // currentSentenceRange field on PlaybackState ticks
                // 5-10x/sec under playback). distinctUntilChanged on
                // a narrow projection means we broadcast only when
                // a user-visible field actually moved.
                .map { state ->
                    WidgetVisibleSlice(
                        isPlaying = state.isPlaying,
                        bookTitle = state.bookTitle,
                        chapterTitle = state.chapterTitle,
                        coverUri = state.coverUri,
                        fictionId = state.currentFictionId,
                        chapterId = state.currentChapterId,
                        // Round to whole seconds so the 1Hz timer
                        // tick doesn't pummel the widget host.
                        sleepRemainingSec = state.sleepTimerRemainingMs?.div(1000L),
                    )
                }
                .distinctUntilChanged()
                .collect {
                    NowPlayingWidgetProvider.broadcastRefresh(context)
                }
        }
    }

    /** Test / shutdown hook. Not wired today — the app process is the
     *  observer's natural lifetime. Future: tear down on
     *  ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN if profile data shows
     *  the collector is meaningful idle-state cost. */
    internal fun stop() {
        job?.cancel()
        job = null
    }
}

/**
 * Internal equality basis for [WidgetStateObserver]'s
 * `distinctUntilChanged`. Mirrors [NowPlayingSnapshot] but with a
 * second-resolution sleep timer so a 1-second timer tick collapses
 * to no broadcast unless the displayed value actually changes.
 */
private data class WidgetVisibleSlice(
    val isPlaying: Boolean,
    val bookTitle: String?,
    val chapterTitle: String?,
    val coverUri: String?,
    val fictionId: String?,
    val chapterId: String?,
    val sleepRemainingSec: Long?,
)
