package `in`.jphe.storyvox.data.work

import android.content.Context
import android.util.Log
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
import `in`.jphe.storyvox.data.source.FictionSource
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
 * Cheap-poll path: before the heavier `refreshDetail` call, ask the source
 * for a revision token (e.g. head commit SHA on GitHub). When the source
 * returns the same token we stored on the previous successful poll, skip
 * the full fetch entirely. Sources without a cheap revision check return
 * null (the default impl) and the worker falls back to the full path.
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
    private val sources: Map<String, @JvmSuppressWildcards FictionSource>,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        var newChapters = 0
        var skippedByRevisionCheck = 0

        // Pull a one-shot snapshot of the library — Room's Flow emits the
        // current set immediately on subscribe.
        val rows = fictionDao.observeLibrary().first()

        for (fiction in rows) {
            val mode = fiction.downloadMode ?: continue
            if (mode != DownloadMode.SUBSCRIBE && mode != DownloadMode.EAGER) continue

            // Cheap-poll: ask the source for a revision token. If we have
            // a stored token AND it matches, the upstream hasn't changed
            // since last poll — skip the full detail fetch.
            val source = sources[fiction.sourceId]
            if (source != null && fiction.lastSeenRevision != null) {
                val tokenResult = runCatching { source.latestRevisionToken(fiction.id) }
                    .getOrElse { e ->
                        Log.w(TAG, "latestRevisionToken threw for ${fiction.id}", e)
                        null
                    }
                if (tokenResult is FictionResult.Success && tokenResult.value != null &&
                    tokenResult.value == fiction.lastSeenRevision
                ) {
                    skippedByRevisionCheck++
                    continue
                }
                // Otherwise fall through: token mismatch, source returned
                // null (no cheap check available), or the call failed.
                // The full refreshDetail path is the safe default.
            }

            when (val result = fictionRepository.refreshDetail(fiction.id)) {
                is FictionResult.Success -> {
                    // After a successful refresh, persist whatever new
                    // revision token the source has now. We re-ask
                    // because `refreshDetail` doesn't return the token
                    // through its Unit-shaped result; an extra call is
                    // cheap (1-2 GitHub calls) and means subsequent
                    // polls can short-circuit.
                    if (source != null) {
                        runCatching { source.latestRevisionToken(fiction.id) }
                            .onSuccess { r ->
                                if (r is FictionResult.Success && r.value != null) {
                                    fictionDao.setLastSeenRevision(fiction.id, r.value)
                                }
                            }
                            .onFailure { e ->
                                Log.w(TAG, "latestRevisionToken (post-refresh) threw for ${fiction.id}", e)
                            }
                    }

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
            Data.Builder()
                .putInt(KEY_NEW_CHAPTERS, newChapters)
                .putInt(KEY_SKIPPED_BY_REVISION, skippedByRevisionCheck)
                .build(),
        )
    }

    companion object {
        const val TAG = "poll:new-chapters"
        const val UNIQUE_NAME = "poll:new-chapters"
        const val KEY_NEW_CHAPTERS = "newChapterCount"

        /**
         * Telemetry-friendly count of fictions whose poll was skipped
         * because the source's revision token matched the stored one.
         * Surfaced in the Result data so future observability can graph
         * "% of polls that hit the cheap path".
         */
        const val KEY_SKIPPED_BY_REVISION = "skippedByRevision"
    }
}
