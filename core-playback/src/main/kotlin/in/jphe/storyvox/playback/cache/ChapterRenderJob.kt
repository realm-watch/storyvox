package `in`.jphe.storyvox.playback.cache

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background WorkManager job that renders one chapter's PCM into the
 * cache. Triggered by [PcmRenderScheduler.scheduleRender] from
 * [PrerenderTriggers]' lifecycle hooks (library-add, chapter
 * natural-end +2, fullPrerender flow flips).
 *
 * Body lands in the next commit — this stub exists so [PcmRenderScheduler]
 * compiles against `OneTimeWorkRequestBuilder<ChapterRenderJob>()` while
 * the wire-up + binding lands together.
 *
 * PR F of the PCM cache series (#86).
 */
@HiltWorker
class ChapterRenderJob @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = Result.failure()

    companion object {
        const val KEY_FICTION_ID = "fictionId"
        const val KEY_CHAPTER_ID = "chapterId"
    }
}
