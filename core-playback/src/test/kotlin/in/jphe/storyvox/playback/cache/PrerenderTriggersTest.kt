package `in`.jphe.storyvox.playback.cache

import `in`.jphe.storyvox.data.repository.CachedBodyUsage
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import `in`.jphe.storyvox.data.repository.playback.PlaybackChapter
import `in`.jphe.storyvox.data.repository.playback.PlaybackModeConfig
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [PrerenderTriggers] schedule + cancel semantics. Uses
 * fakes for [PcmRenderScheduler], [ChapterRepository], and
 * [PlaybackModeConfig] so the tests run without WorkManager or any
 * actual engine. RobolectricTestRunner is needed because the triggers
 * call `android.util.Log.i` on the schedule paths (unmocked by default
 * in pure-JVM JUnit tests) — Robolectric provides a real shadowed
 * implementation. The other core-playback unit tests follow the same
 * pattern (see VoiceManagerTest).
 *
 * PR F of the PCM cache series (#86).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrerenderTriggersTest {

    /** Recording fake — captures every scheduleRender / cancel call. */
    private class RecordingScheduler : PcmRenderScheduler {
        val scheduled = mutableListOf<Pair<String, String>>()
        val cancelledChapters = mutableListOf<String>()
        val cancelledFiction = mutableListOf<String>()

        override fun scheduleRender(fictionId: String, chapterId: String) {
            scheduled += fictionId to chapterId
        }
        override fun cancelRender(chapterId: String) {
            cancelledChapters += chapterId
        }
        override fun cancelAllForFiction(fictionId: String) {
            cancelledFiction += fictionId
        }
    }

    private class FakeModeConfig(
        full: Boolean = false,
    ) : PlaybackModeConfig {
        override val warmupWait = flowOf(false)
        override val catchupPause = flowOf(true)
        private val fullState = MutableStateFlow(full)
        override val fullPrerender: Flow<Boolean> = fullState.asStateFlow()
        override suspend fun currentWarmupWait() = false
        override suspend fun currentCatchupPause() = true
        override suspend fun currentFullPrerender() = fullState.value
    }

    /** Minimal ChapterRepo fake — implements just enough surface for
     *  PrerenderTriggers to navigate the reading-order graph. */
    private open class FakeChapterRepo(
        private val byFiction: Map<String, List<ChapterInfo>>,
    ) : ChapterRepository {
        override fun observeChapters(fictionId: String): Flow<List<ChapterInfo>> =
            flowOf(byFiction[fictionId].orEmpty())
        override fun observeChapter(chapterId: String): Flow<ChapterContent?> =
            flowOf(null)
        override fun observeDownloadState(fictionId: String): Flow<Map<String, ChapterDownloadState>> =
            flowOf(emptyMap())
        override fun observePlayedChapterIds(fictionId: String): Flow<Set<String>> =
            flowOf(emptySet())
        override suspend fun queueChapterDownload(
            fictionId: String, chapterId: String, requireUnmetered: Boolean,
        ) = Unit
        override suspend fun queueAllMissing(fictionId: String, requireUnmetered: Boolean) = Unit
        override suspend fun markRead(chapterId: String, read: Boolean) = Unit
        override suspend fun markChapterPlayed(chapterId: String) = Unit
        override suspend fun trimDownloadedBodies(fictionId: String, keepLast: Int) = Unit
        override suspend fun getChapter(id: String): PlaybackChapter? = null
        override suspend fun getNextChapterId(currentChapterId: String): String? {
            // Walk the flat reading order across all fictions — sufficient
            // for the single-fiction test cases.
            val all = byFiction.values.flatten()
            val idx = all.indexOfFirst { it.id == currentChapterId }
            return all.getOrNull(idx + 1)?.id
        }
        override suspend fun getPreviousChapterId(currentChapterId: String): String? {
            val all = byFiction.values.flatten()
            val idx = all.indexOfFirst { it.id == currentChapterId }
            return all.getOrNull(idx - 1)?.id
        }
        override suspend fun cachedBodyUsage(): CachedBodyUsage =
            CachedBodyUsage(count = 0, bytesEstimate = 0L)
        override suspend fun setChapterBookmark(chapterId: String, charOffset: Int?) = Unit
        override suspend fun chapterBookmark(chapterId: String): Int? = null
    }

    private fun chapter(num: Int, fictionPrefix: String) = ChapterInfo(
        id = "$fictionPrefix-c$num",
        sourceChapterId = "src-$num",
        index = num,
        title = "Chapter $num",
    )

    @Test
    fun `onLibraryAdded enqueues first 3 chapters when fullPrerender off`() = runBlocking {
        val scheduler = RecordingScheduler()
        val repo = FakeChapterRepo(mapOf("f1" to (1..5).map { chapter(it, "f1") }))
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig(full = false))

        triggers.onLibraryAdded("f1")

        assertEquals(3, scheduler.scheduled.size)
        assertEquals(
            listOf("f1-c1", "f1-c2", "f1-c3"),
            scheduler.scheduled.map { it.second },
        )
        assertTrue(
            "every scheduled render carries the source fictionId",
            scheduler.scheduled.all { it.first == "f1" },
        )
    }

    @Test
    fun `onLibraryAdded enqueues all chapters when fullPrerender on`() = runBlocking {
        val scheduler = RecordingScheduler()
        val repo = FakeChapterRepo(mapOf("f1" to (1..5).map { chapter(it, "f1") }))
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig(full = true))

        triggers.onLibraryAdded("f1")

        assertEquals(5, scheduler.scheduled.size)
        assertEquals(
            listOf("f1-c1", "f1-c2", "f1-c3", "f1-c4", "f1-c5"),
            scheduler.scheduled.map { it.second },
        )
    }

    @Test
    fun `onLibraryAdded with fewer than DEFAULT chapters caps at chapters size`() = runBlocking {
        val scheduler = RecordingScheduler()
        // Fiction with only 2 chapters; default limit is 3 but we shouldn't
        // try to schedule beyond what exists.
        val repo = FakeChapterRepo(mapOf("f1" to (1..2).map { chapter(it, "f1") }))
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig(full = false))

        triggers.onLibraryAdded("f1")

        assertEquals(2, scheduler.scheduled.size)
    }

    @Test
    fun `onLibraryAdded with empty chapter list is a no-op`() = runBlocking {
        val scheduler = RecordingScheduler()
        val repo = FakeChapterRepo(emptyMap())
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig(full = false))

        triggers.onLibraryAdded("f1")

        assertTrue("nothing scheduled for empty fiction", scheduler.scheduled.isEmpty())
    }

    @Test
    fun `onLibraryRemoved cancels every render for that fiction`() {
        val scheduler = RecordingScheduler()
        val triggers = PrerenderTriggers(
            scheduler,
            FakeChapterRepo(emptyMap()),
            FakeModeConfig(),
        )

        triggers.onLibraryRemoved("f1")

        assertEquals(listOf("f1"), scheduler.cancelledFiction)
    }

    @Test
    fun `onChapterCompleted schedules N+2`() = runBlocking {
        val scheduler = RecordingScheduler()
        // Stub getChapter so the next-next lookup returns a real
        // PlaybackChapter with the right fictionId.
        val repo = object : FakeChapterRepo(
            mapOf("f1" to (1..5).map { chapter(it, "f1") }),
        ) {
            override suspend fun getChapter(id: String): PlaybackChapter? =
                if (id == "f1-c3") PlaybackChapter(
                    id = "f1-c3",
                    fictionId = "f1",
                    text = "body",
                    title = "Chapter 3",
                    bookTitle = "F1",
                    coverUrl = null,
                ) else null
        }
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig())

        // Just-completed = c1 → next = c2 → next-next = c3 → schedule c3.
        triggers.onChapterCompleted("f1-c1")

        assertEquals(1, scheduler.scheduled.size)
        assertEquals("f1" to "f1-c3", scheduler.scheduled.first())
    }

    @Test
    fun `onChapterCompleted at penultimate chapter is a no-op`() = runBlocking {
        val scheduler = RecordingScheduler()
        // f1 has 3 chapters; completing c2 → next is c3 → next-next is null.
        val repo = FakeChapterRepo(mapOf("f1" to (1..3).map { chapter(it, "f1") }))
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig())

        triggers.onChapterCompleted("f1-c2")

        assertTrue("no N+2 to schedule at end of fiction", scheduler.scheduled.isEmpty())
    }

    @Test
    fun `onChapterCompleted at last chapter is a no-op`() = runBlocking {
        val scheduler = RecordingScheduler()
        val repo = FakeChapterRepo(mapOf("f1" to (1..3).map { chapter(it, "f1") }))
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig())

        triggers.onChapterCompleted("f1-c3")

        assertTrue("no next at end of fiction", scheduler.scheduled.isEmpty())
    }

    @Test
    fun `onFullPrerenderEnabled enqueues every chapter of every fiction`() = runBlocking {
        val scheduler = RecordingScheduler()
        val repo = FakeChapterRepo(
            mapOf(
                "f1" to (1..3).map { chapter(it, "f1") },
                "f2" to (1..4).map { chapter(it, "f2") },
            ),
        )
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig())

        triggers.onFullPrerenderEnabled(listOf("f1", "f2"))

        // 3 + 4 = 7 renders across both fictions.
        assertEquals(7, scheduler.scheduled.size)
        val fictions = scheduler.scheduled.map { it.first }.toSet()
        assertEquals(setOf("f1", "f2"), fictions)
    }
}
