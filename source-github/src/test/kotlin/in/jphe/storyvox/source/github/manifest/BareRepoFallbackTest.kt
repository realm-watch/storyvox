package `in`.jphe.storyvox.source.github.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BareRepoFallbackTest {

    @Test fun `numbered markdown files in chapters dir are picked up in order`() {
        val chapters = BareRepoFallback.chaptersFrom(
            listOf(
                "chapters/01-intro.md",
                "chapters/03-fall.md",
                "chapters/02_brass-gate.md",
                "README.md", // not numbered, ignored
                "chapters/foo.md", // not numbered, ignored
            ),
        )

        assertEquals(3, chapters.size)
        assertEquals("chapters/01-intro.md", chapters[0].path)
        assertEquals("chapters/02_brass-gate.md", chapters[1].path)
        assertEquals("chapters/03-fall.md", chapters[2].path)
    }

    @Test fun `numbered files in src dir also match`() {
        val chapters = BareRepoFallback.chaptersFrom(listOf("src/01-elric.md", "src/02-fall.md"))
        assertEquals(2, chapters.size)
    }

    @Test fun `large numeric prefixes are sorted numerically not lexically`() {
        // `9 < 10` numerically; lexical sort would put 10 first.
        val chapters = BareRepoFallback.chaptersFrom(
            listOf(
                "chapters/10-late.md",
                "chapters/02-early.md",
                "chapters/9-mid.md",
            ),
        )
        assertEquals(
            listOf("chapters/02-early.md", "chapters/9-mid.md", "chapters/10-late.md"),
            chapters.map { it.path },
        )
    }

    @Test fun `stem becomes title-cased when no headingResolver`() {
        val chapters = BareRepoFallback.chaptersFrom(
            listOf("chapters/01-master-elric_and-the-orbs.md"),
        )
        // - and _ both become spaces; first char of each word capitalised.
        assertEquals("Master Elric And The Orbs", chapters.single().title)
    }

    @Test fun `headingResolver overrides stem-derived title when it returns non-blank`() {
        val chapters = BareRepoFallback.chaptersFrom(
            paths = listOf("chapters/01-intro.md"),
            headingResolver = { path ->
                if (path == "chapters/01-intro.md") "An Awakening" else null
            },
        )
        assertEquals("An Awakening", chapters.single().title)
    }

    @Test fun `headingResolver returning null falls back to stem title`() {
        val chapters = BareRepoFallback.chaptersFrom(
            paths = listOf("chapters/01-intro.md"),
            headingResolver = { null },
        )
        assertEquals("Intro", chapters.single().title)
    }

    @Test fun `headingResolver returning blank falls back to stem title`() {
        val chapters = BareRepoFallback.chaptersFrom(
            paths = listOf("chapters/01-intro.md"),
            headingResolver = { "   " },
        )
        assertEquals("Intro", chapters.single().title)
    }

    @Test fun `non-md numbered files are ignored`() {
        val chapters = BareRepoFallback.chaptersFrom(
            listOf("chapters/01-foo.txt", "chapters/02-bar.md"),
        )
        assertEquals(1, chapters.size)
        assertEquals("chapters/02-bar.md", chapters.single().path)
    }

    @Test fun `empty input yields empty list`() {
        assertTrue(BareRepoFallback.chaptersFrom(emptyList()).isEmpty())
    }

    @Test fun `repo name is title-cased`() {
        assertEquals(
            "The Archmage Coefficient",
            BareRepoFallback.titleFromRepoName("the-archmage-coefficient"),
        )
        assertEquals(
            "Underscored Name Works",
            BareRepoFallback.titleFromRepoName("underscored_name_works"),
        )
    }
}
