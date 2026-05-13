package `in`.jphe.storyvox.data.repository

import `in`.jphe.storyvox.data.db.dao.ChapterHistoryDao
import `in`.jphe.storyvox.data.db.dao.ChapterHistoryRow
import `in`.jphe.storyvox.data.db.entity.ChapterHistory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #158 — repository-level coverage for the reading-history surface.
 *
 * The DAO is faked (same pattern as [ChapterRepositoryImplTest]) so these
 * tests run on plain JVM without Robolectric or an emulator. The fake
 * mirrors the production SQL invariants we actually depend on:
 *   - upsert by composite (fictionId, chapterId)
 *   - ORDER BY openedAt DESC on observeAll / observeForFiction
 *   - markCompleted as an atomic UPDATE that preserves prior state
 *     when called against a row that doesn't exist yet (no-op).
 *
 * The Room-generated SQL is exercised by the schema-export step on
 * every build (KSP would fail KSP if the query was malformed); the
 * tests here cover repository semantics (read-then-upsert preserving
 * `completed`, the timestamp behaviour) that schema export alone
 * doesn't catch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryRepositoryImplTest {

    private class FakeChapterHistoryDao : ChapterHistoryDao {
        val rows = mutableMapOf<Pair<String, String>, ChapterHistory>()
        val callLog = mutableListOf<String>()
        private val allFeed = MutableStateFlow<List<ChapterHistoryRow>>(emptyList())
        private val perFictionFeeds = mutableMapOf<String, MutableStateFlow<List<ChapterHistoryRow>>>()

        // Optional join metadata — tests can pre-populate to verify the
        // denormalized projection without spinning up a real fiction +
        // chapter table.
        data class Meta(
            val fictionTitle: String? = null,
            val fictionAuthor: String? = null,
            val coverUrl: String? = null,
            val chapterTitle: String? = null,
            val chapterIndex: Int? = null,
        )
        val meta = mutableMapOf<Pair<String, String>, Meta>()

        private fun rebuild(): List<ChapterHistoryRow> =
            rows.values
                .sortedByDescending { it.openedAt }
                .map { h ->
                    val m = meta[h.fictionId to h.chapterId] ?: Meta()
                    ChapterHistoryRow(
                        fictionId = h.fictionId,
                        chapterId = h.chapterId,
                        openedAt = h.openedAt,
                        completed = h.completed,
                        fractionRead = h.fractionRead,
                        fictionTitle = m.fictionTitle,
                        fictionAuthor = m.fictionAuthor,
                        coverUrl = m.coverUrl,
                        chapterTitle = m.chapterTitle,
                        chapterIndex = m.chapterIndex,
                    )
                }

        private fun publish() {
            val full = rebuild()
            allFeed.value = full
            perFictionFeeds.forEach { (fictionId, f) ->
                f.value = full.filter { it.fictionId == fictionId }
            }
        }

        override fun observeAll(): Flow<List<ChapterHistoryRow>> {
            allFeed.value = rebuild()
            return allFeed
        }

        override fun observeForFiction(fictionId: String): Flow<List<ChapterHistoryRow>> {
            val feed = perFictionFeeds.getOrPut(fictionId) {
                MutableStateFlow(rebuild().filter { it.fictionId == fictionId })
            }
            return feed
        }

        override suspend fun get(fictionId: String, chapterId: String): ChapterHistory? {
            callLog += "get($fictionId, $chapterId)"
            return rows[fictionId to chapterId]
        }

        override suspend fun upsert(row: ChapterHistory) {
            callLog += "upsert(${row.fictionId}, ${row.chapterId}, openedAt=${row.openedAt}, completed=${row.completed})"
            rows[row.fictionId to row.chapterId] = row
            publish()
        }

        override suspend fun markCompleted(
            fictionId: String,
            chapterId: String,
            fraction: Float?,
            now: Long,
        ) {
            callLog += "markCompleted($fictionId, $chapterId, fraction=$fraction)"
            val existing = rows[fictionId to chapterId] ?: return // no-op
            rows[fictionId to chapterId] = existing.copy(
                completed = true,
                fractionRead = fraction ?: existing.fractionRead,
                openedAt = now,
            )
            publish()
        }
    }

    @Test fun `logOpen creates a fresh history row`() = runTest {
        val dao = FakeChapterHistoryDao()
        val repo = HistoryRepositoryImpl(dao)

        repo.logOpen(fictionId = "f1", chapterId = "f1:0")

        val entries = repo.observeAll().first()
        assertEquals(1, entries.size)
        val entry = entries.first()
        assertEquals("f1", entry.fictionId)
        assertEquals("f1:0", entry.chapterId)
        assertEquals(false, entry.completed)
        assertNull(entry.fractionRead)
    }

    @Test fun `re-opening the same chapter upserts in place, not append`() = runTest {
        val dao = FakeChapterHistoryDao()
        val repo = HistoryRepositoryImpl(dao)

        // Three opens of the same (fiction, chapter). One row at the end.
        repo.logOpen("f1", "f1:0")
        repo.logOpen("f1", "f1:0")
        repo.logOpen("f1", "f1:0")

        val entries = repo.observeAll().first()
        assertEquals("re-open should NOT append duplicate rows", 1, entries.size)
        assertEquals("f1:0", entries.first().chapterId)
    }

    @Test fun `logOpen after markCompleted preserves completed flag`() = runTest {
        val dao = FakeChapterHistoryDao()
        val repo = HistoryRepositoryImpl(dao)

        repo.logOpen("f1", "f1:0")
        repo.markCompleted("f1", "f1:0", fraction = 1f)
        // User re-opens the same finished chapter to skim again.
        repo.logOpen("f1", "f1:0")

        val entry = repo.observeAll().first().single()
        assertEquals("completed must survive a subsequent re-open", true, entry.completed)
        assertEquals("fractionRead must survive a subsequent re-open", 1f, entry.fractionRead)
    }

    @Test fun `observeAll orders most-recent open first across fictions`() = runTest {
        val dao = FakeChapterHistoryDao()
        val repo = HistoryRepositoryImpl(dao)

        // Three opens in known order. Manually upsert at known timestamps
        // because logOpen uses System.currentTimeMillis which would race.
        dao.upsert(ChapterHistory("f1", "f1:0", openedAt = 1_000L))
        dao.upsert(ChapterHistory("f2", "f2:0", openedAt = 3_000L))
        dao.upsert(ChapterHistory("f1", "f1:1", openedAt = 2_000L))

        val entries = repo.observeAll().first()
        assertEquals(listOf("f2:0", "f1:1", "f1:0"), entries.map { it.chapterId })
    }

    @Test fun `observeForFiction filters to one fiction's rows only`() = runTest {
        val dao = FakeChapterHistoryDao()
        val repo = HistoryRepositoryImpl(dao)

        dao.upsert(ChapterHistory("f1", "f1:0", openedAt = 1_000L))
        dao.upsert(ChapterHistory("f2", "f2:0", openedAt = 2_000L))
        dao.upsert(ChapterHistory("f1", "f1:1", openedAt = 3_000L))

        val f1 = repo.observeForFiction("f1").first()
        assertEquals(listOf("f1:1", "f1:0"), f1.map { it.chapterId })
        val f2 = repo.observeForFiction("f2").first()
        assertEquals(listOf("f2:0"), f2.map { it.chapterId })
    }

    @Test fun `markCompleted is a no-op when no history row exists yet`() = runTest {
        val dao = FakeChapterHistoryDao()
        val repo = HistoryRepositoryImpl(dao)

        // Never logged the open. End-of-chapter event fires anyway (unreachable
        // in normal use, but the contract is idempotent).
        repo.markCompleted("f1", "ghost", fraction = 1f)

        val entries = repo.observeAll().first()
        assertTrue("markCompleted on missing row must not synthesize a phantom history", entries.isEmpty())
    }

    @Test fun `markCompleted with null fraction preserves prior fractionRead`() = runTest {
        val dao = FakeChapterHistoryDao()
        val repo = HistoryRepositoryImpl(dao)

        // Seed with a non-null fraction, then mark completed with null —
        // the dao SQL uses COALESCE so the prior value should survive.
        dao.upsert(
            ChapterHistory(
                fictionId = "f1",
                chapterId = "f1:0",
                openedAt = 1_000L,
                fractionRead = 0.42f,
            ),
        )
        repo.markCompleted("f1", "f1:0", fraction = null)

        val entry = repo.observeAll().first().single()
        assertEquals(true, entry.completed)
        assertEquals("null fraction must preserve previously-known progress", 0.42f, entry.fractionRead)
    }

    @Test fun `projection passes through denormalized join columns`() = runTest {
        val dao = FakeChapterHistoryDao()
        val repo = HistoryRepositoryImpl(dao)
        dao.meta["f1" to "f1:0"] = FakeChapterHistoryDao.Meta(
            fictionTitle = "Mother of Learning",
            fictionAuthor = "nobody103",
            coverUrl = "https://example/cover.jpg",
            chapterTitle = "Good Morning Brother",
            chapterIndex = 1,
        )
        dao.upsert(ChapterHistory("f1", "f1:0", openedAt = 1_000L))

        val entry = repo.observeAll().first().single()
        assertEquals("Mother of Learning", entry.fictionTitle)
        assertEquals("nobody103", entry.fictionAuthor)
        assertEquals("https://example/cover.jpg", entry.coverUrl)
        assertEquals("Good Morning Brother", entry.chapterTitle)
        assertEquals(1, entry.chapterIndex)
        assertNotNull("openedAt round-trips", entry.openedAt)
    }
}
