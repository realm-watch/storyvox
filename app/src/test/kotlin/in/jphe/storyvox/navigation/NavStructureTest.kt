package `in`.jphe.storyvox.navigation

import `in`.jphe.storyvox.ui.component.HomeTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Restructure (v0.5.40) — bottom-nav + primary-destination contract.
 *
 * JP directive: "put settings in the main nav bar, and put follows and
 * browse into the library tab." This collapses the bottom nav to two
 * primary destinations (Library + Settings) and demotes Browse /
 * Follows / Playing / Voices to deep routes reached from inside Library
 * (sub-tabs) or via drill-down.
 *
 * These tests pin the new contract so a future refactor that re-adds a
 * Browse pill to the bottom bar or that removes the Settings
 * destination fails here first. Plain JUnit (no Robolectric): we're
 * only inspecting enum + route-string state, not any Android framework
 * objects.
 */
class NavStructureTest {

    @Test
    fun `bottom nav exposes exactly two primary destinations`() {
        // Library + Settings. Going past two needs a UX review — the
        // sliding indicator pill in BottomTabBar is centered per-cell,
        // and three cells is the next step that would split the bar
        // visually.
        assertEquals(2, HomeTab.entries.size)
    }

    @Test
    fun `bottom nav primary destinations are Library and Settings`() {
        // Order matters — BottomTabBar uses ordinal to position the
        // indicator pill. Library is the landing destination (first),
        // Settings sits to the right (second).
        val labels = HomeTab.entries.map { it.label }
        assertEquals(listOf("Library", "Settings"), labels)
        assertEquals(HomeTab.Library, HomeTab.entries.first())
        assertEquals(HomeTab.Settings, HomeTab.entries.last())
    }

    @Test
    fun `Settings hub is a routable destination`() {
        // The Settings pill in the bottom bar navigates to
        // SETTINGS_HUB (the v0.5.38 hub screen with section cards),
        // not the legacy long-scroll SETTINGS page. If anyone wires
        // the pill to SETTINGS directly, the user lands in the wrong
        // place — pin the constant so the route-string survives.
        assertNotNull(StoryvoxRoutes.SETTINGS_HUB)
        assertTrue(
            "SETTINGS_HUB route must be non-empty",
            StoryvoxRoutes.SETTINGS_HUB.isNotEmpty(),
        )
    }

    @Test
    fun `Browse route still exists for deep-link resolution`() {
        // Browse was demoted to a Library sub-tab, but the standalone
        // BROWSE route stays in the nav graph so deep-links (e.g. the
        // HybridReader empty-state "Browse the realms" CTA) keep
        // resolving. If anyone deletes the BROWSE constant, every
        // existing deep-linker silently breaks.
        assertNotNull(StoryvoxRoutes.BROWSE)
        assertEquals("browse", StoryvoxRoutes.BROWSE)
    }

    @Test
    fun `Follows route still exists for deep-link resolution`() {
        // Same demotion + survival contract as BROWSE.
        assertNotNull(StoryvoxRoutes.FOLLOWS)
        assertEquals("follows", StoryvoxRoutes.FOLLOWS)
    }

    @Test
    fun `Library is a home route (bottom nav stays visible)`() {
        // Landing destination — bottom bar visible.
        assertTrue(StoryvoxRoutes.isHome(StoryvoxRoutes.LIBRARY))
    }

    @Test
    fun `Settings hub is a home route (bottom nav stays visible)`() {
        // Second primary destination — bottom bar visible.
        assertTrue(StoryvoxRoutes.isHome(StoryvoxRoutes.SETTINGS_HUB))
    }

    @Test
    fun `Browse and Follows are still home routes for the bottom bar`() {
        // Even though they're no longer bottom-bar *destinations*,
        // BROWSE and FOLLOWS are still "home depth" surfaces — when a
        // deep-link or back-stack push lands the user there, the
        // bottom bar must stay visible so the user can return to
        // Library or jump to Settings. The pill maps both to the
        // Library destination since they belong under the Library
        // umbrella now.
        assertTrue(StoryvoxRoutes.isHome(StoryvoxRoutes.BROWSE))
        assertTrue(StoryvoxRoutes.isHome(StoryvoxRoutes.FOLLOWS))
    }

    @Test
    fun `Reader and Audiobook show the bottom bar`() {
        // Issue #267 — Reader / Audiobook ARE the player surface,
        // reached via drill-down. The bottom bar stays visible there
        // (just like home routes) so the user can switch destinations
        // without backing out of the player. Pinned here so the
        // restructure didn't accidentally drop those entries from the
        // showsBottomNav set.
        assertTrue(StoryvoxRoutes.showsBottomNav(StoryvoxRoutes.READER))
        assertTrue(StoryvoxRoutes.showsBottomNav(StoryvoxRoutes.AUDIOBOOK))
    }

    @Test
    fun `Fiction detail is a drill-down (no bottom bar)`() {
        // Negative case — fiction detail is a stack-push, not a home
        // surface. The bottom bar hides so the user reads the bar's
        // visibility as a "you're at home" signal.
        assertFalse(StoryvoxRoutes.showsBottomNav(StoryvoxRoutes.FICTION_DETAIL))
    }
}
