package `in`.jphe.storyvox.feature.library

import `in`.jphe.storyvox.data.db.entity.Shelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #438 — regression guard for the four-tab/four-chip overlap, plus
 * v0.5.40 nav-restructure follow-up.
 *
 * v0.5.36 stacked the four-entry `LibraryTab` strip (All / Reading /
 * Inbox / History) directly on top of the four-entry shelf chip row
 * (All / Reading / Read / Wishlist), so the same strings (`All`,
 * `Reading`) sat in two nested navigation surfaces. We collapsed
 * `LibraryTab` to three entries (`Library / Inbox / History`) at the
 * time.
 *
 * v0.5.40 restructure — JP directive "put follows and browse into the
 * library tab" — grew the tab strip back to five entries, but the
 * #438 invariants still hold: no tab label may collide with a shelf
 * chip label. The new tabs are [LibraryTab.Browse] and
 * [LibraryTab.Follows], neither of which is a shelf name, so the
 * collision contract is preserved.
 */
class LibraryTabCollapseTest {

    @Test
    fun `LibraryTab has exactly five entries`() {
        // v0.5.40 restructure — Library / Browse / Follows / Inbox /
        // History. Five is the new ceiling; growing past five needs a
        // UX review since SecondaryScrollableTabRow is already required
        // to fit five labels on Flip3 portrait.
        assertEquals(5, LibraryTab.entries.size)
    }

    @Test
    fun `LibraryTab labels do not collide with Shelf displayNames`() {
        // #438 — the structural fix: a tab label must never duplicate a
        // shelf chip label, since the chip row sits directly under the
        // tab row on the Library sub-tab. If anyone re-adds a
        // `Reading` / `Read` / `Wishlist` tab without also re-thinking
        // the chip strip, this fails.
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
        // The Library tab hosts the chip row + the user's books; it
        // must be the landing tab so a fresh launch shows the user
        // what they own, not the Browse / Follows / Inbox feeds.
        val first = LibraryTab.entries.first()
        assertEquals(LibraryTab.Library, first)
        assertEquals("Library", first.label)
    }

    @Test
    fun `Browse and Follows are folded under Library as sub-tabs`() {
        // v0.5.40 restructure pin — Browse / Follows are no longer
        // top-level bottom-bar destinations; they're sub-tabs inside
        // the Library destination. If anyone reverts to a top-bar
        // Browse / Follows, this assertion still passes (the enum
        // entries can coexist), but the bottom-nav assertions in
        // BottomTabBar HomeTab.entries catch the regression there.
        assertNotNull(
            "LibraryTab.Browse missing — v0.5.40 folded Browse under Library.",
            LibraryTab.entries.firstOrNull { it == LibraryTab.Browse },
        )
        assertNotNull(
            "LibraryTab.Follows missing — v0.5.40 folded Follows under Library.",
            LibraryTab.entries.firstOrNull { it == LibraryTab.Follows },
        )
    }

    @Test
    fun `Inbox and History remain reachable as their own tabs`() {
        // These two tabs are NOT shelves — they're independent feeds
        // (events / chapter-opens). Keep them as tab entries so the
        // restructure doesn't accidentally bury them inside a sheet.
        assertNotNull(LibraryTab.entries.firstOrNull { it == LibraryTab.Inbox })
        assertNotNull(LibraryTab.entries.firstOrNull { it == LibraryTab.History })
    }

    @Test
    fun `LibraryTab order matches reading flow`() {
        // Left-to-right: own-it (Library) → find-it (Browse / Follows)
        // → new updates (Inbox) → revisit (History). The order isn't
        // alphabetical or random; it's narratively grouped, and the
        // SecondaryScrollableTabRow indicator pill follows this order.
        val expectedOrder = listOf(
            LibraryTab.Library,
            LibraryTab.Browse,
            LibraryTab.Follows,
            LibraryTab.Inbox,
            LibraryTab.History,
        )
        assertEquals(expectedOrder, LibraryTab.entries.toList())
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
