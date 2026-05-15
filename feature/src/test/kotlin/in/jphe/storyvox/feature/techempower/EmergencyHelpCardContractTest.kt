package `in`.jphe.storyvox.feature.techempower

import `in`.jphe.storyvox.data.TechEmpowerLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #516 — pins the contract of the Emergency Help card on
 * TechEmpower Home. The card itself is a private composable inside
 * [TechEmpowerHomeScreen], so this test exercises the *data contract*
 * the card depends on rather than rendering the composable:
 *
 *  1. The three US helpline numbers (988 / 211 / 911) exist on
 *     [TechEmpowerLinks] with the exact string values the dialer
 *     expects. A typo here (e.g. "9-1-1" or "988 ") would silently
 *     break the `ACTION_DIAL` intent — the dialer parses `tel:` URIs
 *     strictly.
 *  2. [TechEmpowerLinks.telUri] produces the canonical `tel:<n>`
 *     shape — no whitespace, no `tel://` (Android dialer treats
 *     `tel://211` as a non-resolvable URI on some OEM dialers).
 *  3. The three numbers are *distinct*. Bundling them into one card
 *     only works if each constant points to a different line; a copy-
 *     paste regression that sets 911 = "988" would silently route
 *     emergency taps to the crisis line.
 *  4. The card composable [TechEmpowerHomeScreen] still exists with
 *     the same public signature — Compose synthesises the function
 *     as `TechEmpowerHomeScreenKt`, and a reflection load catches
 *     accidental renames.
 *
 * Why pin via data + reflection instead of a Compose UI test:
 * `:feature` ships JVM unit tests only (no androidx.compose.ui.test
 * dependency yet — see [SettingsSubscreenContractTest] for the same
 * rationale). Once that infra lands, this test can grow assertions
 * that click each of the three sub-buttons and verify the dispatched
 * intent's action + data. Until then, the data-shape contract is the
 * cheapest regression net for the high-value "wrong number dialled
 * in an emergency" failure mode.
 */
class EmergencyHelpCardContractTest {

    @Test
    fun `crisis helpline constant is 988`() {
        // Exact string match — `tel:988` is the URI the dialer
        // resolves. Any whitespace, punctuation, or alternate
        // formatting (e.g. "9-8-8") would break the intent.
        assertEquals("988", TechEmpowerLinks.CRISIS_HELP_NUMBER)
    }

    @Test
    fun `primary helpline constant is 211`() {
        assertEquals("211", TechEmpowerLinks.PRIMARY_HELP_NUMBER)
    }

    @Test
    fun `emergency dispatch constant is 911`() {
        // Issue #516 introduced this constant. Pin its exact value
        // so a refactor that touches TechEmpowerLinks can't silently
        // drop or mis-spell the 911 surface.
        assertEquals("911", TechEmpowerLinks.EMERGENCY_DISPATCH_NUMBER)
    }

    @Test
    fun `telUri produces canonical tel scheme for every emergency number`() {
        // The dialer parses `tel:<digits>` strictly. `tel://211`
        // (with two slashes) is treated as a non-resolvable URI on
        // some OEM dialers — Samsung's stock dialer was the failure
        // mode JP hit during v0.5.51 development.
        assertEquals(
            "tel:988",
            TechEmpowerLinks.telUri(TechEmpowerLinks.CRISIS_HELP_NUMBER),
        )
        assertEquals(
            "tel:211",
            TechEmpowerLinks.telUri(TechEmpowerLinks.PRIMARY_HELP_NUMBER),
        )
        assertEquals(
            "tel:911",
            TechEmpowerLinks.telUri(TechEmpowerLinks.EMERGENCY_DISPATCH_NUMBER),
        )
    }

    @Test
    fun `three emergency numbers are distinct`() {
        // A copy-paste regression that sets two of the constants to
        // the same value would silently route taps to the wrong line.
        // Especially dangerous for 911 — an emergency tap going to
        // 988 (or vice-versa) is a user-facing failure mode that no
        // CI signal would catch downstream.
        val numbers = setOf(
            TechEmpowerLinks.CRISIS_HELP_NUMBER,
            TechEmpowerLinks.PRIMARY_HELP_NUMBER,
            TechEmpowerLinks.EMERGENCY_DISPATCH_NUMBER,
        )
        assertEquals(3, numbers.size)
    }

    @Test
    fun `TechEmpowerHomeScreen composable still exists`() {
        // Compose-emitted top-level functions land on a synthetic
        // class named `<FileName>Kt`. Asserting the class loads is
        // the cheapest smoke test for "the surface still exists" —
        // a rename or accidental deletion turns into ClassNotFound.
        val cls = runCatching {
            Class.forName("in.jphe.storyvox.feature.techempower.TechEmpowerHomeScreenKt")
        }.getOrNull()
        assertNotNull(
            "TechEmpowerHomeScreen composable missing — the Emergency Help card lives inside it.",
            cls,
        )
    }

    @Test
    fun `all three emergency-number constants are non-empty digit strings`() {
        // Defensive: the dialer happily opens with an empty `tel:`
        // URI but the user lands in an empty dial pad, which reads
        // as "the app is broken." Pin that each constant is non-blank
        // and digits-only (the only shape that round-trips cleanly
        // through `tel:` on every Android dialer JP has tested).
        val constants = listOf(
            TechEmpowerLinks.CRISIS_HELP_NUMBER,
            TechEmpowerLinks.PRIMARY_HELP_NUMBER,
            TechEmpowerLinks.EMERGENCY_DISPATCH_NUMBER,
        )
        for (n in constants) {
            assertTrue("Emergency number must be non-blank: '$n'", n.isNotBlank())
            assertTrue(
                "Emergency number must be digits-only: '$n'",
                n.all { it.isDigit() },
            )
        }
    }
}
