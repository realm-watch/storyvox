package `in`.jphe.storyvox.feature.library

import `in`.jphe.storyvox.data.db.entity.Shelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #438 — regression guard for the four-tab/four-chip overlap.
 *
 * v0.5.36 stacked the four-entry `LibraryTab` strip (All / Reading /
 * Inbox / History) directly on top of the four-entry shelf chip row
 * (All / Reading / Read / Wishlist), so the same strings (`All`,
 * `Reading`) sat in two nested navigation surfaces. We collapsed
 * `LibraryTab` to three entries (`Library / Inbox / History`) and
 * surface the shelf filter as the canonical Reading affordance via
 * the chip row.
 *
 * Tests pin the new contract so a future PR that re-introduces a
 * `Reading` (or other shelf-named) tab entry fails here first.
 */
class LibraryTabCollapseTest {

    @Test
    fun `LibraryTab has exactly three entries`() {
        // The pre-#438 enum had four entries (All / Reading / Inbox /
        // History). Three is the new ceiling; growing past three needs
        // a UX review, because the Flip3 portrait width gets tight at
        // four labels even with single-word names.
        assertEquals(3, LibraryTab.entries.size)
    }

    @Test
    fun `LibraryTab labels do not collide with Shelf displayNames`() {
        // #438 — the structural fix: a tab label must never duplicate a
        // shelf chip label, since the chip row sits directly under the
        // tab row. If anyone re-adds a `Reading` / `Read` / `Wishlist`
        // tab without also re-thinking the chip strip, this fails.
        val tabLabels = LibraryTab.entries.map { it.label.lowercase() }.toSet()
        val shelfLabels = Shelf.ALL.map { it.displayName.lowercase() }.toSet()
        val overlap = tabLabels intersect shelfLabels
        assertTrue(
            "Tab labels $tabLabels must not overlap shelf labels $shelfLabels; collision: $overlap",
            overlap.isEmpty(),
        )
    }

    @Test
    fun `LibraryTab does not include an All entry`() {
        // The chip row already has a leading "All" chip (see
        // ShelfChipRow). Re-introducing an `All` tab label collides
        // with that chip — the exact bug reported in #438. Pin the
        // absence so a refactor that adds it back fails here.
        val byName = LibraryTab.entries.firstOrNull { it.name == "All" }
        assertNull(
            "LibraryTab.All was removed in #438 — do not re-add it; use Library instead.",
            byName,
        )
        val byLabel = LibraryTab.entries.firstOrNull { it.label.equals("All", ignoreCase = true) }
        assertNull(
            "No LibraryTab may carry the 'All' label — it duplicates the leading shelf chip.",
            byLabel,
        )
    }

    @Test
    fun `Library is the first tab and is the default landing surface`() {
        // The Library tab hosts the chip row; it must be the landing
        // tab so a fresh launch shows the user their books, not the
        // Inbox feed or History.
        val first = LibraryTab.entries.first()
        assertEquals(LibraryTab.Library, first)
        assertEquals("Library", first.label)
    }

    @Test
    fun `Inbox and History remain reachable as their own tabs`() {
        // These two tabs are NOT shelves — they're independent feeds
        // (events / chapter-opens). Keep them as tab entries so the
        // #438 collapse doesn't accidentally bury them inside a sheet.
        assertNotNull(LibraryTab.entries.firstOrNull { it == LibraryTab.Inbox })
        assertNotNull(LibraryTab.entries.firstOrNull { it == LibraryTab.History })
    }

    @Test
    fun `ShelfFilter All is structurally distinguishable from a Shelf-scoped filter`() {
        // The chip row needs a stable "All" selector so the user can
        // get back to the unfiltered grid in one tap. The sealed-class
        // shape makes that distinction structural.
        val allFilter: ShelfFilter = ShelfFilter.All
        val readingFilter: ShelfFilter = ShelfFilter.OneShelf(Shelf.Reading)
        assertTrue(allFilter is ShelfFilter.All)
        assertTrue(readingFilter is ShelfFilter.OneShelf)
        assertFalse(allFilter === readingFilter)
    }
}
