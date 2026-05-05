package `in`.jphe.storyvox.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import `in`.jphe.storyvox.data.db.entity.DownloadMode
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.repository.FictionRepository
import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.flow.first

/**
 * Periodic worker that polls each subscribed fiction for new chapters.
 *
 * Walks every `Fiction WHERE inLibrary = 1 AND downloadMode IN (SUBSCRIBE, EAGER)`,
 * fetches the detail page, diffs against cached chapters, inserts new rows
 * (state = NOT_DOWNLOADED), then auto-enqueues `ChapterDownloadWorker` for new
 * chapters when the fiction is in EAGER mode.
 *
 * Notification rendering (collapsible group, deep links) is the app module's
 * responsibility — this worker only sets the data and returns the count.
 */
@HiltWorker
class NewChapterPollWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val fictionDao: FictionDao,
    private val chapterDao: ChapterDao,
    private val fictionRepository: FictionRepository,
    private val chapterRepository: ChapterRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        var newChapters = 0

        // Pull a one-shot snapshot of the library — Room's Flow emits the
        // current set immediately on subscribe.
        val rows = fictionDao.observeLibrary().first()

        for (fiction in rows) {
            val mode = fiction.downloadMode ?: continue
            if (mode != DownloadMode.SUBSCRIBE && mode != DownloadMode.EAGER) continue

            when (val result = fictionRepository.refreshDetail(fiction.id)) {
                is FictionResult.Success -> {
                    val missing = chapterDao.missingForFiction(fiction.id)
                    if (missing.isEmpty()) continue
                    newChapters += missing.size
                    if (mode == DownloadMode.EAGER) {
                        for (m in missing) {
                            chapterRepository.queueChapterDownload(
                                fictionId = fiction.id,
                                chapterId = m.id,
                                requireUnmetered = true,
                            )
                        }
                    } else {
                        // SUBSCRIBE mode: leave them as NOT_DOWNLOADED; user
                        // sees them in their library and can tap to play.
                        for (m in missing) {
                            chapterDao.setDownloadState(
                                id = m.id,
                                state = ChapterDownloadState.NOT_DOWNLOADED,
                                now = System.currentTimeMillis(),
                                error = null,
                            )
                        }
                    }
                }
                is FictionResult.Failure -> {
                    // Don't fail the whole poll because one fiction is rate-limited.
                    // Continue to the next.
                }
            }
        }

        return Result.success(
            Data.Builder().putInt(KEY_NEW_CHAPTERS, newChapters).build(),
        )
    }

    companion object {
        const val TAG = "poll:new-chapters"
        const val UNIQUE_NAME = "poll:new-chapters"
        const val KEY_NEW_CHAPTERS = "newChapterCount"
    }
}
