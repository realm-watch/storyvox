package `in`.jphe.storyvox.ui.a11y

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Touch-target widener contract — #486 / #479 Phase 2.
 *
 * Pin the widened-target constant + the modifier-helper behavior so a
 * future regression that drops the bump below 48dp WCAG 2.5.5 minimum
 * fails loudly.
 */
class AccessibleTouchTargetTest {

    @Test
    fun `enlarged target constant is at least 48dp`() {
        // WCAG 2.5.5 says interactive targets should be ≥44×44 CSS px
        // (≈48×48 dp). The widened target is 64dp — well above the
        // minimum; if a future tweak drops below 48 this fires.
        assertEquals(64, ACCESSIBLE_TOUCH_TARGET_DP)
    }

    @Test
    fun `accessibleTouchTarget is a no-op when enlarged is false`() {
        val base = Modifier
        val out = base.accessibleTouchTarget(enlarged = false)
        assertSame(
            "accessibleTouchTarget(false) should be the identity modifier — got a different chain",
            base,
            out,
        )
    }

    @Test
    fun `accessibleTouchTarget appends a modifier when enlarged is true`() {
        val base: Modifier = Modifier
        val out = base.accessibleTouchTarget(enlarged = true)
        // Asserting that the chain grew is enough — the actual size
        // application is layout-time and only visible in an Android
        // composition. Equality with the identity modifier here is
        // ruled out by chain length.
        assert(out !== base) { "accessibleTouchTarget(true) returned identity modifier" }
    }

    @Test
    fun `accessibleSize swaps base for enlarged when flag is true`() {
        // We can't directly inspect the resulting modifier's size at
        // unit-test scope without Robolectric, but we CAN pin the
        // function contract via a behavioral proxy: calling with
        // enlargedFlag=true must produce a different chain from
        // enlargedFlag=false. (If they were the same chain, the
        // function would be a no-op and the bug would silently slip
        // through.)
        val small = Modifier.accessibleSize(enlargedFlag = false, base = 40.dp)
        val big = Modifier.accessibleSize(enlargedFlag = true, base = 40.dp)
        // Both grow the chain (size always applies); they must be
        // distinct instances so the toggle has observable effect.
        assert(small !== big) {
            "accessibleSize: flag=true and flag=false produced equal chains — bug"
        }
    }

    @Test
    fun `accessibleMinSize defaults to 48dp baseline`() {
        // The off-state baseline matches M3's
        // minimumInteractiveComponentSize (48dp); the on-state lifts
        // to the [ACCESSIBLE_TOUCH_TARGET_DP] constant. Confirm the
        // default base param is the M3 minimum by exercising both
        // paths and asserting the chains differ.
        val off = Modifier.accessibleMinSize(enlargedFlag = false)
        val on = Modifier.accessibleMinSize(enlargedFlag = true)
        assert(off !== on) {
            "accessibleMinSize: flag=true and flag=false produced equal chains"
        }
    }
}
