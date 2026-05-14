package `in`.jphe.storyvox.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Follow-up to #440 / #467 — pins the seven subscreen composables that
 * were broken out of the legacy long-scroll [SettingsScreen]. The hub
 * catalog ([SettingsHubSections]) still names them; this test verifies
 * the composable function symbols actually exist (load via reflection)
 * so a rename or accidental deletion fails CI rather than silently
 * dropping a nav target.
 *
 * The :feature module ships JVM unit tests only — see the existing
 * [SettingsHubSectionsTest] for the same data-list discipline. Once
 * the project picks up an androidx.compose.ui.test dependency, these
 * pin tests can be upgraded to full Compose smoke tests that assert
 * row presence + back-arrow click → onBack invoked. Until then,
 * reflection-based existence checks are the cheapest regression net.
 */
class SettingsSubscreenContractTest {

    private val pkg = "in.jphe.storyvox.feature.settings"

    /**
     * Compose-emitted functions land on a synthetic class named
     * `<FunctionName>Kt`. Asserting the class loads is the cheapest
     * smoke test for "the composable still exists with this signature";
     * a rename or accidental deletion turns into a ClassNotFoundException.
     */
    private fun assertComposableExists(simpleName: String) {
        val cls = runCatching { Class.forName("$pkg.${simpleName}Kt") }
            .getOrElse { fail("Composable $simpleName not found in $pkg") }
        assertNotNull(cls)
    }

    private fun fail(msg: String): Nothing = throw AssertionError(msg)

    @Test
    fun `VoiceAndPlaybackSettingsScreen composable is present`() {
        assertComposableExists("VoiceAndPlaybackSettingsScreen")
    }

    @Test
    fun `ReadingSettingsScreen composable is present`() {
        assertComposableExists("ReadingSettingsScreen")
    }

    @Test
    fun `PerformanceSettingsScreen composable is present`() {
        assertComposableExists("PerformanceSettingsScreen")
    }

    @Test
    fun `AiSettingsScreen composable is present`() {
        assertComposableExists("AiSettingsScreen")
    }

    @Test
    fun `AccountSettingsScreen composable is present`() {
        assertComposableExists("AccountSettingsScreen")
    }

    @Test
    fun `MemoryPalaceSettingsScreen composable is present`() {
        assertComposableExists("MemoryPalaceSettingsScreen")
    }

    @Test
    fun `AboutSettingsScreen composable is present`() {
        assertComposableExists("AboutSettingsScreen")
    }

    /**
     * The hub still names every section that has a dedicated subscreen
     * (Voice & Playback, Reading, Performance, AI, Account, Memory
     * Palace, About) in [SettingsHubSections]. A misspelling or
     * accidental drop in the catalog would silently bury a card; pin
     * the seven titles here independently of [SettingsHubSectionsTest]
     * so a regression surfaces in the subscreen suite too.
     */
    @Test
    fun `hub catalog still names every section that owns a subscreen`() {
        val owners = listOf(
            "Voice & Playback",
            "Reading",
            "Performance",
            "AI",
            "Account",
            "Memory Palace",
            "About",
        )
        val titles = SettingsHubSections.map { it.title }
        for (owner in owners) {
            assertTrue(
                "Hub catalog dropped '$owner' — its subscreen is now unreachable from the hub",
                titles.contains(owner),
            )
        }
    }

    /**
     * The "All settings" escape hatch still routes to the legacy
     * long-scroll [SettingsScreen]; it's the only remaining destination
     * that doesn't have a dedicated subscreen route. If a future change
     * accidentally removes it, the hub loses its power-user search
     * affordance — pin it explicitly.
     */
    @Test
    fun `All settings escape hatch remains in the hub catalog`() {
        val all = SettingsHubSections.find { it.title == "All settings" }
        assertNotNull(
            "Hub catalog must keep an 'All settings' row routing to the legacy long-scroll page",
            all,
        )
        assertEquals("Every setting on one long page (legacy).", all!!.subtitle)
    }
}
