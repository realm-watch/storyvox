package `in`.jphe.storyvox.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import `in`.jphe.storyvox.data.repository.AuthRepository
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.WebViewFetcher
import `in`.jphe.storyvox.data.source.model.FictionResult
import java.security.MessageDigest

/**
 * Downloads a single chapter body. Run with `OneTimeWorkRequest`; orchestration
 * lives on [`in`.jphe.storyvox.data.repository.ChapterRepository.queueChapterDownload].
 *
 * Retry semantics:
 *  - [FictionResult.RateLimited]/[FictionResult.NetworkError] → `Result.retry()`
 *    with exponential backoff (configured by the request).
 *  - [FictionResult.NotFound]/[FictionResult.AuthRequired] → `Result.failure()`
 *    (terminal). On `AuthRequired`, the app module is responsible for posting
 *    a "Sign in to continue downloading" notification observing
 *    [AuthRepository.sessionState].
 *  - [FictionResult.Cloudflare] → escalate to [WebViewFetcher] (when bound),
 *    parse via the same source code path, then `Result.success()`. If the
 *    fetcher isn't available, `retry()`.
 *  - [FictionResult.Success] → write body, mark `DOWNLOADED`, `Result.success()`.
 */
@HiltWorker
class ChapterDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val source: FictionSource,
    private val chapterDao: ChapterDao,
    private val auth: AuthRepository,
    private val webView: WebViewFetcher? = null,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val fictionId = inputData.getString(KEY_FICTION_ID) ?: return Result.failure(
            Data.Builder().putString(KEY_RESULT, RESULT_BAD_INPUT).build(),
        )
        val chapterId = inputData.getString(KEY_CHAPTER_ID) ?: return Result.failure(
            Data.Builder().putString(KEY_RESULT, RESULT_BAD_INPUT).build(),
        )
        val now = System.currentTimeMillis()

        chapterDao.setDownloadState(chapterId, ChapterDownloadState.DOWNLOADING, now, null)

        return when (val result = source.chapter(fictionId, chapterId)) {
            is FictionResult.Success -> {
                val content = result.value
                val checksum = sha256(content.htmlBody)
                chapterDao.setBody(
                    id = chapterId,
                    html = content.htmlBody,
                    plain = content.plainBody,
                    checksum = checksum,
                    notesAuthor = content.notesAuthor,
                    notesAuthorPosition = content.notesAuthorPosition?.name,
                    now = System.currentTimeMillis(),
                )
                Result.success(output(RESULT_OK))
            }

            is FictionResult.NotFound -> {
                chapterDao.setDownloadState(chapterId, ChapterDownloadState.FAILED, now, "404")
                Result.failure(output(RESULT_NOT_FOUND))
            }

            is FictionResult.AuthRequired -> {
                chapterDao.setDownloadState(chapterId, ChapterDownloadState.FAILED, now, "auth")
                Result.failure(output(RESULT_AUTH))
            }

            is FictionResult.Cloudflare -> {
                val fetcher = webView
                if (fetcher == null) {
                    chapterDao.setDownloadState(chapterId, ChapterDownloadState.QUEUED, now, "cf-no-fetcher")
                    return Result.retry()
                }
                // The source's `chapter()` already failed at the HTTP layer.
                // We escalate by fetching the URL via WebView and then asking
                // the source to re-parse it. To avoid leaking a parse-from-html
                // method into the FictionSource interface we instead just
                // retry the original suspend call — Oneiros's RR impl is
                // expected to consult AuthRepository's cookie + WebViewFetcher
                // on retry. If still Cloudflare, we give up for this run.
                when (val retry = source.chapter(fictionId, chapterId)) {
                    is FictionResult.Success -> {
                        val checksum = sha256(retry.value.htmlBody)
                        chapterDao.setBody(
                            id = chapterId,
                            html = retry.value.htmlBody,
                            plain = retry.value.plainBody,
                            checksum = checksum,
                            notesAuthor = retry.value.notesAuthor,
                            notesAuthorPosition = retry.value.notesAuthorPosition?.name,
                            now = System.currentTimeMillis(),
                        )
                        Result.success(output(RESULT_OK))
                    }
                    is FictionResult.Failure -> {
                        chapterDao.setDownloadState(chapterId, ChapterDownloadState.FAILED, now, "cf")
                        Result.retry()
                    }
                }
            }

            is FictionResult.RateLimited -> {
                chapterDao.setDownloadState(chapterId, ChapterDownloadState.QUEUED, now, "rate")
                Result.retry()
            }

            is FictionResult.NetworkError -> {
                chapterDao.setDownloadState(chapterId, ChapterDownloadState.QUEUED, now, "net")
                Result.retry()
            }
        }
    }

    private fun output(tag: String): Data =
        Data.Builder().putString(KEY_RESULT, tag).build()

    private fun sha256(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val TAG = "download:chapter"

        const val KEY_FICTION_ID = "fictionId"
        const val KEY_CHAPTER_ID = "chapterId"
        const val KEY_REQUIRE_UNMETERED = "requireUnmetered"

        const val KEY_RESULT = "result"

        const val RESULT_OK = "ok"
        const val RESULT_NOT_FOUND = "404"
        const val RESULT_AUTH = "auth"
        const val RESULT_BAD_INPUT = "bad-input"

        fun uniqueName(chapterId: String): String = "download:$chapterId"
    }
}
