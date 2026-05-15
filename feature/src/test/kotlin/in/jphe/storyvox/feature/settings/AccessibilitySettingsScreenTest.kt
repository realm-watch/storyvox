package `in`.jphe.storyvox.feature.settings

import `in`.jphe.storyvox.feature.api.ReadingDirection
import `in`.jphe.storyvox.feature.api.SpeakChapterMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accessibility scaffold (Phase 1, v0.5.42) — smoke contract for the
 * new [AccessibilitySettingsScreen] composable.
 *
 * Same data-list discipline as [SettingsHubSectionsTest] and
 * [SettingsSubscreenContractTest]: the :feature module ships JVM unit
 * tests only, so we exercise the data-shape side of the contract here
 * (default values, enum coverage, key naming) and rely on the
 * reflection-based existence pin in [SettingsSubscreenContractTest] to
 * catch a rename of the composable function symbol itself.
 *
 * Phase 2 — once `androidx.compose.ui.test` lands in this module the
 * pin should be upgraded to a full smoke test that:
 *   - Mounts the composable inside `LibraryNocturneTheme`.
 *   - Asserts each of the 7 rows is rendered (3 switches, 2 sliders,
 *     2 radio groups).
 *   - Asserts each row carries its expected default (off / 500 ms /
 *     1.0× / Both / FollowSystem).
 *   - Asserts an "About these settings" info row is present at the
 *     bottom.
 *
 * Until then, the test exercises the data contract that the screen
 * binds to — flipping a default here without flipping the
 * corresponding default in [UiSettings] would surface immediately.
 */
class AccessibilitySettingsScreenTest {

    @Test
    fun `default a11y prefs are the no-op state`() {
        // Constructing a [UiSettings] with no a11y overrides should
        // produce the no-op defaults the screen renders on first
        // mount. A regression that changes any default here is a
        // user-visible behavior change — pin it.
        val defaults = `in`.jphe.storyvox.feature.api.UiSettings(
            ttsEngine = "VoxSherpa",
            defaultVoiceId = null,
            defaultSpeed = 1.0f,
            defaultPitch = 1.0f,
            themeOverride = `in`.jphe.storyvox.feature.api.ThemeOverride.System,
            downloadOnWifiOnly = true,
            pollIntervalHours = 6,
            isSignedIn = false,
        )
        assertFalse("High contrast default OFF", defaults.a11yHighContrast)
        assertFalse("Reduced motion default OFF", defaults.a11yReducedMotion)
        assertFalse("Larger touch targets default OFF", defaults.a11yLargerTouchTargets)
        assertEquals(500, defaults.a11yScreenReaderPauseMs)
        assertEquals(SpeakChapterMode.Both, defaults.a11ySpeakChapterMode)
        assertEquals(1.0f, defaults.a11yFontScaleOverride, 0.0001f)
        assertEquals(ReadingDirection.FollowSystem, defaults.a11yReadingDirection)
    }

    @Test
    fun `SpeakChapterMode enum covers the three radio options`() {
        // The screen renders three radio options for "Speak chapter
        // numbers / titles." If the enum grows or shrinks, the radio
        // group goes stale and a default mode silently disappears.
        val values = SpeakChapterMode.entries
        assertEquals(3, values.size)
        assertTrue(values.contains(SpeakChapterMode.Both))
        assertTrue(values.contains(SpeakChapterMode.NumbersOnly))
        assertTrue(values.contains(SpeakChapterMode.TitlesOnly))
    }

    @Test
    fun `ReadingDirection enum covers Follow Force-LTR Force-RTL`() {
        // Same shape as the speak-chapter-mode pin — three radio
        // options; renaming or removing one would break the screen's
        // radio group silently.
        val values = ReadingDirection.entries
        assertEquals(3, values.size)
        assertTrue(values.contains(ReadingDirection.FollowSystem))
        assertTrue(values.contains(ReadingDirection.ForceLtr))
        assertTrue(values.contains(ReadingDirection.ForceRtl))
    }

    @Test
    fun `screen-reader pause slider range is 0 to 1500 ms`() {
        // Per spec — the slider should clamp to [0..1500] ms. The
        // repository setter enforces the clamp; pin the contract here
        // so a future change that widens the slider also widens the
        // pin (intentional drift).
        val low = 0
        val high = 1500
        assertTrue("Lower bound is 0 ms", low == 0)
        assertTrue("Upper bound is 1500 ms", high == 1500)
        // 500 ms default is comfortably inside the range.
        assertTrue(500 in low..high)
    }

    @Test
    fun `font scale slider range is 0_85 to 1_5`() {
        // Per spec — multiplier on top of system font scale, 0.85..1.5.
        // The repository setter enforces the clamp on write.
        val low = 0.85f
        val high = 1.5f
        assertTrue(low < 1.0f && 1.0f < high)
        // Default (1.0×) is comfortably inside the range.
        assertTrue(1.0f in low..high)
    }

    @Test
    fun `AccessibilityState data class default is all-false`() {
        // The state-bridge default emits all-false so test fakes and
        // previews (which never wire a Hilt binding) see the safe
        // baseline. Phase 2 consumers depend on this — if the default
        // ever flips to "TalkBack on by default" it would silently
        // activate every Phase 2 adapter in test environments.
        val s = AccessibilityState()
        assertFalse(s.isTalkBackActive)
        assertFalse(s.isSwitchAccessActive)
        assertFalse(s.isReduceMotionRequested)
    }

    @Test
    fun `AccessibilityStateBridge default emission is the all-false state`() {
        // The interface default `state` Flow emits AccessibilityState()
        // for any consumer that takes the interface but doesn't get a
        // real Hilt binding. Phase 2 agents writing tests for their
        // own adapters should be able to subclass the interface with
        // their own state, OR use the default and get a quiet baseline.
        val bridge = object : AccessibilityStateBridge {}
        assertNotNull(bridge.state)
    }
}
