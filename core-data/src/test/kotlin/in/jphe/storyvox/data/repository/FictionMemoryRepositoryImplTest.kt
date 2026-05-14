package `in`.jphe.storyvox.data.repository

import `in`.jphe.storyvox.data.db.dao.FictionMemoryDao
import `in`.jphe.storyvox.data.db.entity.FictionMemoryEntry
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
 * Issue #217 — repository-level coverage for cross-fiction memory.
 *
 * The DAO is faked to keep these tests on plain JVM. The fake mirrors
 * the production SQL invariants the repository depends on:
 *   - upsert on composite (fictionId, name) PK
 *   - findByName respects excludeFictionId filter
 *   - delete is keyed by (fictionId, name)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FictionMemoryRepositoryImplTest {

    private class FakeDao : FictionMemoryDao {
        val rows: MutableMap<Pair<String, String>, FictionMemoryEntry> = mutableMapOf()
        private val perFictionFlows: MutableMap<String, MutableStateFlow<List<FictionMemoryEntry>>> =
            mutableMapOf()

        private fun rebuild(fictionId: String) {
            val current = rows.values.filter { it.fictionId == fictionId }
            perFictionFlows[fictionId]?.value = current
        }

        override fun observeForFiction(fictionId: String): Flow<List<FictionMemoryEntry>> =
            perFictionFlows.getOrPut(fictionId) {
                MutableStateFlow(rows.values.filter { it.fictionId == fictionId })
            }

        override suspend fun findByName(
            name: String,
            excludeFictionId: String?,
        ): List<FictionMemoryEntry> = rows.values
            .filter { it.name == name && it.fictionId != excludeFictionId }
            .sortedByDescending { it.lastUpdated }

        override suspend fun allEntries(excludeFictionId: String?): List<FictionMemoryEntry> =
            rows.values.filter { it.fictionId != excludeFictionId }
                .sortedByDescending { it.lastUpdated }

        override suspend fun forFiction(fictionId: String): List<FictionMemoryEntry> =
            rows.values.filter { it.fictionId == fictionId }

        override suspend fun upsert(entry: FictionMemoryEntry) {
            rows[entry.fictionId to entry.name] = entry
            rebuild(entry.fictionId)
        }

        override suspend fun delete(fictionId: String, name: String) {
            rows.remove(fictionId to name)
            rebuild(fictionId)
        }

        override suspend fun deleteAllForFiction(fictionId: String) {
            rows.entries.removeAll { it.value.fictionId == fictionId }
            rebuild(fictionId)
        }
    }

    @Test fun `entity upsert inserts on first call`() = runTest {
        val dao = FakeDao()
        val repo = FictionMemoryRepositoryImpl(dao)
        repo.recordEntity(
            fictionId = "book-a",
            entityType = FictionMemoryEntry.Kind.CHARACTER,
            name = "Kelsier",
            summary = "Mistborn, leader of the crew",
        )
        val rows = dao.rows.values.toList()
        assertEquals(1, rows.size)
        assertEquals("Kelsier", rows.first().name)
        assertEquals("book-a", rows.first().fictionId)
        assertEquals(FictionMemoryEntry.Kind.CHARACTER.name, rows.first().entityType)
        assertTrue(rows.first().summary.startsWith("Mistborn"))
    }

    @Test fun `entity upsert updates existing row on second call with same key`() = runTest {
        val dao = FakeDao()
        val repo = FictionMemoryRepositoryImpl(dao)
        repo.recordEntity(
            fictionId = "book-a",
            entityType = FictionMemoryEntry.Kind.CHARACTER,
            name = "Vin",
            summary = "first version",
        )
        repo.recordEntity(
            fictionId = "book-a",
            entityType = FictionMemoryEntry.Kind.CHARACTER,
            name = "Vin",
            summary = "Mistborn who works with the crew",
        )
        // Composite key collapses both writes into one row.
        assertEquals(1, dao.rows.size)
        val row = dao.rows.values.first()
        assertEquals("Mistborn who works with the crew", row.summary)
    }

    @Test fun `cross-fiction lookup returns matches from multiple books`() = runTest {
        val dao = FakeDao()
        val repo = FictionMemoryRepositoryImpl(dao)
        repo.recordEntity("book-a", FictionMemoryEntry.Kind.CHARACTER, "Sarah", "From book A")
        repo.recordEntity("book-b", FictionMemoryEntry.Kind.CHARACTER, "Sarah", "From book B")
        repo.recordEntity("book-c", FictionMemoryEntry.Kind.CHARACTER, "Other", "Not Sarah")

        // No exclusion — both Sarahs surface.
        val all = repo.findEntityAcrossFictions("Sarah", excludeFictionId = null)
        assertEquals(2, all.size)
        assertTrue(all.any { it.fictionId == "book-a" })
        assertTrue(all.any { it.fictionId == "book-b" })

        // Exclude book-a — only book-b's Sarah surfaces.
        val excluded = repo.findEntityAcrossFictions("Sarah", excludeFictionId = "book-a")
        assertEquals(1, excluded.size)
        assertEquals("book-b", excluded.first().fictionId)
    }

    @Test fun `userEdited entries are preserved against AI overwrite`() = runTest {
        val dao = FakeDao()
        val repo = FictionMemoryRepositoryImpl(dao)
        repo.recordEntity(
            fictionId = "book-a",
            entityType = FictionMemoryEntry.Kind.CHARACTER,
            name = "Vin",
            summary = "Hand-curated summary",
            userEdited = true,
        )
        // AI-source attempt to overwrite the same (fictionId, name).
        repo.recordEntity(
            fictionId = "book-a",
            entityType = FictionMemoryEntry.Kind.CHARACTER,
            name = "Vin",
            summary = "AI-extracted summary that should not win",
            userEdited = false,
        )
        val row = dao.rows[("book-a" to "Vin")]
        assertNotNull(row)
        assertEquals("Hand-curated summary", row!!.summary)
        assertTrue(row.userEdited)
    }

    @Test fun `summary is clipped to the max-chars cap`() = runTest {
        val dao = FakeDao()
        val repo = FictionMemoryRepositoryImpl(dao)
        val giantSummary = "x".repeat(1000)
        repo.recordEntity(
            fictionId = "book-a",
            entityType = FictionMemoryEntry.Kind.CHARACTER,
            name = "Kelsier",
            summary = giantSummary,
        )
        val row = dao.rows.values.first()
        assertEquals(
            "summary must be clipped to the cap",
            FictionMemoryRepository.SUMMARY_MAX_CHARS, row.summary.length,
        )
    }

    @Test fun `delete removes a single entry by composite key`() = runTest {
        val dao = FakeDao()
        val repo = FictionMemoryRepositoryImpl(dao)
        repo.recordEntity("book-a", FictionMemoryEntry.Kind.CHARACTER, "Vin", "summary")
        repo.recordEntity("book-a", FictionMemoryEntry.Kind.CHARACTER, "Kelsier", "summary")
        assertEquals(2, dao.rows.size)
        repo.deleteEntry("book-a", "Vin")
        assertEquals(1, dao.rows.size)
        assertNull(dao.rows[("book-a" to "Vin")])
        assertNotNull(dao.rows[("book-a" to "Kelsier")])
    }

    @Test fun `entitiesForFiction observes the per-fiction feed`() = runTest {
        val dao = FakeDao()
        val repo = FictionMemoryRepositoryImpl(dao)
        repo.recordEntity("book-a", FictionMemoryEntry.Kind.CHARACTER, "Vin", "summary")
        repo.recordEntity("book-b", FictionMemoryEntry.Kind.CHARACTER, "Other", "summary")

        val rows = repo.entitiesForFiction("book-a").first()
        assertEquals(1, rows.size)
        assertEquals("Vin", rows.first().name)
    }
}
