package `in`.jphe.storyvox.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Follow-up to #440 / #467 — pins the seven new subscreen route
 * constants and the rules they collectively follow:
 *
 *  - Every settings subscreen route starts with `settings/`. The
 *    NavHost routing them is prefix-agnostic, but the prefix is a
 *    semantic anchor that downstream code (deep-link parsers,
 *    analytics URI mapping, log-grep) leans on. Drift would break
 *    those silently.
 *  - Routes are unique. Two routes resolving to the same string
 *    would shadow each other in the NavHost.
 *  - The legacy [StoryvoxRoutes.SETTINGS] page is preserved as an
 *    "All settings" escape hatch and stays reachable. The hub's
 *    "All settings" row routes there explicitly.
 */
class SettingsSubscreenRoutesTest {

    private val newRoutes = listOf(
        StoryvoxRoutes.SETTINGS_VOICE_PLAYBACK,
        StoryvoxRoutes.SETTINGS_READING,
        StoryvoxRoutes.SETTINGS_PERFORMANCE,
        StoryvoxRoutes.SETTINGS_AI,
        StoryvoxRoutes.SETTINGS_ACCOUNT,
        StoryvoxRoutes.SETTINGS_MEMORY_PALACE,
        StoryvoxRoutes.SETTINGS_ABOUT,
    )

    @Test
    fun `seven new subscreen routes are declared`() {
        assertEquals(7, newRoutes.size)
    }

    @Test
    fun `every settings subscreen route uses the settings prefix`() {
        for (route in newRoutes) {
            assertTrue(
                "Route '$route' must start with 'settings/' so deep-link/log " +
                    "parsers can pin the settings scope by prefix.",
                route.startsWith("settings/"),
            )
        }
    }

    @Test
    fun `new subscreen routes are unique`() {
        val duplicates = newRoutes.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue(
            "Settings subscreen route duplication: $duplicates",
            duplicates.isEmpty(),
        )
    }

    @Test
    fun `new subscreen routes do not collide with pre-existing settings routes`() {
        val preExisting = setOf(
            StoryvoxRoutes.SETTINGS_HUB,
            StoryvoxRoutes.SETTINGS,
            StoryvoxRoutes.SETTINGS_PRONUNCIATION,
            StoryvoxRoutes.VOICE_LIBRARY,
            StoryvoxRoutes.SETTINGS_AI_SESSIONS,
            StoryvoxRoutes.SETTINGS_DEBUG,
            StoryvoxRoutes.SETTINGS_PLUGINS,
        )
        for (route in newRoutes) {
            assertTrue(
                "New subscreen route '$route' collides with a pre-existing settings route.",
                route !in preExisting,
            )
        }
    }

    @Test
    fun `legacy SETTINGS page is still reachable for the All settings escape hatch`() {
        assertEquals("settings", StoryvoxRoutes.SETTINGS)
    }

    @Test
    fun `SETTINGS_HUB stays the gear-icon destination`() {
        assertEquals("settings/hub", StoryvoxRoutes.SETTINGS_HUB)
    }
}
