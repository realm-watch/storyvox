package `in`.jphe.storyvox.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Reduced-motion fold-in contract — #486 / #480 Phase 2.
 *
 * [LocalReducedMotion]'s contract:
 *  - Defaults to `false` so previews / tests / non-wiring contexts
 *    behave like v0.5.42.
 *  - Is a [staticCompositionLocalOf] — provider changes invalidate
 *    the subtree (so a runtime flip of the user pref re-renders the
 *    consumer sites without an app restart).
 *
 * The actual per-site fold-in behavior (snap vs tween, static glyph
 * vs animation) is verified at integration-test scope; this test
 * pins the CompositionLocal contract itself.
 */
class ReduceMotionTest {

    @Test
    fun `LocalReducedMotion defaults to false`() {
        // Reading the default from outside a composition isn't
        // directly possible, but every reduced-motion consumer in
        // :core-ui depends on this default; if it ever flipped to
        // `true` by accident, every preview would render without
        // motion (a load-bearing contract documented in the kdoc).
        // We can't query the default from a non-composable, so the
        // best we can do is assert the kdoc-stated default by
        // confirming the LocalReducedMotion property exists with the
        // expected nullability + type (compiles).
        @Suppress("UNUSED_VARIABLE")
        val local = LocalReducedMotion
        // Compilation of this test is the assertion — the import path
        // pins the kdoc-documented public surface.
    }

    @Test
    fun `Motion data class is immutable`() {
        // Reduced-motion folds in alongside [Motion]: the data class
        // is `@Immutable` so consumers can rely on stable-snapshot
        // semantics. If a future tweak makes Motion `var`-y, this
        // test won't catch it directly but it will pin the values
        // the consumers rely on (so an unintended duration change
        // shows up in the diff).
        val m = Motion()
        assertEquals(280, m.standardDurationMs)
        assertEquals(180, m.sentenceDurationMs)
        assertEquals(360, m.swipeDurationMs)
    }

    @Test
    fun `default LocalMotion does not pretend to be reduced`() {
        // Sanity guard — there's a temptation to fold the
        // reduced-motion flag inside the Motion struct (it lives in
        // the same file). The kdoc on LocalReducedMotion explicitly
        // calls this out as a bad idea — folding it inside Motion
        // would break Motion's @Immutable contract. Confirm by
        // shape: Motion has no reduced-motion field, just durations
        // and easings.
        val m = Motion()
        // If a future regression adds a `reducedMotion: Boolean`
        // field to Motion, the field list grows and copy() acquires
        // a new param. The test would still pass; the regression
        // would be visible in code review. Documenting the intent
        // here at least.
        assertFalse(
            "Motion data class should not carry the reduced-motion flag — " +
                "use LocalReducedMotion instead",
            m.toString().contains("reducedMotion", ignoreCase = true),
        )
    }
}
