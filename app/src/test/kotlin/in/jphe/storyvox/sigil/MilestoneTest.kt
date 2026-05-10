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

    @Test fun `qualifies returns true for newer patch`() {
        assertTrue(Milestone.qualifies("0.5.1"))
        assertTrue(Milestone.qualifies("0.5.17"))
    }

    @Test fun `qualifies returns true for newer minor`() {
        assertTrue(Milestone.qualifies("0.6.0"))
        assertTrue(Milestone.qualifies("0.10.0"))
    }

    @Test fun `qualifies returns true for newer major`() {
        assertTrue(Milestone.qualifies("1.0.0"))
        assertTrue(Milestone.qualifies("2.3.4"))
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
        assertTrue(Milestone.qualifies("1.0"))
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
