package `in`.jphe.storyvox.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #527 follow-up — chip-preset rows in [VoiceQuickSheetContent].
 *
 * The quick sheet's continuous-tuning controls (Speed / Pitch /
 * Sentence silence) were converted from sliders to chip-preset rows
 * plus a "Custom…" toggle that re-reveals the slider. These tests
 * pin the preset sets, the selection epsilons, and the label
 * formatters so a future refactor that scrambles the chip layout
 * has to update the test + acknowledge the UX intent.
 *
 * Pure-logic, JVM-only. The chip composables themselves aren't
 * rendered here (feature/build.gradle.kts uses
 * `isReturnDefaultValues = true`); the contract under test is the
 * presets + selection logic.
 */
class VoiceQuickSheetChipPresetsTest {

    // ── Pitch chips ────────────────────────────────────────────────

    /**
     * Pitch presets bracket the engine's 0.6×..1.4× Sonic-musical
     * zone. Five anchors: two warm/deeper, neutral, two bright/higher.
     * 0.15 spacing keeps adjacent presets audibly distinct (the JND
     * threshold for pitch shifts on narrator voice in informal A/B
     * is ≈ 0.05-0.08; 0.15 keeps every step a clearly-different read).
     */
    @Test
    fun `pitch presets cover the Sonic musical zone with five anchors`() {
        assertEquals(
            listOf(0.7f, 0.85f, 1.0f, 1.15f, 1.3f),
            PITCH_PRESETS,
        )
    }

    @Test
    fun `pitch preset exactly matches selects`() {
        assertTrue(isPitchPresetSelected(1.0f, 1.0f))
        assertTrue(isPitchPresetSelected(0.7f, 0.7f))
        assertTrue(isPitchPresetSelected(1.3f, 1.3f))
    }

    @Test
    fun `pitch preset selection tolerates float rounding`() {
        // The continuous slider's floats can land at 1.150001f from
        // ribbon-fine drags; the chip must still flash selected.
        assertTrue(isPitchPresetSelected(1.1500001f, 1.15f))
        assertTrue(isPitchPresetSelected(0.99995f, 1.0f))
    }

    @Test
    fun `pitch preset selection rejects clearly different pitches`() {
        assertFalse(isPitchPresetSelected(1.0f, 1.15f))
        assertFalse(isPitchPresetSelected(0.8f, 0.85f))
        assertFalse(isPitchPresetSelected(1.4f, 1.3f))
    }

    /**
     * Adjacent presets are 0.15 apart (well above the 0.02 epsilon),
     * so a pitch between 1.0 and 1.15 shouldn't flash both chips.
     * Pinning so future preset spacing tweaks can't silently break.
     */
    @Test
    fun `pitch preset selection never flashes adjacent presets together`() {
        val between = 1.075f
        val matches = PITCH_PRESETS.count { isPitchPresetSelected(between, it) }
        assertEquals(
            "Pitch at 1.075 should not be epsilon-close to ANY preset — " +
                "if this fails, either epsilon is too generous or two presets " +
                "are too close together.",
            0, matches,
        )
    }

    @Test
    fun `format pitch preset strips trailing zeroes from whole numbers`() {
        assertEquals("1×", formatPitchPreset(1.0f))
    }

    @Test
    fun `format pitch preset keeps two decimals for fractional presets`() {
        assertEquals("0.7×", formatPitchPreset(0.7f))
        assertEquals("0.85×", formatPitchPreset(0.85f))
        assertEquals("1.15×", formatPitchPreset(1.15f))
        assertEquals("1.3×", formatPitchPreset(1.3f))
    }

    // ── Sentence-silence chips ─────────────────────────────────────

    /**
     * Cadence presets cover the practical 0×..3× listening range.
     * The slider's 4× extreme is reachable via "Custom…" for users
     * who want extra-slow narrator cadence. Five anchors:
     * 0× (speedrun) / 0.5× (brisk) / 1× (default) / 2× (slow) /
     * 3× (a11y / language-learning).
     */
    @Test
    fun `cadence presets cover the practical listening range with five anchors`() {
        assertEquals(
            listOf(0f, 0.5f, 1f, 2f, 3f),
            PUNCTUATION_PAUSE_PRESETS,
        )
    }

