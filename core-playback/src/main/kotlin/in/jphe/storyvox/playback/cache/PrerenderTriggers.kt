package `in`.jphe.storyvox.playback.cache

import android.util.Log
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.repository.FictionLibraryListener
import `in`.jphe.storyvox.data.repository.playback.PlaybackModeConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Trigger glue for the PCM cache pre-render scheduler. Sits between
 * trigger sources (FictionRepository, EnginePlayer, the `:app`-level
 * fullPrerender flow collector) and the scheduler so none of those
 * callers needs to know about WorkManager.
 *
 * Trigger semantics per spec (see
 * `docs/superpowers/specs/2026-05-07-pcm-cache-design.md` § "Trigger
 * points"):
 *
 *  - **Library add** ([onLibraryAdded]) — schedule renders for the
 *    first [DEFAULT_PRERENDER_CHAPTERS] chapters in reading order, OR
 *    all chapters if Mode C ([PlaybackModeConfig.fullPrerender]) is
 *    on.
 *  - **Chapter complete** ([onChapterCompleted]) — schedule a render
 *    of chapter N+2 in reading order, where N is the just-completed
 *    chapter. N+1 is usually already cached or in flight from the
 *    previous chapter-completed trigger or from PR-D's foreground
 *    tee while the user is actively listening.
 *  - **Fiction removed** ([onLibraryRemoved]) — cancel all scheduled
 *    renders for that fiction (via the `pcm-render-fiction-<id>` tag).
 *  - **Mode C flow flip** false → true ([onFullPrerenderEnabled]) —
 *    re-evaluate every library fiction and enqueue any not-yet-cached
 *    chapters. Called from `:app`'s flow collector (PrerenderModeWatcher)
 *    so this class doesn't need to take a FictionRepository dependency.
 *
 * Implements [FictionLibraryListener] (in :core-data) — :app's
 * CacheBindingsModule binds this class as the listener so
 * FictionRepository's addToLibrary / removeFromLibrary can dispatch
 * without depending on :core-playback directly.
 *
 * NOT in this PR: cancel a render when the user is now actively
 * playing the same (chapter, voice) key via the streaming source.
 * The streaming source's tee writes to the SAME cache key; PR-D's
 * appender's resume detection (meta exists, idx absent) means the
 * foreground tee will collide with a worker that finalized first.
 * Spec calls this "mutual exclusion contract"; PR-F's first cut
 * handles it via the engineMutex (worker can't generate while
 * foreground holds the mutex; serialization is at sentence
 * granularity, effectively the worker pauses between sentences while
 * foreground is active). A future cleanup adds explicit
 * [PcmRenderScheduler.cancelRender] calls from
 * EnginePlayer.startPlaybackPipeline for the matching key.
 *
 * PR F of the PCM cache series (#86).
 */
@Singleton
class PrerenderTriggers @Inject constructor(
    private val scheduler: PcmRenderScheduler,
    private val chapterRepo: ChapterRepository,
    private val modeConfig: PlaybackModeConfig,
) : FictionLibraryListener {

    /**
     * Library-add: schedule chapters 1-N (per [DEFAULT_PRERENDER_CHAPTERS]
     * or full-prerender). Order matches reading order from
     * [ChapterRepository.observeChapters] — first emission is the
     * current snapshot, sorted by chapter index.
     */
    override suspend fun onLibraryAdded(fictionId: String) {
        val chapters = chapterRepo.observeChapters(fictionId).first()
            .sortedBy { it.index }
        if (chapters.isEmpty()) {
            Log.i(
                LOG_TAG,
                "pcm-cache TRIGGER-LIBRARY-ADD fictionId=$fictionId chapters=0 (skipping)",
            )
            return
        }
        val limit = if (modeConfig.currentFullPrerender()) {
            chapters.size
        } else {
            DEFAULT_PRERENDER_CHAPTERS
        }
        val targets = chapters.take(limit)
        Log.i(
            LOG_TAG,
            "pcm-cache TRIGGER-LIBRARY-ADD fictionId=$fictionId " +
                "scheduling=${targets.size} fullPrerender=${limit == chapters.size}",
        )
        for (chapter in targets) {
            scheduler.scheduleRender(fictionId = fictionId, chapterId = chapter.id)
        }
    }

    /**
     * Chapter natural-end: schedule N+2. N+1 should already be cached
     * (it was scheduled when N started OR is the next-up that the user
     * is about to play and PR-D's tee will populate). N+2 keeps the
     * pre-render window one chapter ahead of playback so the user
     * never hits a streaming cold-start.
     *
     * Resolves the next-next chapter's fictionId via the chapter row —
     * the trigger doesn't carry it and we don't want to assume.
     */
    suspend fun onChapterCompleted(currentChapterId: String) {
        val nextId = chapterRepo.getNextChapterId(currentChapterId)
        if (nextId == null) {
            Log.i(
                LOG_TAG,
                "pcm-cache TRIGGER-CHAPTER-DONE chapterId=$currentChapterId " +
                    "no-next (end of fiction)",
            )
            return
        }
        val nextNextId = chapterRepo.getNextChapterId(nextId) ?: return
        val nextNextChapter = chapterRepo.getChapter(nextNextId) ?: return
        Log.i(
            LOG_TAG,
            "pcm-cache TRIGGER-CHAPTER-DONE chapterId=$currentChapterId " +
                "scheduling next-next=$nextNextId",
        )
        scheduler.scheduleRender(
            fictionId = nextNextChapter.fictionId,
            chapterId = nextNextId,
        )
    }

    override fun onLibraryRemoved(fictionId: String) {
        Log.i(LOG_TAG, "pcm-cache TRIGGER-LIBRARY-REMOVE fictionId=$fictionId")
        scheduler.cancelAllForFiction(fictionId)
    }

    /**
     * Mode C flow flip false → true. Re-evaluates every library
     * fiction and enqueues any not-yet-cached chapters. Called by
     * `:app`'s PrerenderModeWatcher flow collector (which knows
     * about FictionRepository — this class deliberately doesn't, to
     * keep `:core-playback` from depending on the library repository).
     *
     * The KEEP existing-work policy on the scheduler means re-scheduling
     * an already-queued chapter is a no-op; we don't need to filter
     * here, the scheduler dedupes.
     */
    suspend fun onFullPrerenderEnabled(fictionIds: Iterable<String>) {
        for (fictionId in fictionIds) {
            val chapters = chapterRepo.observeChapters(fictionId).first()
                .sortedBy { it.index }
            Log.i(
                LOG_TAG,
                "pcm-cache TRIGGER-FULL-PRERENDER fictionId=$fictionId " +
                    "scheduling=${chapters.size}",
            )
            for (chapter in chapters) {
                scheduler.scheduleRender(fictionId, chapter.id)
            }
        }
    }

    companion object {
        private const val LOG_TAG = "PrerenderTriggers"

        /** Per spec: library-add pre-renders the first three chapters in
         *  reading order. Mode C expands to "all chapters". */
        const val DEFAULT_PRERENDER_CHAPTERS = 3
    }
}
