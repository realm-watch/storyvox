package `in`.jphe.storyvox.feature.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accessibility scaffold (Phase 1, v0.5.42) — contract tests for the
 * [AccessibilityStateBridge] interface and the [AccessibilityState]
 * data class.
 *
 * Phase 1 surface only — no consumers in storyvox today. These tests
 * pin the shape of the data the Phase 2 agents will read against:
 *  - Default values must stay all-false (test fakes need this).
 *  - The Flow must be observable without crashing.
 *  - copy() must work as expected on the data class (Phase 2 agents
 *    might layer derived state by copying).
 *
 * The real [`RealAccessibilityStateBridge`] in `:app` is exercised by
 * the integration / instrumented tests there — JVM unit tests in
 * `:feature` only verify the interface contract.
 */
class AccessibilityStateTest {

    @Test
    fun `data class default has all flags false`() {
        val state = AccessibilityState()
        assertFalse("TalkBack default off", state.isTalkBackActive)
        assertFalse("Switch Access default off", state.isSwitchAccessActive)
        assertFalse("Reduce motion default off", state.isReduceMotionRequested)
    }

    @Test
    fun `data class copy lets Phase 2 consumers derive state`() {
        // Phase 2 adapter might want to fold "any assistive service
        // active" into a derived field. Verify the data class supports
        // that ergonomically via copy().
        val base = AccessibilityState()
        val withTalkBack = base.copy(isTalkBackActive = true)
        assertTrue(withTalkBack.isTalkBackActive)
        assertFalse(withTalkBack.isSwitchAccessActive)
        assertFalse(withTalkBack.isReduceMotionRequested)
        // Original is unchanged (data-class semantics).
        assertFalse(base.isTalkBackActive)
    }

    @Test
    fun `bridge default state flow emits the all-false baseline`() = runTest {
        // The interface default returns a Flow that emits a single
        // AccessibilityState() — used by test fakes and any consumer
        // that takes the interface but doesn't get a real binding.
        val bridge = object : AccessibilityStateBridge {}
        val first = bridge.state.first()
        assertEquals(AccessibilityState(), first)
    }

    @Test
    fun `bridge subclass can override the state stream for tests`() = runTest {
        // Phase 2 agents writing their own consumer tests should be
        // able to substitute a fixed-emission bridge. Pin that the
        // override mechanism works.
        val fake = object : AccessibilityStateBridge {
            override val state = flowOf(
                AccessibilityState(
                    isTalkBackActive = true,
                    isReduceMotionRequested = true,
                ),
            )
        }
        val emitted = fake.state.first()
        assertTrue(emitted.isTalkBackActive)
        assertFalse(emitted.isSwitchAccessActive)
        assertTrue(emitted.isReduceMotionRequested)
    }

    @Test
    fun `bridge can be observed without crashing in tests`() = runTest {
        // Smoke: the default Flow can be observed at least once. A
        // regression where the default Flow becomes null or throws on
        // collect would surface here before any Phase 2 agent runs
        // into it.
        val bridge = object : AccessibilityStateBridge {}
        val flow = bridge.state
        assertNotNull(flow)
        val first = flow.first()
        assertNotNull(first)
    }
}
