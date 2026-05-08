package `in`.jphe.storyvox.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [computeTickLabelX] applied to the **buffer slider**'s
 * single recommended-max tick (`▲ 64` at fraction 64/(1500−2)). The
 * legacy `TickMarker` used a [androidx.compose.foundation.layout.Row] of
 * weight-spacers, which had the same intrinsic-width-leaks-into-position
 * flaw Tessa fixed for the punctuation slider in #146 — at small tick
 * fractions the drift was subpixel-ish on tablet widths but still wrong
 * in principle, and at higher fractions or smaller parent widths it
 * would visibly diverge from the slider thumb.
 *
 * After Lyra's [SliderTickLabels] extraction, both sliders share the
 * thumb-aligned placement math. These tests pin the buffer-slider
 * single-tick contract so a future TickMarker tweak doesn't silently
 * regress.
 *
 * The right-align rule (`isLast = true`) is suppressed inside
 * SliderTickLabels when the tick list has only one entry — there's
 * no preceding label whose width could push the rightmost off the
 * edge, so a 1-tick row anchors at the thumb-x like a middle tick.
 *
 * Mirrors [PunctuationPauseTickPlacementTest]'s shape — pure math, no
 * Compose runtime, JVM-only.
 */
class BufferTickPlacementTest {

    /**
     * The buffer slider's recommended-max marker is a single tick at
     * fraction (64 − 2) / (1500 − 2) ≈ 0.04138. The label needs to sit
     * at trackPaddingPx + fraction × (parentWidth − 2 × trackPaddingPx),
     * matching the M3 Slider thumb's position at value 64 — NOT at
     * row-x = fraction × parentWidth (the legacy weight-spacer behavior,
     * which leaked label width into placement).
     */
    @Test
    fun `buffer recommended-max tick anchored at thumb x not raw row fraction`() {
        // BUFFER_MIN_CHUNKS = 2, BUFFER_RECOMMENDED_MAX_CHUNKS = 64,
        // BUFFER_MAX_CHUNKS = 1500. fraction = (64 - 2) / (1500 - 2).
        val fraction = 62f / 1498f
        val x = computeTickLabelX(
            rowWidthPx = 600,
            trackPaddingPx = 10,
            fraction = fraction,
            labelWidthPx = 40,
            isLast = false,
        )
        // 10 + (fraction × 580).toInt()
        val expected = 10 + (fraction * 580f).toInt()
        assertEquals(expected, x)
    }

    /**
     * For the buffer slider TickMarker case, SliderTickLabels passes
     * `isLast = false` (because `ticks.size > 1 && i == lastIndex` is
     * false when size == 1), so even a fraction-1 single tick stays
     * thumb-anchored. This is the seam that makes a single-tick
     * SliderTickLabels caller render at fraction-x instead of pinning
     * to the right edge.
     */
    @Test
    fun `single tick at fraction 1 with isLast=false anchors at track end`() {
        val x = computeTickLabelX(
            rowWidthPx = 600,
            trackPaddingPx = 10,
            fraction = 1f,
            labelWidthPx = 40,
            isLast = false,
        )
        // anchorX = 10 + (1.0 × 580).toInt() = 590
        // clamped to (600 - 40) = 560 so label doesn't overflow right
        assertEquals(560, x)
    }

    /**
     * Realistic buffer-slider value (1347 chunks, the slider max
     * observed on Hazel's tablet) — verifies the thumb-anchored
     * placement holds at high-fraction values too. Even when fraction
     * approaches 1, the label clamps to (rowWidth − labelWidth) so
     * the trailing characters stay on screen.
     */
    @Test
    fun `high-fraction single tick clamps to keep label on screen`() {
        // Hypothetical 1347-chunk tick: (1347 - 2) / (1500 - 2) ≈ 0.898.
        val fraction = (1347f - 2f) / (1500f - 2f)
        val x = computeTickLabelX(
            rowWidthPx = 600,
            trackPaddingPx = 10,
            fraction = fraction,
            labelWidthPx = 50, // "▲ 1347" is wider
            isLast = false,
        )
        // anchorX = 10 + (0.898 × 580).toInt() = 10 + 521 = 531
        // (600 - 50) = 550 floor → 531 fits, no clamp.
        val expected = 10 + (fraction * 580f).toInt()
        assertEquals(expected, x)
    }
}
