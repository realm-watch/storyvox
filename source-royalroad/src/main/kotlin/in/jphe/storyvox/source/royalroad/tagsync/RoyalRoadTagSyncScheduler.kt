package `in`.jphe.storyvox.source.royalroad.tagsync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules / cancels [RoyalRoadTagSyncWorker]. Lives in
 * `:source-royalroad` because the worker class itself is here —
 * the shared `:core-data`'s `WorkScheduler` already does the
 * generic scheduling, but would have to depend on
 * `:source-royalroad` to enqueue this one, so this thin helper
 * keeps the dependency direction clean (issue #178).
 *
 * Called from `:app`'s startup wiring after the shared
 * `WorkScheduler.ensurePeriodicWorkScheduled` completes, gated on
 * "user has at least once been signed in to RR." The KEEP policy
 * means we don't re-enqueue on every launch.
 */
@Singleton
class RoyalRoadTagSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /**
     * Enqueue the 24h periodic tag-sync worker. Idempotent (KEEP
     * existing schedule). The first tick fires ~24h from now;
     * for the immediate-on-sign-in case, `:app` observes the
     * AuthRepository state flip and invokes the coordinator
     * directly without going through WorkManager.
     */
    fun schedule() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<RoyalRoadTagSyncWorker>(
            Duration.ofHours(RoyalRoadTagSyncWorker.TAG_SYNC_INTERVAL_HOURS)
        )
            .setConstraints(constraints)
            .addTag(RoyalRoadTagSyncWorker.TAG)
            .build()

        workManager.enqueueUniquePeriodicWork(
            RoyalRoadTagSyncWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Cancel the periodic worker. Called when the user signs out
     *  of RR or disables tag-sync in Settings. */
    fun cancel() {
        workManager.cancelUniqueWork(RoyalRoadTagSyncWorker.UNIQUE_NAME)
    }
}
