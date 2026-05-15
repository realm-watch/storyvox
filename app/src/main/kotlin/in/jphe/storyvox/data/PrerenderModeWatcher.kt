package `in`.jphe.storyvox.data

import android.util.Log
import `in`.jphe.storyvox.data.repository.FictionRepository
import `in`.jphe.storyvox.data.repository.playback.PlaybackModeConfig
import `in`.jphe.storyvox.playback.cache.PrerenderTriggers
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * PR-F (#86) — listens for [PlaybackModeConfig.fullPrerender] flips and,
 * when it goes false → true, enqueues all library fictions' chapters
 * via [PrerenderTriggers.onFullPrerenderEnabled]. Lives in `:app` to
 * keep `:core-playback` free of the [FictionRepository] dependency
 * (the playback layer should depend on the data layer, not the
 * reverse).
 *
 * Started from [StoryvoxApp.onCreate] via [start]; the scope is
 * Singleton-tied so the collector survives the app lifetime.
 *
 * Skips the initial emission via [drop] so a process restart with
 * Mode C already on doesn't re-enqueue every chapter every cold
 * launch (the scheduler's KEEP policy would dedupe but the wasted
 * WorkManager round-trips are needless).
 */
@Singleton
class PrerenderModeWatcher @Inject constructor(
    private val modeConfig: PlaybackModeConfig,
    private val triggers: PrerenderTriggers,
    private val fictionRepo: FictionRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            modeConfig.fullPrerender
                .distinctUntilChanged()
                .drop(1) // skip initial DataStore emission on cold start
                .filter { it } // only on false → true
                .collectLatest {
                    val library = fictionRepo.observeLibrary().first()
                    Log.i(
                        LOG_TAG,
                        "pcm-cache MODE-C-ENABLED libraryCount=${library.size}",
                    )
                    triggers.onFullPrerenderEnabled(library.map { it.id })
                }
        }
    }

    companion object {
        private const val LOG_TAG = "PrerenderModeWatcher"
    }
}
