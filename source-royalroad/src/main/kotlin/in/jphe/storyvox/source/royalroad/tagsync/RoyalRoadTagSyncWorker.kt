package `in`.jphe.storyvox.source.royalroad.tagsync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic worker — pulls a single tag-sync round-trip (RR ↔
 * storyvox) on a 24h cadence (issue #178).
 *
 * Constraints (set by the `:core-data` `WorkScheduler` when it
 * enqueues this — see [TAG_SYNC_INTERVAL_HOURS]):
 *
 *  - Network: `CONNECTED` — RR is online-only by definition.
 *  - Battery not low: yes — saved tags aren't load-bearing
 *    enough to drain a phone that's already running on fumes.
 *
 * The actual sync logic lives in [RoyalRoadTagSyncCoordinator] —
 * this worker is just the WorkManager surface. Failures return
 * `Result.success(...)` with a tagged Data payload (telemetry)
 * rather than `Result.failure()`, because we don't want
 * WorkManager's exponential backoff stacking up: the spec is
 * "retry at next 24h tick," and `Result.success` is the right
 * code for that.
 */
@HiltWorker
class RoyalRoadTagSyncWorker @AssistedInject internal constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: RoyalRoadTagSyncCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val outcome = coordinator.syncNow()
        val tag = when (outcome) {
            is RoyalRoadTagSyncCoordinator.Outcome.Ok -> "ok"
            RoyalRoadTagSyncCoordinator.Outcome.NotAuthenticated -> "not-authenticated"
            RoyalRoadTagSyncCoordinator.Outcome.Disabled -> "disabled"
            is RoyalRoadTagSyncCoordinator.Outcome.Failed -> "failed"
        }
        return Result.success(Data.Builder().putString(KEY_STATE, tag).build())
    }

    companion object {
        const val TAG: String = "royalroad:tag-sync"
        const val UNIQUE_NAME: String = "royalroad:tag-sync"
        const val KEY_STATE: String = "state"

        /** Issue #178 — 24h periodic cadence. The first tick fires
         *  ~24h after the worker is enqueued; first-sync-on-sign-in
         *  is handled separately by `:app` observing the
         *  AuthRepository state flip. */
        const val TAG_SYNC_INTERVAL_HOURS: Long = 24
    }
}