    @Test
    fun `cadence preset exactly matches selects`() {
        assertTrue(isPunctuationPausePresetSelected(0f, 0f))
        assertTrue(isPunctuationPausePresetSelected(1f, 1f))
        assertTrue(isPunctuationPausePresetSelected(3f, 3f))
    }

    @Test
    fun `cadence preset selection tolerates float rounding`() {
        assertTrue(isPunctuationPausePresetSelected(1.001f, 1f))
        assertTrue(isPunctuationPausePresetSelected(0.499f, 0.5f))
    }

    @Test
    fun `cadence preset selection rejects clearly different multipliers`() {
        // The 0.5×→1× gap is the tightest at 0.5; epsilon 0.05 means
        // 1.4 vs 1× is clearly distinct.
        assertFalse(isPunctuationPausePresetSelected(1.4f, 1f))
        assertFalse(isPunctuationPausePresetSelected(2.5f, 3f))
    }

    /**
     * The 0.5×→1× gap is the tightest at 0.5; a value at 0.75 should
     * not flash either preset. Pinning so widening the epsilon (or
     * tightening preset spacing) surfaces the regression.
     */
    @Test
    fun `cadence preset selection never flashes adjacent presets together`() {
        val between = 0.75f
        val matches = PUNCTUATION_PAUSE_PRESETS.count {
            isPunctuationPausePresetSelected(between, it)
        }
        assertEquals(
            "Cadence at 0.75 should not be epsilon-close to ANY preset.",
            0, matches,
        )
    }

    @Test
    fun `format cadence preset strips trailing zeroes from whole numbers`() {
        assertEquals("0×", formatPunctuationPausePreset(0f))
        assertEquals("1×", formatPunctuationPausePreset(1f))
        assertEquals("2×", formatPunctuationPausePreset(2f))
        assertEquals("3×", formatPunctuationPausePreset(3f))
    }

    @Test
    fun `format cadence preset keeps the half-step decimal`() {
        assertEquals("0.5×", formatPunctuationPausePreset(0.5f))
    }

    // ── Cross-row pref-key contract ────────────────────────────────

    /**
     * The chip-preset path must write to the same callback the slider
     * used — chip taps invoke `onSetSpeed` / `onSetPitch` /
     * `onSetPunctuationPause` exactly like the slider's onValueChange
     * did. This test pins the contract by asserting the speed presets
     * are floats (not Int — engine API is Float) so a refactor that
     * accidentally normalises them to Int would break the engine
     * `setSpeed(Float)` call site silently.
     */
    @Test
    fun `chip preset types match the engine callbacks they invoke`() {
        // The presets are List<Float> — same type the slider's
        // onValueChange emits. If this fails the chip rows can't
        // call onSetSpeed/Pitch/Pause without an implicit conversion.
        val speedSample: Float = SPEED_PRESETS.first()
        val pitchSample: Float = PITCH_PRESETS.first()
        val pauseSample: Float = PUNCTUATION_PAUSE_PRESETS.first()
        // Compile-time check is the real test; runtime asserts they're
        // real floats (not boxed Number).
        assertEquals(0.75f, speedSample, 0.0001f)
        assertEquals(0.7f, pitchSample, 0.0001f)
        assertEquals(0f, pauseSample, 0.0001f)
    }

    /**
     * Pitch row's "neutral" preset must be 1.0×. The slider in the
     * pre-chip world had a "▲ 1×" tick anchor in SettingsScreen
     * (#273) — keeping 1.0 as the chip's neutral preserves the
     * established "tap once to return to default" pattern.
     */
    @Test
    fun `pitch presets include 1× as the neutral anchor`() {
        assertTrue(
            "Pitch presets must include 1.0 as the neutral anchor; " +
                "users expect a one-tap path back to neutral pitch.",
            PITCH_PRESETS.any { kotlin.math.abs(it - 1.0f) < 0.0001f },
        )
    }

    /**
     * Cadence row's "default" preset must be 1.0× — matches the
     * legacy slider's audiobook-tuned default. Lose this and users
     * who tap the "Default" chip get a different cadence than the
     * one Settings displays as the natural value.
     */
    @Test
    fun `cadence presets include 1× as the default anchor`() {
        assertTrue(
            "Cadence presets must include 1.0 as the audiobook default.",
            PUNCTUATION_PAUSE_PRESETS.any { kotlin.math.abs(it - 1.0f) < 0.0001f },
        )
    }
}
