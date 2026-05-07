package `in`.jphe.storyvox.source.github.registry

import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.source.github.net.GitHubJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistryModelsTest {

    @Test fun `full document deserializes`() {
        val json = """
            {
              "version": 1,
              "fictions": [
                {
                  "id": "github:jphein/example-fiction",
                  "title": "Example Fiction",
                  "author": "onedayokay",
                  "description": "An overpowered archmage relearns humility.",
                  "cover_url": "https://example.com/cover.png",
                  "tags": ["fantasy", "litrpg"],
                  "featured": true,
                  "added_at": "2026-05-06",
                  "status": "ONGOING",
                  "rating": 4.5,
                  "chapter_count": 42
                }
              ]
            }
        """.trimIndent()

        val doc = GitHubJson.decodeFromString<RegistryDocument>(json)

        assertEquals(1, doc.version)
        assertEquals(1, doc.fictions.size)
        val e = doc.fictions[0]
        assertEquals("github:jphein/example-fiction", e.id)
        assertEquals("Example Fiction", e.title)
        assertEquals("onedayokay", e.author)
        assertEquals(listOf("fantasy", "litrpg"), e.tags)
        assertTrue(e.featured)
        assertEquals("2026-05-06", e.addedAt)
        assertEquals(4.5f, e.rating!!, 0.001f)
        assertEquals(42, e.chapterCount)
    }

    @Test fun `minimal entry tolerates missing optional fields`() {
        val json = """
            {
              "version": 1,
              "fictions": [
                { "id": "github:o/r", "title": "Minimal", "author": "o" }
              ]
            }
        """.trimIndent()

        val doc = GitHubJson.decodeFromString<RegistryDocument>(json)
        val e = doc.fictions.single()

        assertNull(e.description)
        assertNull(e.coverUrl)
        assertEquals(emptyList<String>(), e.tags)
        assertFalse(e.featured)
        assertNull(e.addedAt)
        assertNull(e.status)
        assertNull(e.rating)
        assertNull(e.chapterCount)
    }

    @Test fun `empty fictions list deserializes cleanly`() {
        val doc = GitHubJson.decodeFromString<RegistryDocument>(
            """{ "version": 1, "fictions": [] }""",
        )
        assertTrue(doc.fictions.isEmpty())
    }

    @Test fun `omitted fictions key defaults to empty list`() {
        // Curator might author a registry skeleton with just the version.
        val doc = GitHubJson.decodeFromString<RegistryDocument>("""{ "version": 1 }""")
        assertTrue(doc.fictions.isEmpty())
    }

    @Test fun `unknown keys at document or entry level are tolerated`() {
        // Forward-compat: registry schema may grow.
        val json = """
            {
              "version": 1,
              "schema_url": "https://example.com/schema.json",
              "fictions": [
                {
                  "id": "github:o/r", "title": "T", "author": "A",
                  "future_field": "shrug"
                }
              ]
            }
        """.trimIndent()

        val doc = GitHubJson.decodeFromString<RegistryDocument>(json)
        assertEquals("T", doc.fictions.single().title)
    }

    // ─── Mapping ───────────────────────────────────────────────────────

    @Test fun `entry to summary preserves required fields and assigns sourceId`() {
        val entry = RegistryEntry(
            id = "github:o/r",
            title = "Title",
            author = "Author",
            description = "Desc",
            coverUrl = "https://example.com/c.png",
            tags = listOf("fantasy"),
            featured = true,
            addedAt = "2026-05-06",
            status = "ONGOING",
            rating = 4.0f,
            chapterCount = 10,
        )

        val s = entry.toSummary()

        assertEquals("github:o/r", s.id)
        assertEquals("github", s.sourceId)
        assertEquals("Title", s.title)
        assertEquals("Author", s.author)
        assertEquals("Desc", s.description)
        assertEquals("https://example.com/c.png", s.coverUrl)
        assertEquals(listOf("fantasy"), s.tags)
        assertEquals(FictionStatus.ONGOING, s.status)
        assertEquals(10, s.chapterCount)
        assertEquals(4.0f, s.rating!!, 0.001f)
    }

    @Test fun `status maps the supported variants`() {
        listOf(
            "COMPLETED" to FictionStatus.COMPLETED,
            "completed" to FictionStatus.COMPLETED,
            "HIATUS" to FictionStatus.HIATUS,
            "DROPPED" to FictionStatus.DROPPED,
            "ONGOING" to FictionStatus.ONGOING,
        ).forEach { (raw, expected) ->
            val mapped = baseEntry().copy(status = raw).toSummary().status
            assertEquals("status=$raw", expected, mapped)
        }
    }

    @Test fun `unknown or null status falls back to ONGOING`() {
        assertEquals(FictionStatus.ONGOING, baseEntry().copy(status = null).toSummary().status)
        assertEquals(
            FictionStatus.ONGOING,
            baseEntry().copy(status = "WIBBLY").toSummary().status,
        )
    }

    private fun baseEntry() = RegistryEntry(
        id = "github:o/r",
        title = "T",
        author = "A",
    )
}
