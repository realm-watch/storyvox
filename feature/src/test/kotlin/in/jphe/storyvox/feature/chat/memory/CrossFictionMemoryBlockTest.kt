package `in`.jphe.storyvox.feature.chat.memory

import `in`.jphe.storyvox.data.db.entity.FictionMemoryEntry
import `in`.jphe.storyvox.data.repository.FictionMemoryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #217 — coverage for the cross-fiction prompt-block builder.
 * Focuses on the budget-enforcement path: when the user has more
 * cross-fiction matches than the character cap allows, the oldest
 * are dropped first and the metric reflects the drop count.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrossFictionMemoryBlockTest {

    private class FakeMemoryRepo(
        private val byName: Map<String, List<FictionMemoryEntry>>,
        private val perFiction: Map<String, List<FictionMemoryEntry>> = emptyMap(),
    ) : FictionMemoryRepository {
        override suspend fun recordEntity(
            fictionId: String,
            entityType: FictionMemoryEntry.Kind,
            name: String,
            summary: String,
            firstSeenChapterIndex: Int?,
            userEdited: Boolean,
        ) = Unit
        override suspend fun findEntityAcrossFictions(
            name: String,
            excludeFictionId: String?,
        ): List<FictionMemoryEntry> = (byName[name] ?: emptyList())
            .filter { it.fictionId != excludeFictionId }
        override fun entitiesForFiction(fictionId: String) =
            kotlinx.coroutines.flow.flowOf(perFiction[fictionId] ?: emptyList())
        override suspend fun forFictionOnce(fictionId: String): List<FictionMemoryEntry> =
            perFiction[fictionId] ?: emptyList()
        override suspend fun deleteEntry(fictionId: String, name: String) = Unit
    }

    @Test
    fun `block is empty when toggle is off`() = runTest {
        val repo = FakeMemoryRepo(byName = mapOf("Kelsier" to listOf(entry("a", "Kelsier"))))
        val block = CrossFictionMemoryBlock(repo) { "title:$it" }
        val res = block.build("anchor", "Who is Kelsier?", enabled = false)
        assertEquals("", res.text)
        assertEquals(0, res.entryCount)
    }

    @Test
    fun `block is empty when no candidate name has cross-fiction matches`() = runTest {
        val repo = FakeMemoryRepo(byName = emptyMap())
        val block = CrossFictionMemoryBlock(repo) { "title:$it" }
        val res = block.build("anchor", "Who is Kelsier?", enabled = true)
        assertEquals("", res.text)
        assertEquals(0, res.entryCount)
    }

    @Test
    fun `block renders a single hit with the title and summary`() = runTest {
        val repo = FakeMemoryRepo(
            byName = mapOf("Kelsier" to listOf(entry("f-prior", "Kelsier", "Mistborn"))),
        )
        val block = CrossFictionMemoryBlock(repo) { "Mistborn (title)" }
        val res = block.build("anchor", "Tell me about Kelsier", enabled = true)
        assertEquals(1, res.entryCount)
        assertTrue(res.text.contains("Kelsier"))
        assertTrue(res.text.contains("Mistborn (title)"))
        assertTrue(res.text.contains("Cross-fiction context"))
    }

    @Test
    fun `budget enforcement drops oldest entries when over the character cap`() = runTest {
        // Each entry's summary is ~200 chars; the header alone uses
        // ~220 chars, leaving ~1800 chars of body budget. ~9 entries
        // fit; 30 entries should produce at least 20 drops.
        val largeBody = "x".repeat(200)
        val matches = (1..30).map { i ->
            entry(
                fictionId = "book-$i",
                name = "Kelsier",
                summary = largeBody,
                lastUpdated = i.toLong(),
            )
        }
        val repo = FakeMemoryRepo(byName = mapOf("Kelsier" to matches))
        val block = CrossFictionMemoryBlock(repo) { "title:$it" }
        val res = block.build("anchor", "Tell me about Kelsier", enabled = true)

        assertTrue(
            "expected some entries to survive the budget. got: ${res.entryCount}",
            res.entryCount in 1..15,
        )
        assertTrue(
            "expected some entries to be dropped. dropped=${res.droppedCount}",
            res.droppedCount > 0,
        )
        // Total characters must not exceed the budget. Allow a tiny
        // slack for the final line that just fit before the cutoff.
        assertTrue(
            "rendered text size ${res.text.length} must be near budget cap " +
                "${CrossFictionMemoryBlock.TOKEN_BUDGET_CHARS}",
            res.text.length <= CrossFictionMemoryBlock.TOKEN_BUDGET_CHARS + 300,
        )
        // Older entries (lower lastUpdated) should be the dropped ones.
        // The kept entries should be the recent half — the test
        // verifies by checking that the highest-numbered book ids
        // appear in the rendered text.
        assertTrue(
            "block should keep recent entries (book-30)",
            res.text.contains("title:book-30"),
        )
    }

    @Test
    fun `block falls back to notebook entries when user message has no names`() = runTest {
        // User typed "who is he?" — no capital-name match. The block
        // should broaden to the current fiction's notebook entries.
        val anchorNotebook = listOf(entry("anchor", "Mira"))
        val crossBook = listOf(entry("f-prior", "Mira", "A pilot"))
        val repo = FakeMemoryRepo(
            byName = mapOf("Mira" to crossBook),
            perFiction = mapOf("anchor" to anchorNotebook),
        )
        val block = CrossFictionMemoryBlock(repo) { "Other Book" }
        val res = block.build("anchor", "who is he again?", enabled = true)
        assertEquals(1, res.entryCount)
        assertTrue(res.text.contains("Mira"))
    }

    private fun entry(
        fictionId: String,
        name: String,
        summary: String = "summary",
        lastUpdated: Long = 0L,
    ) = FictionMemoryEntry(
        fictionId = fictionId,
        entityType = FictionMemoryEntry.Kind.CHARACTER.name,
        name = name,
        summary = summary,
        firstSeenChapterIndex = null,
        lastUpdated = lastUpdated,
    )
}
