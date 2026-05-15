package `in`.jphe.storyvox.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * High-contrast theme contract — #486 Phase 2.
 *
 * Pin the resolved color tokens AND assert body-text contrast clears
 * AAA on both the dark and light variants. The audit's WCAG findings
 * (`scratch/a11y-audit/findings/05-contrast.txt`) used the same sRGB
 * relative-luminance math; we reproduce a compact version here so the
 * test is self-contained.
 *
 * If the high-contrast palette is tweaked, this test will fail
 * loudly so the tweak is intentional. Any change that drops body-text
 * contrast below 7:1 (WCAG AAA) breaks the contract.
 */
class HighContrastThemeTest {

    @Test
    fun `dark variant pins brass-on-near-black tokens`() {
        // JP's design call: backgrounds #1a1410 → #000000, brass
        // #b88746 → #ffc14a. These are the values shipped.
        assertEquals(0xFF000000.toInt(), HighContrastDarkColors.surface.toArgb())
        assertEquals(0xFFFFC14A.toInt(), HighContrastDarkColors.primary.toArgb())
        assertEquals(0xFFFFFFFF.toInt(), HighContrastDarkColors.onSurface.toArgb())
    }

    @Test
    fun `light variant pins brass-on-pure-white tokens`() {
        assertEquals(0xFFFFFFFF.toInt(), HighContrastLightColors.surface.toArgb())
        assertEquals(0xFF000000.toInt(), HighContrastLightColors.onSurface.toArgb())
    }

    @Test
    fun `dark body text clears AAA on every surface container tier`() {
        val onSurface = HighContrastDarkColors.onSurface
        val surfaces = listOf(
            HighContrastDarkColors.surface,
            HighContrastDarkColors.surfaceContainerLow,
            HighContrastDarkColors.surfaceContainer,
            HighContrastDarkColors.surfaceContainerHigh,
            HighContrastDarkColors.surfaceContainerHighest,
        )
        for (bg in surfaces) {
            val ratio = contrastRatio(onSurface, bg)
            // AAA-normal threshold is 7:1. Brass-on-near-black easily
            // exceeds this — the audit cited ~14:1+ for the default
            // theme's body text, and the high-contrast variant is
            // designed to be even tighter at the brightest surfaces.
            assertTrue(
                "Dark body text contrast ${"%.2f".format(ratio)}:1 < AAA 7:1 on $bg",
                ratio >= 7.0,
            )
        }
    }

    @Test
    fun `light body text clears AAA on every surface container tier`() {
        val onSurface = HighContrastLightColors.onSurface
        val surfaces = listOf(
            HighContrastLightColors.surface,
            HighContrastLightColors.surfaceContainerLow,
            HighContrastLightColors.surfaceContainer,
            HighContrastLightColors.surfaceContainerHigh,
            HighContrastLightColors.surfaceContainerHighest,
        )
        for (bg in surfaces) {
            val ratio = contrastRatio(onSurface, bg)
            assertTrue(
                "Light body text contrast ${"%.2f".format(ratio)}:1 < AAA 7:1 on $bg",
                ratio >= 7.0,
            )
        }
    }

    @Test
    fun `default-palette plum AA fix lifts plum500 above 4 dot 5 on dark surface`() {
        // #477 — plum500 was #7A5A7A (3.31:1, FAIL). v0.5.43 ships
        // #A582A5 to clear AA-normal (4.5:1) on the dark surface.
        val ratio = contrastRatio(PlumRamp.Plum500, SurfaceTokens.SurfaceDark)
        assertTrue(
            "plum500 contrast ${"%.2f".format(ratio)}:1 < AA 4.5:1 — #477 regressed",
            ratio >= 4.5,
        )
    }

    @Test
    fun `default-palette outlineVariant dark clears 3 to 1 non-text minimum`() {
        // #477 — outlineVariant dark was #3A3530 (1.60:1, FAIL WCAG 1.4.11).
        // v0.5.43 lifts to #5A5248. Target: ≥3:1.
        val ratio = contrastRatio(SurfaceTokens.OutlineVariantDark, SurfaceTokens.SurfaceDark)
        assertTrue(
            "outlineVariant dark ${"%.2f".format(ratio)}:1 < 3:1 — #477 regressed",
            ratio >= 3.0,
        )
    }

    @Test
    fun `default-palette outlineVariant light clears 3 to 1 non-text minimum`() {
        val ratio = contrastRatio(SurfaceTokens.OutlineVariantLight, SurfaceTokens.SurfaceLight)
        assertTrue(
            "outlineVariant light ${"%.2f".format(ratio)}:1 < 3:1 — #477 regressed",
            ratio >= 3.0,
        )
    }

    @Test
    fun `default-palette errorContainer dark passes AA against error text`() {
        // #477 — errorContainer dark was #5A2A22 against #E07A6A error =
        // 4.00:1 (FAIL). v0.5.43 darkens container to #3A1A14, lifting
        // the pair to ≥4.5:1.
        val ratio = contrastRatio(StatusTokens.ErrorDark, StatusTokens.ErrorContainerDark)
        assertTrue(
            "errorContainer dark ${"%.2f".format(ratio)}:1 < AA 4.5:1 — #477 regressed",
            ratio >= 4.5,
        )
    }

    // ─── WCAG 2.1 relative-luminance math ──────────────────────────

    private fun Color.toArgb(): Int {
        val r = (red * 255).toInt() and 0xFF
        val g = (green * 255).toInt() and 0xFF
        val b = (blue * 255).toInt() and 0xFF
        val a = (alpha * 255).toInt() and 0xFF
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** WCAG 2.1 contrast ratio between two opaque sRGB colors. */
    private fun contrastRatio(fg: Color, bg: Color): Double {
        val lFg = relativeLuminance(fg)
        val lBg = relativeLuminance(bg)
        val lighter = max(lFg, lBg)
        val darker = min(lFg, lBg)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(c: Color): Double {
        fun linearize(channel: Float): Double {
            val v = channel.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linearize(c.red) +
            0.7152 * linearize(c.green) +
            0.0722 * linearize(c.blue)
    }
}
