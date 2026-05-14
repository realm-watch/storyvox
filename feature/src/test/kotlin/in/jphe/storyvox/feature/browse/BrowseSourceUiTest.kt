package `in`.jphe.storyvox.feature.browse

import `in`.jphe.storyvox.data.source.SourceIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plugin-seam Phase 3 (#384) — unit tests for the [BrowseSourceUi]
 * side-table. Verifies the lookup tables return the expected per-
 * source UI hints for all 17 in-tree backends, with sensible defaults
 * for ids the table doesn't know about (out-of-tree plugin posture).
 */
class BrowseSourceUiTest {

    @Test fun `chipLabel returns concise label for every in-tree source`() {
        val expected = mapOf(
            SourceIds.ROYAL_ROAD to "Royal Road",
            SourceIds.GITHUB to "GitHub",
            SourceIds.MEMPALACE to "Palace",
            SourceIds.RSS to "RSS",
            SourceIds.EPUB to "Local",
            SourceIds.OUTLINE to "Wiki",
            SourceIds.GUTENBERG to "Gutenberg",
            SourceIds.AO3 to "AO3",
            SourceIds.STANDARD_EBOOKS to "Standard Ebooks",
            SourceIds.WIKIPEDIA to "Wikipedia",
            SourceIds.WIKISOURCE to "Wikisource",
            // Issue #417 — :source-kvmr → :source-radio. Both ids
            // resolve to the "Radio" chip during the one-cycle
            // migration overlap.
            SourceIds.RADIO to "Radio",
            SourceIds.KVMR to "Radio",
            SourceIds.NOTION to "Notion",
            SourceIds.HACKERNEWS to "Hacker News",
            SourceIds.ARXIV to "arXiv",
            SourceIds.PLOS to "PLOS",
            SourceIds.DISCORD to "Discord",
        )

        assertEquals(18, expected.size)
        for ((id, label) in expected) {
            assertEquals(
                "Unexpected chip label for $id",
                label,
                BrowseSourceUi.chipLabel(id, "fallback"),
            )
        }
    }

    @Test fun `chipLabel falls through to displayName for unknown ids`() {
        assertEquals("Custom Backend", BrowseSourceUi.chipLabel("custom-id", "Custom Backend"))
    }

    @Test fun `supportedTabs gives a non-empty list for every in-tree source`() {
        for (id in IN_TREE_IDS) {
            val tabs = BrowseSourceUi.supportedTabs(id)
            assertTrue("Expected non-empty tabs for $id", tabs.isNotEmpty())
            assertTrue("Expected Popular tab for $id", BrowseTab.Popular in tabs)
        }
    }

    @Test fun `supportedTabs adds auth-only github tabs when signed in`() {
        val anon = BrowseSourceUi.supportedTabs(SourceIds.GITHUB, githubSignedIn = false)
        val authed = BrowseSourceUi.supportedTabs(SourceIds.GITHUB, githubSignedIn = true)

        assertFalse(BrowseTab.MyRepos in anon)
        assertFalse(BrowseTab.Starred in anon)
        assertFalse(BrowseTab.Gists in anon)
        assertTrue(BrowseTab.MyRepos in authed)
        assertTrue(BrowseTab.Starred in authed)
        assertTrue(BrowseTab.Gists in authed)
    }

    @Test fun `filterShape RoyalRoad GitHub MemPalace get sheet, others get None`() {
        assertEquals(FilterShape.RoyalRoad, BrowseSourceUi.filterShape(SourceIds.ROYAL_ROAD))
        assertEquals(FilterShape.GitHub, BrowseSourceUi.filterShape(SourceIds.GITHUB))
        assertEquals(FilterShape.MemPalace, BrowseSourceUi.filterShape(SourceIds.MEMPALACE))

        // The remaining 14 in-tree sources have no filter sheet.
        val noFilter = IN_TREE_IDS - setOf(SourceIds.ROYAL_ROAD, SourceIds.GITHUB, SourceIds.MEMPALACE)
        for (id in noFilter) {
            assertEquals("Expected None filter shape for $id", FilterShape.None, BrowseSourceUi.filterShape(id))
        }
    }

    @Test fun `searchHint returns sensible copy for every in-tree source`() {
        for (id in IN_TREE_IDS) {
            val hint = BrowseSourceUi.searchHint(id, displayName = "FallbackName")
            assertTrue(
                "Expected non-empty searchHint for $id (got: '$hint')",
                hint.isNotBlank(),
            )
            // Default `else` branch uses the displayName; in-tree
            // sources should NOT fall through to that branch.
            assertFalse(
                "$id fell through to the default searchHint",
                hint == "Search FallbackName",
            )
        }
    }

    @Test fun `searchHint falls through to displayName for unknown ids`() {
        assertEquals("Search My Backend", BrowseSourceUi.searchHint("custom-id", "My Backend"))
    }

    private companion object {
        /** All 17 in-tree backends. Adding a row here when a new
         *  backend lands is the explicit "did we add the UI hint?"
         *  audit step. */
        val IN_TREE_IDS = setOf(
            SourceIds.ROYAL_ROAD, SourceIds.GITHUB, SourceIds.MEMPALACE,
            SourceIds.RSS, SourceIds.EPUB, SourceIds.OUTLINE,
            SourceIds.GUTENBERG, SourceIds.AO3, SourceIds.STANDARD_EBOOKS,
            SourceIds.WIKIPEDIA, SourceIds.WIKISOURCE,
            // Issue #417 — RADIO is canonical; KVMR alias kept for
            // one cycle to cover persisted-id resolution.
            SourceIds.RADIO, SourceIds.KVMR,
            SourceIds.NOTION, SourceIds.HACKERNEWS, SourceIds.ARXIV,
            SourceIds.PLOS, SourceIds.DISCORD,
        )
    }
}
