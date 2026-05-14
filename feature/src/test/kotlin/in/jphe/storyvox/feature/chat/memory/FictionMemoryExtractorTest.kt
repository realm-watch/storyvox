package `in`.jphe.storyvox.feature.chat.memory

import `in`.jphe.storyvox.data.db.entity.FictionMemoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #217 — coverage for the v1 regex-based entity extractor that
 * post-processes AI chat replies into memory entries. The extractor
 * is *deliberately* approximate (see the kdoc on
 * [FictionMemoryExtractor]) so these tests focus on the common-case
 * "X is a Y" pattern rather than aiming for full coverage.
 *
 * Follow-up (a) in the PR description replaces this with a
 * structured LLM call.
 */
class FictionMemoryExtractorTest {

    @Test fun `extracts character defined with "is a"`() {
        val reply = "Vin is a Mistborn who works with the crew. " +
            "She is sixteen at the start of the book."
        val entities = FictionMemoryExtractor.extract(reply)

        assertEquals("expected one entity from the reply", 1, entities.size)
        val e = entities.first()
        assertEquals("Vin", e.name)
        // Classifier should land on CHARACTER (no place/concept marker).
        assertEquals(FictionMemoryEntry.Kind.CHARACTER, e.kind)
        assertTrue(
            "summary should include the predicate",
            e.summary.contains("Mistborn"),
        )
    }

    @Test fun `extracts a place when summary contains a place marker`() {
        val reply = "Luthadel is the largest city in the Final Empire."
        val entities = FictionMemoryExtractor.extract(reply)
        assertEquals(1, entities.size)
        assertEquals(FictionMemoryEntry.Kind.PLACE, entities.first().kind)
        assertEquals("Luthadel", entities.first().name)
    }

    @Test fun `extracts a concept when summary contains a concept marker`() {
        val reply = "Allomancy is a magic system tied to consuming metals."
        val entities = FictionMemoryExtractor.extract(reply)
        assertEquals(1, entities.size)
        assertEquals(FictionMemoryEntry.Kind.CONCEPT, entities.first().kind)
        assertEquals("Allomancy", entities.first().name)
    }

    @Test fun `de-duplicates by name within a single reply`() {
        val reply = "Vin is a Mistborn. Vin is a young thief from the streets."
        val entities = FictionMemoryExtractor.extract(reply)
        // Both sentences match, but distinctBy(name.lowercase()) collapses.
        assertEquals(1, entities.size)
        assertEquals("Vin", entities.first().name)
    }

    @Test fun `ignores stopword sentence starters`() {
        val reply = "The book is a fantasy novel. " +
            "This is an excellent read. " +
            "However is a non-character."
        val entities = FictionMemoryExtractor.extract(reply)
        // None of these should produce a CHARACTER entity — they're
        // all sentence-leading capitalisation, not names.
        assertTrue(
            "expected no entities from stopword-leading sentences, got: $entities",
            entities.isEmpty(),
        )
    }

    @Test fun `empty input returns empty list`() {
        assertEquals(emptyList<FictionMemoryExtractor.ExtractedEntity>(), FictionMemoryExtractor.extract(""))
        assertEquals(emptyList<FictionMemoryExtractor.ExtractedEntity>(), FictionMemoryExtractor.extract("   "))
    }
}
