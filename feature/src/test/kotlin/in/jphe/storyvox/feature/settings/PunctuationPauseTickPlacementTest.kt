package `in`.jphe.storyvox.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [computeTickLabelX], the pure placement helper that powers
 * `PunctuationPauseTickLabels`. Issue #139 — labels visibly drifted right
 * because the previous `Row(weight)` layout treats label widths as a
 * fixed-takeout from the row, leaving weights to divvy up only the
 * *remaining* space. These tests guarantee the new placement math anchors
 * each label to its true value-fraction along the slider track.
 */
class PunctuationPauseTickPlacementTest {

    /**
     * At fraction 0 (value = MIN_MULTIPLIER, "Off"), the label's left edge
     * sits at trackPadding — the same x-pixel where the slider thumb sits
     * when fully left.
     */
    @Test
    fun `leftmost tick anchored at track padding, not parent left edge`() {
        val x = computeTickLabelX(
            rowWidthPx = 600,
            trackPaddingPx = 10,
            fraction = 0f,
            labelWidthPx = 50,
            isLast = false,
        )
        assertEquals(10, x)
    }

    /**
     * Middle tick at fraction 0.25 (value = 1×) sits at 10 + 0.25 × 580 = 155.
     * This is the fix for the symptom JP reported: previously "▲ 1×"
     * appeared at slider position ~1.09× because cumulative label widths
     * pushed it right of fraction 0.25.
     */
    @Test
    fun `middle tick at value-fraction along the track, not the row`() {
        val x = computeTickLabelX(
            rowWidthPx = 600,
            trackPaddingPx = 10,
            fraction = 0.25f, // value = 1× in 0..4 range
            labelWidthPx = 40,
            isLast = false,
        )
        assertEquals(10 + (0.25f * 580f).toInt(), x) // 155
    }

    /**
     * "Long" at value 1.75 in a 0..4 range: fraction 0.4375.
     * Track-x = 10 + 0.4375 × 580 = 263.75 → 263 (Int).
     * Hazel reported this label appearing at "1.92×" before the fix —
     * (label drifted ~10% right, consistent with cumulative label width).
     */
    @Test
    fun `long tick lands at fraction 0_4375 on a 600px parent`() {
        val x = computeTickLabelX(
            rowWidthPx = 600,
            trackPaddingPx = 10,
            fraction = 1.75f / 4f,
            labelWidthPx = 60,
            isLast = false,
        )
        // 10 + (0.4375 × 580).toInt() = 10 + 253 = 263
        assertEquals(10 + (0.4375f * 580f).toInt(), x)
    }

    /**
     * The rightmost tick is right-aligned so its text doesn't overflow
     * the parent's right edge. The visual ▲ then sits slightly right of
     * the track end, which matches the thumb's visual extent (the thumb
     * is a circle that extends half its width past the track end).
     */
    @Test
    fun `rightmost tick is right-aligned to parent edge`() {
        val x = computeTickLabelX(
            rowWidthPx = 600,
            trackPaddingPx = 10,
            fraction = 1f,
            labelWidthPx = 50,
            isLast = true,
        )
        assertEquals(550, x) // 600 - 50
    }

    /**
     * Defensive: a wide middle label that would otherwise overflow the
     * right edge gets clamped. Prevents text from being cut off if a
     * future locale's "Long" translation is unusually wide.
     */
    @Test
    fun `middle tick clamps when label would overflow the right edge`() {
        val x = computeTickLabelX(
            rowWidthPx = 200,
            trackPaddingPx = 10,
            fraction = 0.95f,
            labelWidthPx = 80,
            isLast = false,
        )
        // anchorX = 10 + (0.95 × 180).toInt() = 10 + 171 = 181
        // clamp = (200 - 80) = 120
        assertEquals(120, x)
    }

    /**
     * Defensive: a fraction outside [0, 1] is clamped so the label stays
     * within the parent. Should never happen given the [0..1] derivation
     * upstream, but cheap to guarantee.
     */
    @Test
    fun `fraction below zero clamps to track padding`() {
        val x = computeTickLabelX(
            rowWidthPx = 600,
            trackPaddingPx = 10,
            fraction = -0.5f,
            labelWidthPx = 40,
            isLast = false,
        )
        assertEquals(10, x)
    }

    /**
     * Sanity check that the four real tick fractions (Off/1×/Long/4×) for
     * the punctuation cadence slider produce monotonically increasing
     * positions — i.e., labels don't visually re-order or overlap-collide
     * on a typical tablet width.
     */
    @Test
    fun `four real ticks monotonically increase across a 600px parent`() {
        val labelWidth = 40
        val xs = listOf(0f, 0.25f, 0.4375f, 1f).mapIndexed { i, frac ->
            computeTickLabelX(
                rowWidthPx = 600,
                trackPaddingPx = 10,
                fraction = frac,
                labelWidthPx = labelWidth,
                isLast = i == 3,
            )
        }
        for (i in 1 until xs.size) {
            assertTrue(
                "x[$i]=${xs[i]} should be > x[${i - 1}]=${xs[i - 1]}",
                xs[i] > xs[i - 1],
            )
        }
    }
}
