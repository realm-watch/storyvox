package `in`.jphe.storyvox.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the [coverSourceFamilyFor] mapping that decides which watermark
 * glyph [BrandedCoverTile] draws for a given fiction.
 *
 * Notion is the only source currently mapped to a branded family; all
 * other sources fall to [CoverSourceFamily.Generic] so we never claim
 * a specific brand on third-party content. When more branded families
 * land (Outline, GitHub, …) the new mapping should land here too.
 */
class CoverSourceFamilyTest {

    @Test
    fun `notion sourceId maps to TechEmpower family`() {
        // The Notion source's only configured root in v0.5.51 is the
        // TechEmpower org page; mapping is therefore safe.
        assertEquals(CoverSourceFamily.TechEmpower, coverSourceFamilyFor("notion"))
    }

    @Test
    fun `unrecognized sourceIds fall to Generic family`() {
        assertEquals(CoverSourceFamily.Generic, coverSourceFamilyFor("royalroad"))
        assertEquals(CoverSourceFamily.Generic, coverSourceFamilyFor("github"))
        assertEquals(CoverSourceFamily.Generic, coverSourceFamilyFor("rss"))
        assertEquals(CoverSourceFamily.Generic, coverSourceFamilyFor("epub"))
    }

    @Test
    fun `null or empty sourceId falls to Generic family`() {
        // HistoryEntry doesn't carry sourceId — the cascade-fallback
        // case needs a sensible Generic default rather than throwing.
        assertEquals(CoverSourceFamily.Generic, coverSourceFamilyFor(null))
        assertEquals(CoverSourceFamily.Generic, coverSourceFamilyFor(""))
    }
}
