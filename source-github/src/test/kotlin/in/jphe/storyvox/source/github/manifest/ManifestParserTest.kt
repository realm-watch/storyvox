package `in`.jphe.storyvox.source.github.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestParserTest {

    @Test fun `full happy path uses book toml + storyvox json + summary md`() {
        val manifest = ManifestParser.parse(
            fictionId = "github:onedayokay/the-archmage-coefficient",
            bookToml = """
                [book]
                title = "The Archmage Coefficient"
                authors = ["onedayokay"]
                description = "An overpowered archmage relearns humility."
                language = "en"
                src = "src"
            """.trimIndent(),
            storyvoxJson = """
                {
                  "version": 1,
                  "cover": "assets/cover.png",
                  "tags": ["fantasy", "litrpg"],
                  "status": "ongoing",
                  "narrator_voice_id": "en-US-Andrew:DragonHDLatestNeural"
                }
            """.trimIndent(),
            summaryMd = """
                # Summary

                - [Master Elric](chapters/01-master-elric.md)
                - [The Brass Gate](chapters/02-brass-gate.md)
            """.trimIndent(),
        )

        assertEquals("The Archmage Coefficient", manifest.title)
        assertEquals("onedayokay", manifest.author)
        assertEquals("An overpowered archmage relearns humility.", manifest.description)
        assertEquals("assets/cover.png", manifest.coverPath)
        assertEquals(listOf("fantasy", "litrpg"), manifest.tags)
        assertEquals("ongoing", manifest.status)
        assertEquals("en", manifest.language)
        assertEquals("src", manifest.srcDir)
        assertEquals(2, manifest.chapters.size)
        assertEquals("Master Elric", manifest.chapters[0].title)
        assertEquals("en-US-Andrew:DragonHDLatestNeural", manifest.narratorVoiceId)
    }

    @Test fun `bare repo falls back to repo name + numbered files`() {
        val manifest = ManifestParser.parse(
            fictionId = "github:jphein/example-fiction",
            bareRepoPaths = listOf(
                "chapters/01-intro.md",
                "chapters/02-the-gate.md",
                "README.md",
            ),
        )

        assertEquals("Example Fiction", manifest.title)
        assertEquals("jphein", manifest.author)
        assertEquals(2, manifest.chapters.size)
        assertEquals("chapters/01-intro.md", manifest.chapters[0].path)
        assertEquals("Intro", manifest.chapters[0].title)
        assertNull(manifest.description)
        assertNull(manifest.coverPath)
        assertTrue(manifest.tags.isEmpty())
        assertNull(manifest.status)
    }

    @Test fun `summary md takes precedence over bare-repo fallback`() {
        val manifest = ManifestParser.parse(
            fictionId = "github:o/r",
            bookToml = """[book]
                |title = "T"
            """.trimMargin(),
            summaryMd = "- [Custom](custom.md)",
            bareRepoPaths = listOf("chapters/01-numbered.md"),
        )

        assertEquals(1, manifest.chapters.size)
        assertEquals("custom.md", manifest.chapters.single().path)
        assertEquals("Custom", manifest.chapters.single().title)
    }

    @Test fun `falls back to bare repo when summary md is empty`() {
        // Author may have a SUMMARY.md that's just a heading with no
        // chapter list (work-in-progress repo). Fall through to the
        // numbered-file heuristic rather than yielding zero chapters
        // when files are present.
        val manifest = ManifestParser.parse(
            fictionId = "github:o/r",
            summaryMd = "# Summary\n",
            bareRepoPaths = listOf("chapters/01-fall.md"),
        )

        assertEquals(1, manifest.chapters.size)
        assertEquals("chapters/01-fall.md", manifest.chapters.single().path)
    }

    @Test fun `book toml without authors falls back to repo owner`() {
        val manifest = ManifestParser.parse(
            fictionId = "github:owner/repo",
            bookToml = """[book]
                |title = "Has Title"
            """.trimMargin(),
        )
        assertEquals("Has Title", manifest.title)
        assertEquals("owner", manifest.author)
    }

    @Test fun `blank book toml title falls back to repo name`() {
        val manifest = ManifestParser.parse(
            fictionId = "github:o/lovely-repo",
            bookToml = """[book]
                |title = ""
            """.trimMargin(),
        )
        assertEquals("Lovely Repo", manifest.title)
    }

    @Test fun `storyvox json fields fill in mdbook gaps`() {
        val manifest = ManifestParser.parse(
            fictionId = "github:o/r",
            bookToml = """[book]
                |title = "T"
            """.trimMargin(),
            storyvoxJson = """
                {
                  "version": 1,
                  "cover": "cover.png",
                  "tags": ["sci-fi"],
                  "status": "completed",
                  "honeypot_selectors": [".secret"]
                }
            """.trimIndent(),
        )

        assertEquals("cover.png", manifest.coverPath)
        assertEquals(listOf("sci-fi"), manifest.tags)
        assertEquals("completed", manifest.status)
        assertEquals(listOf(".secret"), manifest.honeypotSelectors)
    }

    @Test fun `unparseable manifests fall through gracefully`() {
        val manifest = ManifestParser.parse(
            fictionId = "github:o/r",
            bookToml = "garbage\nno-section\n",
            storyvoxJson = "{ not json",
        )

        // Nothing parsed; we still get a valid manifest with fallbacks.
        assertEquals("R", manifest.title) // from repo name "r" → titlecase
        assertEquals("o", manifest.author)
        assertNull(manifest.description)
        assertNull(manifest.coverPath)
    }

    @Test fun `malformed fictionId still yields something printable`() {
        val manifest = ManifestParser.parse(fictionId = "not-a-github-id")
        // unknown owner, raw id as repo
        assertEquals("Not A Github Id", manifest.title)
        assertEquals("unknown", manifest.author)
    }

    @Test fun `defaults match documented field origins`() {
        val manifest = ManifestParser.parse(fictionId = "github:owner/repo-name")
        assertEquals("Repo Name", manifest.title)
        assertEquals("owner", manifest.author)
        assertEquals("src", manifest.srcDir)
        assertTrue(manifest.tags.isEmpty())
        assertTrue(manifest.honeypotSelectors.isEmpty())
        assertTrue(manifest.chapters.isEmpty())
    }

    @Test fun `headingResolver routes through to bare-repo path`() {
        val manifest = ManifestParser.parse(
            fictionId = "github:o/r",
            bareRepoPaths = listOf("chapters/01-intro.md"),
            headingResolver = { "An Awakening" },
        )
        assertEquals("An Awakening", manifest.chapters.single().title)
    }
}
