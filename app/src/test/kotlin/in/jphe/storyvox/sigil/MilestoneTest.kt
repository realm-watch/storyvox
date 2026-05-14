package `in`.jphe.storyvox.sigil

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Calliope (v0.5.00) — version-string gate semantics for the
 * milestone celebration. The gate is fail-closed: any unparseable
 * input returns false so a dev / pre-release sigil never
 * accidentally pops the dialog.
 */
class MilestoneTest {

    @Test fun `qualifies returns true for exact v0_5_00`() {
        assertTrue(Milestone.qualifies("0.5.00"))
    }

    @Test fun `qualifies returns true for newer patch inside the window`() {
        assertTrue(Milestone.qualifies("0.5.1"))
        assertTrue(Milestone.qualifies("0.5.5"))
    }

    // #439 — celebration window closes after v0.5.5. Anyone fresh-
    // installing on a later build never lived through the crossing,
    // so the dialog (which says "storyvox 0.5.00") would read as a
    // dead placeholder. Window-end bound silently retires the dialog
    // for fresh installs on later builds.
    @Test fun `qualifies returns false past the celebration window`() {
        assertFalse(Milestone.qualifies("0.5.17"))
        assertFalse(Milestone.qualifies("0.5.36"))
        assertFalse(Milestone.qualifies("0.6.0"))
        assertFalse(Milestone.qualifies("0.10.0"))
        assertFalse(Milestone.qualifies("1.0.0"))
        assertFalse(Milestone.qualifies("2.3.4"))
    }

    @Test fun `qualifies returns false for prior v0_4_x`() {
        assertFalse(Milestone.qualifies("0.4.97"))
        assertFalse(Milestone.qualifies("0.4.16"))
        assertFalse(Milestone.qualifies("0.4.0"))
    }

    @Test fun `qualifies returns false for prior v0_3_x`() {
        assertFalse(Milestone.qualifies("0.3.99"))
    }

    @Test fun `qualifies tolerates two-part version names`() {
        assertTrue(Milestone.qualifies("0.5"))
        assertFalse(Milestone.qualifies("1.0")) // past window end
        assertFalse(Milestone.qualifies("0.4"))
    }

    @Test fun `qualifies fails closed on garbage input`() {
        assertFalse(Milestone.qualifies(""))
        assertFalse(Milestone.qualifies("dev"))
        assertFalse(Milestone.qualifies("foo.bar.baz"))
    }

    @Test fun `qualifies strips pre-release suffixes`() {
        // 0.5.00-rc1 / 0.5.00+sha — split on `.`/`-`/`+` and compare
        // the first three integer triples.
        assertTrue(Milestone.qualifies("0.5.0-rc1"))
        assertTrue(Milestone.qualifies("0.5.0+abcdef"))
        assertFalse(Milestone.qualifies("0.4.97-rc1"))
    }
}
