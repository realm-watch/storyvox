package `in`.jphe.storyvox.ui.theme

import androidx.compose.ui.unit.TextUnitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Font-scale override contract — #486 Phase 2.
 *
 * The Accessibility subscreen exposes a 0.85..1.5× multiplier on top
 * of Android's system font scale. [scaledTypography] is the bridge
 * the theme uses; this test pins its contract.
 */
class TypographyScaleTest {

    @Test
    fun `scale 1 0 returns the singleton typography`() {
        // No-op fast path — Material3's stable-snapshot semantics
        // benefit when the same Typography instance is reused across
        // compositions. The override sites that don't enable scaling
        // get the singleton.
        assertSame(LibraryNocturneTypography, scaledTypography(1.0f))
    }

    @Test
    fun `scale 1 25 multiplies font sizes proportionally`() {
        val base = LibraryNocturneTypography
        val scaled = scaledTypography(1.25f)
        assertNotSame(base, scaled)

        val baseBody = base.bodyMedium.fontSize.value
        val scaledBody = scaled.bodyMedium.fontSize.value
        assertEquals(baseBody * 1.25f, scaledBody, 0.01f)

        val baseLine = base.bodyMedium.lineHeight.value
        val scaledLine = scaled.bodyMedium.lineHeight.value
        assertEquals(baseLine * 1.25f, scaledLine, 0.01f)
    }

    @Test
    fun `scale clamps below 0 85 and above 1 5 to the wider safety range`() {
        // The slider only allows 0.85..1.5; scaledTypography clamps
        // to a wider 0.5..2.5 internal safety range to defend against
        // bad caller input. Anything outside still produces sane
        // output rather than a NaN/zero.
        val scaledLow = scaledTypography(0.1f)
        val scaledHigh = scaledTypography(10f)
        val base = LibraryNocturneTypography
        // 0.1 clamps up to 0.5; bodyMedium 14sp → 7sp.
        assertEquals(base.bodyMedium.fontSize.value * 0.5f, scaledLow.bodyMedium.fontSize.value, 0.01f)
        // 10 clamps down to 2.5; bodyMedium 14sp → 35sp.
        assertEquals(base.bodyMedium.fontSize.value * 2.5f, scaledHigh.bodyMedium.fontSize.value, 0.01f)
    }

    @Test
    fun `scaled typography preserves font family and weight`() {
        val base = LibraryNocturneTypography.bodyLarge
        val scaled = scaledTypography(1.2f).bodyLarge
        assertEquals(base.fontFamily, scaled.fontFamily)
        assertEquals(base.fontWeight, scaled.fontWeight)
        assertEquals(base.letterSpacing, scaled.letterSpacing)
    }

    @Test
    fun `every typography slot scales`() {
        val base = LibraryNocturneTypography
        val scaled = scaledTypography(1.5f)
        val pairs = listOf(
            base.displayLarge to scaled.displayLarge,
            base.displayMedium to scaled.displayMedium,
            base.displaySmall to scaled.displaySmall,
            base.headlineLarge to scaled.headlineLarge,
            base.headlineMedium to scaled.headlineMedium,
            base.headlineSmall to scaled.headlineSmall,
            base.titleLarge to scaled.titleLarge,
            base.titleMedium to scaled.titleMedium,
            base.titleSmall to scaled.titleSmall,
            base.bodyLarge to scaled.bodyLarge,
            base.bodyMedium to scaled.bodyMedium,
            base.bodySmall to scaled.bodySmall,
            base.labelLarge to scaled.labelLarge,
            base.labelMedium to scaled.labelMedium,
            base.labelSmall to scaled.labelSmall,
        )
        for ((b, s) in pairs) {
            if (b.fontSize.type != TextUnitType.Unspecified) {
                assertEquals(
                    "fontSize mismatch on slot with base ${b.fontSize}",
                    b.fontSize.value * 1.5f,
                    s.fontSize.value,
                    0.01f,
                )
            }
        }
    }
}
