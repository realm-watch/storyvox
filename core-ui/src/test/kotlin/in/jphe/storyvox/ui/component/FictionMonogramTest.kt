package `in`.jphe.storyvox.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #322 — locks in the resolution order and edge cases for the
 * single-character monogram shown on cover-less fictions. Earlier
 * implementations rendered a bare '?' when the author field was
 * empty (RSS feeds, mempalace docs, etc.), reading as a broken
 * image. This helper resolves author → title → brass star fallback.
 */
class FictionMonogramTest {

    @Test
    fun `uses author first letter when present`() {
        assertEquals("J", fictionMonogram(author = "Jane Doe", title = "Some Story"))
    }

    @Test
    fun `uses title first letter when author is blank`() {
        // Common case: RSS feeds and many GitHub repos carry no author.
        assertEquals("L", fictionMonogram(author = "", title = "lionsroar.com"))
    }

    @Test
    fun `uses title when author is only whitespace`() {
        assertEquals("A", fictionMonogram(author = "   ", title = "Archmage Coefficient"))
    }

    @Test
    fun `uppercases lowercase initials`() {
        assertEquals("S", fictionMonogram(author = "sonu", title = "VoxSherpa"))
        assertEquals("S", fictionMonogram(author = "", title = "sky pride"))
    }

    @Test
    fun `skips leading punctuation to the first letter or digit`() {
        // RR sometimes formats titles with leading brackets / quotes.
        assertEquals("T", fictionMonogram(author = "", title = "\"The Brass Sigil\""))
        assertEquals("E", fictionMonogram(author = "[Editor's Pick] Edna Lin", title = "X"))
    }

    @Test
    fun `digits are valid monograms`() {
        // Some GitHub repos start with a digit (e.g. "2048-game-saga").
        assertEquals("2", fictionMonogram(author = "", title = "2048-game-saga"))
    }

    @Test
    fun `falls back to brass star when both empty`() {
        assertEquals("✦", fictionMonogram(author = "", title = ""))
    }

    @Test
    fun `falls back to brass star when nothing alphanumeric is present`() {
        // Defensive: a fiction that's only punctuation should still render
        // an intentional sigil mark rather than throw or surface a glyph
        // that reads as broken.
        assertEquals("✦", fictionMonogram(author = "—", title = "…"))
    }
}
