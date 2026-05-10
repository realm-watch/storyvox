package `in`.jphe.storyvox.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #256 — regression coverage for the chapter-title prefix strip
 * that runs in the ChapterCard composable. The brass left-side index
 * already shows the chapter number; titles like "Ch. 116 - Insurmountable
 * Foe" used to render two numbers racing on the same row.
 */
class StripChapterPrefixTest {

    @Test
    fun `strips the common Ch dot N dash variant`() {
        assertEquals("Insurmountable Foe", stripChapterPrefix("Ch. 116 - Insurmountable Foe"))
    }

    @Test
    fun `strips Chapter N colon variant`() {
        assertEquals("The Brass Sigil", stripChapterPrefix("Chapter 7: The Brass Sigil"))
    }

    @Test
    fun `strips Ch dot N space-less variant`() {
        // RR titles occasionally drop the space after the period.
        assertEquals("Greed", stripChapterPrefix("Ch.7 - Greed"))
    }

    @Test
    fun `case-insensitive match`() {
        assertEquals("Insurmountable Foe", stripChapterPrefix("CH. 116 - Insurmountable Foe"))
        assertEquals("Insurmountable Foe", stripChapterPrefix("ch. 116 - Insurmountable Foe"))
        assertEquals("Brass Sigil", stripChapterPrefix("CHAPTER 7: Brass Sigil"))
    }

    @Test
    fun `em-dash and en-dash separators`() {
        assertEquals("Greed", stripChapterPrefix("Ch. 7 — Greed"))
        assertEquals("Greed", stripChapterPrefix("Ch. 7 – Greed"))
    }

    @Test
    fun `untouched when title has no prefix`() {
        assertEquals("The Brass Sigil", stripChapterPrefix("The Brass Sigil"))
        assertEquals("Lion's Roar Is Hiring a Copy Editor", stripChapterPrefix("Lion's Roar Is Hiring a Copy Editor"))
    }

    @Test
    fun `falls back to original when strip leaves empty`() {
        // Edge case: the title IS just the prefix. Empty isn't a useful
        // render so we fall back; the brass left-side index still
        // communicates the chapter number.
        assertEquals("Ch. 0 - ", stripChapterPrefix("Ch. 0 - "))
        assertEquals("Chapter 1:", stripChapterPrefix("Chapter 1:"))
    }

    @Test
    fun `does not strip mid-string prefixes`() {
        // The strip is anchored to start-of-string; a title like
        // "Vol 2 Ch. 6 - Greed" keeps the volume context intact.
        assertEquals("Vol 2 Ch. 6 - Greed", stripChapterPrefix("Vol 2 Ch. 6 - Greed"))
    }
}
