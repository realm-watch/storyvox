package `in`.jphe.storyvox.data.work

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
 * One-shot helper the app module calls from `Application.onCreate()` to ensure
 * our periodic workers are enqueued. KEEP policy means we don't double-schedule
 * on every launch; we update only when settings change (caller flips
 * [ExistingPeriodicWorkPolicy] to UPDATE).
 *
 * Selene does NOT enqueue from a `ContentProvider` or `androidx.startup.Initializer`
 * because Hilt + WorkManager has historically been unfriendly to that pattern.
 */
@Singleton
class WorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    fun ensurePeriodicWorkScheduled(
        pollIntervalHours: Long = 6,
        pollRequiresUnmetered: Boolean = true,
        sessionRefreshIntervalHours: Long = 24,
    ) {
        scheduleNewChapterPoll(pollIntervalHours, pollRequiresUnmetered)
        scheduleSessionRefresh(sessionRefreshIntervalHours)
    }

    fun scheduleNewChapterPoll(intervalHours: Long, requiresUnmetered: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (requiresUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<NewChapterPollWorker>(Duration.ofHours(intervalHours))
            .setConstraints(constraints)
            .addTag(NewChapterPollWorker.TAG)
            .build()

        workManager.enqueueUniquePeriodicWork(
            NewChapterPollWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun scheduleSessionRefresh(intervalHours: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<SessionRefreshWorker>(Duration.ofHours(intervalHours))
            .setConstraints(constraints)
            .addTag(SessionRefreshWorker.TAG)
            .build()

        workManager.enqueueUniquePeriodicWork(
            SessionRefreshWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelAll() {
        workManager.cancelAllWorkByTag(NewChapterPollWorker.TAG)
        workManager.cancelAllWorkByTag(SessionRefreshWorker.TAG)
        workManager.cancelAllWorkByTag(ChapterDownloadWorker.TAG)
    }
}
