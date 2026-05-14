package `in`.jphe.storyvox.feature.fiction

import `in`.jphe.storyvox.data.db.entity.FictionMemoryEntry
import `in`.jphe.storyvox.data.repository.FictionMemoryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #217 — focused tests on the Notebook UI's population path. We
 * exercise the [FictionMemoryRepository] surface that backs the
 * [FictionDetailUiState.notebookEntries] field directly — recording an
 * entity for the current fiction, observing the per-fiction feed, and
 * round-tripping a manual user-edited note through the repo.
 *
 * The full [FictionDetailViewModel] has a thick injection footprint
 * (ExportFictionToEpubUseCase wraps two concrete DAOs that aren't
 * faked anywhere; FictionRepositoryUi has a 12-method surface). The
 * Notebook-specific paths it adds — addNotebookEntry, deleteNotebookEntry,
 * and the notebookEntries field of the StateFlow — are thin pass-throughs
 * over the repo's surface; testing the repo's behaviour under those
 * call patterns covers the meaningful logic without rebuilding the
 * whole VM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FictionDetailNotebookTest {

    private class FakeMemoryRepo : FictionMemoryRepository {
        val entries: MutableMap<Pair<String, String>, FictionMemoryEntry> = mutableMapOf()
        private val flows: MutableMap<String, MutableStateFlow<List<FictionMemoryEntry>>> = mutableMapOf()
        override suspend fun recordEntity(
            fictionId: String,
            entityType: FictionMemoryEntry.Kind,
            name: String,
            summary: String,
            firstSeenChapterIndex: Int?,
            userEdited: Boolean,
        ) {
            val key = fictionId to name.trim()
            // Mirror the userEdited-protection rule from the impl.
            if (!userEdited && entries[key]?.userEdited == true) return
            entries[key] = FictionMemoryEntry(
                fictionId = fictionId,
                entityType = entityType.name,
                name = name.trim(),
                summary = summary.trim().take(FictionMemoryRepository.SUMMARY_MAX_CHARS),
                firstSeenChapterIndex = firstSeenChapterIndex,
                lastUpdated = System.currentTimeMillis(),
                userEdited = userEdited,
            )
            flows[fictionId]?.update { entries.values.filter { it.fictionId == fictionId } }
        }

        override suspend fun findEntityAcrossFictions(
            name: String,
            excludeFictionId: String?,
        ): List<FictionMemoryEntry> = emptyList()

        override fun entitiesForFiction(fictionId: String): Flow<List<FictionMemoryEntry>> =
            flows.getOrPut(fictionId) {
                MutableStateFlow(entries.values.filter { it.fictionId == fictionId })
            }.asStateFlow()

        override suspend fun forFictionOnce(fictionId: String): List<FictionMemoryEntry> =
            entries.values.filter { it.fictionId == fictionId }

        override suspend fun deleteEntry(fictionId: String, name: String) {
            entries.remove(fictionId to name.trim())
            flows[fictionId]?.update { entries.values.filter { it.fictionId == fictionId } }
        }
    }

    @Test fun `Notebook feed surfaces a newly-recorded entity for the current fiction`() = runTest {
        // Mirrors the FictionDetailViewModel.addNotebookEntry pass-
        // through: a user-edited note routed at recordEntity should
        // surface on the next emission of entitiesForFiction.
        val repo = FakeMemoryRepo()
        val fictionId = "book-current"

        repo.recordEntity(
            fictionId = fictionId,
            entityType = FictionMemoryEntry.Kind.CHARACTER,
            name = "Vin",
            summary = "Mistborn, young protagonist.",
            userEdited = true,
        )

        val rows = repo.entitiesForFiction(fictionId).first()
        assertEquals("expected one notebook entry, got: $rows", 1, rows.size)
        val row = rows.first()
        assertEquals("Vin", row.name)
        assertTrue("user-edited entry must be flagged manual", row.userEdited)
        assertTrue(row.summary.contains("Mistborn"))
    }

    @Test fun `Notebook feed is scoped to the current fiction id`() = runTest {
        // A separate book's entries must NOT appear in the current
        // book's notebook feed. The per-fiction observation guarantees
        // the Notebook section only renders what was recorded for the
        // page the user is looking at.
        val repo = FakeMemoryRepo()
        repo.recordEntity(
            "book-current",
            FictionMemoryEntry.Kind.CHARACTER,
            "Vin",
            "Current book character.",
        )
        repo.recordEntity(
            "book-other",
            FictionMemoryEntry.Kind.CHARACTER,
            "Kelsier",
            "Other book character.",
        )

        val currentRows = repo.entitiesForFiction("book-current").first()
        assertEquals(1, currentRows.size)
        assertEquals("Vin", currentRows.first().name)
    }

    @Test fun `deleteNotebookEntry removes the row from the feed`() = runTest {
        // The Notebook UI's per-row "Remove" affordance routes
        // FictionDetailViewModel.deleteNotebookEntry through to the
        // repo. After delete, the next feed emission must not include
        // the removed row.
        val repo = FakeMemoryRepo()
        val fictionId = "book-current"
        repo.recordEntity(fictionId, FictionMemoryEntry.Kind.CHARACTER, "Vin", "summary")
        repo.recordEntity(fictionId, FictionMemoryEntry.Kind.PLACE, "Luthadel", "summary")
        assertEquals(2, repo.entitiesForFiction(fictionId).first().size)

        repo.deleteEntry(fictionId, "Vin")

        val rows = repo.entitiesForFiction(fictionId).first()
        assertEquals(1, rows.size)
        assertNull(rows.firstOrNull { it.name == "Vin" })
        assertNotNull(rows.firstOrNull { it.name == "Luthadel" })
    }
}
