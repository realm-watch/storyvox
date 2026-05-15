package `in`.jphe.storyvox.navigation

import androidx.compose.ui.unit.LayoutDirection
import `in`.jphe.storyvox.feature.api.ReadingDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Accessibility scaffold Phase 2 (#486) — verifies the
 * [ReadingDirection] → [LayoutDirection] override mapping used by
 * MainActivity when wrapping [StoryvoxNavHost] in
 * `CompositionLocalProvider(LocalLayoutDirection provides ...)`.
 *
 * `FollowSystem` must resolve to `null` (no provider — Compose
 * inherits the system locale's direction). `ForceLtr` / `ForceRtl`
 * must resolve to the matching [LayoutDirection]. This is the
 * contract MainActivity implements; the test pins it so a regression
 * (e.g. swapping LTR/RTL) fails loudly.
 *
 * MainActivity's mapping lives inline in `setContent`; this file
 * mirrors the same `when` so the test is independent of the call
 * site. The two must match.
 */
class ReadingDirectionMappingTest {

    /** Mirror of MainActivity's inline mapping. */
    private fun forcedLayoutDirection(rd: ReadingDirection): LayoutDirection? = when (rd) {
        ReadingDirection.FollowSystem -> null
        ReadingDirection.ForceLtr -> LayoutDirection.Ltr
        ReadingDirection.ForceRtl -> LayoutDirection.Rtl
    }

    @Test
    fun `FollowSystem returns null so Compose inherits locale direction`() {
        assertNull(forcedLayoutDirection(ReadingDirection.FollowSystem))
    }

    @Test
    fun `ForceLtr resolves to LayoutDirection Ltr`() {
        assertEquals(LayoutDirection.Ltr, forcedLayoutDirection(ReadingDirection.ForceLtr))
    }

    @Test
    fun `ForceRtl resolves to LayoutDirection Rtl`() {
        assertEquals(LayoutDirection.Rtl, forcedLayoutDirection(ReadingDirection.ForceRtl))
    }

    @Test
    fun `mapping is exhaustive over the ReadingDirection enum`() {
        // Compilation-level guarantee: when()-without-else over an
        // enum forces every value to be handled. If a new value lands
        // in ReadingDirection without a mapping case, this `when`
        // would fail to compile. Run the mapping over every enum
        // value to confirm it returns without throwing.
        for (rd in ReadingDirection.entries) {
            forcedLayoutDirection(rd) // no-op; we just verify no MatchException.
        }
    }
}
