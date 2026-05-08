package `in`.jphe.storyvox.source.github.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryMdParserTest {

    @Test fun `standard mdbook summary parses chapter entries`() {
        val chapters = SummaryMdParser.parse(
            """
                # Summary

                - [Master Elric and the Spirit Orbs](chapters/01-master-elric.md)
                - [The Brass Gate](chapters/02-brass-gate.md)
                - [The Fall](chapters/03-the-fall.md)
            """.trimIndent(),
        )

        assertEquals(3, chapters.size)
        assertEquals("Master Elric and the Spirit Orbs", chapters[0].title)
        assertEquals("chapters/01-master-elric.md", chapters[0].path)
        assertEquals("The Brass Gate", chapters[1].title)
        assertEquals("chapters/02-brass-gate.md", chapters[1].path)
    }

    @Test fun `asterisk bullets work the same as dashes`() {
        val chapters = SummaryMdParser.parse(
            """
                * [One](a.md)
                * [Two](b.md)
            """.trimIndent(),
        )
        assertEquals(2, chapters.size)
    }

    @Test fun `indented entries are still parsed as chapters`() {
        val chapters = SummaryMdParser.parse(
            """
                # Summary

                - [Part 1](part1.md)
                  - [Sub A](part1/a.md)
                  - [Sub B](part1/b.md)
                - [Part 2](part2.md)
            """.trimIndent(),
        )

        assertEquals(4, chapters.size)
        assertEquals("Sub A", chapters[1].title)
        assertEquals("part1/a.md", chapters[1].path)
    }

    @Test fun `non-bullet lines including the heading are skipped`() {
        val chapters = SummaryMdParser.parse(
            """
                # Summary

                Some prose the author added.

                ## Part 1

                - [One](a.md)

                ## Part 2

                - [Two](b.md)
            """.trimIndent(),
        )

        assertEquals(2, chapters.size)
        assertEquals(listOf("a.md", "b.md"), chapters.map { it.path })
    }

    @Test fun `leading slash on the path is trimmed`() {
        val chapters = SummaryMdParser.parse("- [T](/abs/path.md)")
        assertEquals("abs/path.md", chapters.single().path)
    }

    @Test fun `empty title or path is rejected`() {
        // We can't represent these as ManifestChapter — drop them
        // rather than emitting a malformed row.
        val chapters = SummaryMdParser.parse(
            """
                - [](a.md)
                - [Title]()
                - [OK](good.md)
            """.trimIndent(),
        )
        assertEquals(1, chapters.size)
        assertEquals("OK", chapters.single().title)
    }

    @Test fun `empty input yields empty list`() {
        assertTrue(SummaryMdParser.parse("").isEmpty())
        assertTrue(SummaryMdParser.parse("   \n   ").isEmpty())
    }
}
