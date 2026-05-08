package `in`.jphe.storyvox.source.github.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookTomlParserTest {

    @Test fun `full book section parses every field`() {
        val toml = BookTomlParser.parse(
            """
                [book]
                title = "The Archmage Coefficient"
                authors = ["onedayokay", "co-author"]
                description = "An overpowered archmage relearns humility."
                language = "en"
                src = "src"
            """.trimIndent(),
        )

        assertEquals("The Archmage Coefficient", toml.title)
        assertEquals(listOf("onedayokay", "co-author"), toml.authors)
        assertEquals("An overpowered archmage relearns humility.", toml.description)
        assertEquals("en", toml.language)
        assertEquals("src", toml.src)
    }

    @Test fun `keys outside the book section are ignored`() {
        val toml = BookTomlParser.parse(
            """
                [book]
                title = "T"

                [output.html]
                no-section-folding = true
                additional-css = ["theme/my.css"]

                [preprocessor.toc]
                command = "mdbook-toc"
            """.trimIndent(),
        )

        assertEquals("T", toml.title)
        // Nothing leaks from output/preprocessor sections.
        assertTrue(toml.authors.isEmpty())
        assertNull(toml.description)
    }

    @Test fun `comments are stripped including inline comments after values`() {
        val toml = BookTomlParser.parse(
            """
                # full-line comment
                [book]
                title = "T" # inline comment
                # another full-line
                language = "en"
            """.trimIndent(),
        )

        assertEquals("T", toml.title)
        assertEquals("en", toml.language)
    }

    @Test fun `comment characters inside string values are preserved`() {
        val toml = BookTomlParser.parse("""[book]
            |description = "Hash # in the description"
        """.trimMargin())

        assertEquals("Hash # in the description", toml.description)
    }

    @Test fun `escape sequences in strings decode`() {
        val toml = BookTomlParser.parse(
            """
                [book]
                title = "Q: \"Why?\""
                description = "line1\nline2\twith tab"
            """.trimIndent(),
        )

        assertEquals("Q: \"Why?\"", toml.title)
        assertEquals("line1\nline2\twith tab", toml.description)
    }

    @Test fun `empty array yields empty authors`() {
        val toml = BookTomlParser.parse("""[book]
            |authors = []
        """.trimMargin())

        assertTrue(toml.authors.isEmpty())
    }

    @Test fun `single-author array works`() {
        val toml = BookTomlParser.parse("""[book]
            |authors = ["solo"]
        """.trimMargin())

        assertEquals(listOf("solo"), toml.authors)
    }

    @Test fun `unparseable values are silently dropped`() {
        // Numbers, booleans, dotted keys — none of these are
        // recognised, but the parser doesn't crash on them.
        val toml = BookTomlParser.parse(
            """
                [book]
                title = "OK"
                future_field = 42
                another = true
                a.b.c = "dotted"
                description = "Real value still parses"
            """.trimIndent(),
        )

        assertEquals("OK", toml.title)
        assertEquals("Real value still parses", toml.description)
    }

    @Test fun `missing book section yields all-null result`() {
        val toml = BookTomlParser.parse(
            """
                [preprocessor.toc]
                command = "mdbook-toc"
            """.trimIndent(),
        )

        assertNull(toml.title)
        assertNull(toml.description)
        assertTrue(toml.authors.isEmpty())
    }

    @Test fun `empty input yields all-null result`() {
        val toml = BookTomlParser.parse("")
        assertNull(toml.title)
        assertNull(toml.description)
    }

    @Test fun `whitespace tolerance around tokens`() {
        val toml = BookTomlParser.parse(
            "[book]\n   title    =    \"Spaced\"   \n",
        )
        assertEquals("Spaced", toml.title)
    }
}
