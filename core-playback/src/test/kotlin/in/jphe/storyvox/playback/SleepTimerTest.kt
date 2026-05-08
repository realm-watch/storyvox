package `in`.jphe.storyvox.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {

    private class RecordingVolumeRamp : VolumeRamp {
        val writes = mutableListOf<Float>()
        override fun set(v: Float) {
            writes += v
        }
    }

    private class CountingPauseAction : SleepTimer.PauseAction {
        var count: Int = 0
            private set
        override fun invoke() {
            count++
        }
    }

    @Test fun `remainingMs starts as null`() = runTest {
        val ramp = RecordingVolumeRamp()
        val pause = CountingPauseAction()
        val timer = SleepTimer(ramp, pause, backgroundScope)
        assertNull(timer.remainingMs.value)
    }

    @Test fun `Duration countdown publishes remainingMs at TICK_MS cadence`() = runTest {
        val ramp = RecordingVolumeRamp()
        val pause = CountingPauseAction()
        val timer = SleepTimer(ramp, pause, backgroundScope)

        timer.start(SleepTimerMode.Duration(minutes = 1)) // 60_000 ms total
        runCurrent()
        // First tick written before the first delay returns.
        assertEquals(60_000L, timer.remainingMs.value)

        advanceTimeBy(1_000L); runCurrent()
        assertEquals(59_000L, timer.remainingMs.value)

        advanceTimeBy(1_000L); runCurrent()
        assertEquals(58_000L, timer.remainingMs.value)

        // Stop the job so the test exits cleanly without simulating the full
        // 50-second fade tail.
        timer.cancel()
    }

    @Test fun `Duration countdown ends with fadeAndPause invoking pauseAction once`() = runTest {
        val ramp = RecordingVolumeRamp()
        val pause = CountingPauseAction()
        val timer = SleepTimer(ramp, pause, backgroundScope)

        timer.start(SleepTimerMode.Duration(minutes = 1))
        runCurrent()
        advanceTimeBy(70_000L) // 60s countdown + 10s fade tail
        runCurrent()

        assertEquals(1, pause.count)
        assertNull(timer.remainingMs.value)
    }

    @Test fun `EndOfChapter waits for signalChapterEnd before fading`() = runTest {
        val ramp = RecordingVolumeRamp()
        val pause = CountingPauseAction()
        val timer = SleepTimer(ramp, pause, backgroundScope)

        timer.start(SleepTimerMode.EndOfChapter)
        // Let the launched job park inside chapterEndSignal.first().
        runCurrent()
        // No chapter end yet — pauseAction must not have fired and time must
        // not be advancing remainingMs.
        assertEquals(0, pause.count)
        assertNull(timer.remainingMs.value)

        // Advance fake time arbitrarily; without a chapter signal nothing
        // should happen.
        advanceTimeBy(60_000L); runCurrent()
        assertEquals(0, pause.count)

        // Now signal chapter end and let the fade run.
        timer.signalChapterEnd()
        runCurrent()
        advanceTimeBy(11_000L) // 10s fade tail + slack
        runCurrent()

        assertEquals(1, pause.count)
        assertNull(timer.remainingMs.value)
    }

    @Test fun `fade ramps from 1f down to 0f across 100 steps`() = runTest {
        val ramp = RecordingVolumeRamp()
        val pause = CountingPauseAction()
        val timer = SleepTimer(ramp, pause, backgroundScope)

        timer.start(SleepTimerMode.EndOfChapter)
        runCurrent()
        timer.signalChapterEnd()
        runCurrent()
        advanceTimeBy(11_000L)
        runCurrent()

        // Fade writes by step index 0..100: values 100/100, 99/100, ..., 0/100.
        // Then a final reset to 1.0f. 102 writes for the fade itself.
        // start() called cancel() first which writes 1.0f, so the recorded
        // sequence is [1.0 (cancel), 1.0 (step 0), 0.99, ..., 0.0 (step 100), 1.0 (reset)].
        // Total writes = 1 + 101 + 1 = 103.
        assertEquals(103, ramp.writes.size)
        assertEquals(1.0f, ramp.writes.first(), 1e-6f) // cancel-as-restart from start()
        assertEquals(1.0f, ramp.writes[1], 1e-6f)      // fade step 0
        assertEquals(0.0f, ramp.writes[101], 1e-6f)    // fade step 100
        assertEquals(1.0f, ramp.writes.last(), 1e-6f)  // post-pause reset

        // Strictly monotonic non-increasing during the fade body
        // (indices 1..101 in the recorded sequence).
        for (i in 2..101) {
            assertTrue(
                "fade write index $i must be <= ${i - 1}",
                ramp.writes[i] <= ramp.writes[i - 1],
            )
        }
    }

    @Test fun `fade publishes remainingMs counting down to 0 across 10 seconds`() = runTest {
        val ramp = RecordingVolumeRamp()
        val pause = CountingPauseAction()
        val timer = SleepTimer(ramp, pause, backgroundScope)

        timer.start(SleepTimerMode.EndOfChapter)
        runCurrent()
        timer.signalChapterEnd()
        runCurrent()
        // After signal received, fade writes its first remaining value
        // (100 steps * 100ms = 10_000ms).
        assertEquals(10_000L, timer.remainingMs.value)

        advanceTimeBy(5_000L); runCurrent()
        // Halfway through fade: should be ~5000ms remaining.
        val mid = timer.remainingMs.value
        assertNotNull(mid)
        assertTrue("expected ~5000ms at halfway, got $mid", mid!! in 4_000L..6_000L)

        advanceTimeBy(6_000L); runCurrent()
        assertNull(timer.remainingMs.value)
        assertEquals(1, pause.count)
    }

    @Test fun `cancel before any tick clears remainingMs and resets volume`() = runTest {
        val ramp = RecordingVolumeRamp()
        val pause = CountingPauseAction()
        val timer = SleepTimer(ramp, pause, backgroundScope)

        timer.start(SleepTimerMode.Duration(minutes = 5))
        runCurrent()
        assertEquals(300_000L, timer.remainingMs.value)

        timer.cancel()
        runCurrent()

        assertNull(timer.remainingMs.value)
        assertEquals(0, pause.count)
        // cancel always resets ramp to 1.0f as its last act.
        assertEquals(1.0f, ramp.writes.last(), 1e-6f)
    }

    @Test fun `cancel mid-fade prevents pauseAction and resets state`() = runTest {
        val ramp = RecordingVolumeRamp()
        val pause = CountingPauseAction()
        val timer = SleepTimer(ramp, pause, backgroundScope)

        timer.start(SleepTimerMode.EndOfChapter)
        runCurrent()
        timer.signalChapterEnd()
        runCurrent()
        // Step into the fade — past the first few writes but before completion.
        advanceTimeBy(500L); runCurrent()
        assertTrue(ramp.writes.isNotEmpty())

        timer.cancel()
        advanceTimeBy(11_000L); runCurrent()

        assertEquals("cancel mid-fade must not invoke pauseAction", 0, pause.count)
        assertNull(timer.remainingMs.value)
        assertEquals(1.0f, ramp.writes.last(), 1e-6f)
    }

    @Test fun `cancel is idempotent when no timer is running`() = runTest {
        val ramp = RecordingVolumeRamp()
        val pause = CountingPauseAction()
        val timer = SleepTimer(ramp, pause, backgroundScope)

        // Multiple cancels with no active job must not throw and must not
        // bring pauseAction.count above zero.
        timer.cancel()
        timer.cancel()
        timer.cancel()
        runCurrent()

        assertEquals(0, pause.count)
        assertNull(timer.remainingMs.value)
        // Each cancel writes 1.0f to the ramp; that's a known and intentional
        // side effect of the production code.
        assertTrue(ramp.writes.all { it == 1.0f })
        assertEquals(3, ramp.writes.size)
    }

    @Test fun `start cancels a previously running timer`() = runTest {
        val ramp = RecordingVolumeRamp()
        val pause = CountingPauseAction()
        val timer = SleepTimer(ramp, pause, backgroundScope)

        timer.start(SleepTimerMode.Duration(minutes = 5))
        runCurrent()
        assertEquals(300_000L, timer.remainingMs.value)

        // Restart with a shorter duration. Per `start()`'s contract the prior
        // job is cancelled before the new one is launched.
        timer.start(SleepTimerMode.Duration(minutes = 1))
        runCurrent()
        assertEquals(60_000L, timer.remainingMs.value)

        timer.cancel()
    }

    @Test fun `signalChapterEnd before EndOfChapter start IS captured and replayed`() = runTest {
        // Issue #34: chapterEndSignal uses replay=1 so a signal fired in
        // the millisecond before start(EndOfChapter) subscribes is still
        // observable. Previously this test documented the dropped-signal
        // behavior as a contract; the contract is now "captured and
        // replayed" because the dropped-signal version produced
        // unobservable bugs (timer never fires; user thinks the feature
        // is broken).
        val ramp = RecordingVolumeRamp()
        val pause = CountingPauseAction()
        val timer = SleepTimer(ramp, pause, backgroundScope)

        timer.signalChapterEnd()
        runCurrent()

        timer.start(SleepTimerMode.EndOfChapter)
        runCurrent()
        // Cached signal is consumed immediately; let the fade-tail run.
        advanceTimeBy(11_000L); runCurrent() // 10s fade tail + slack

        assertEquals(1, pause.count)
        timer.cancel()
    }

    @Test fun `cancel clears the chapter-end replay cache`() = runTest {
        // Issue #34 corollary: a stale signal from chapter A must not
        // arm a freshly-started EndOfChapter timer for chapter B. The
        // cache is logically per-timer-lifetime, not per-process.
        val ramp = RecordingVolumeRamp()
        val pause = CountingPauseAction()
        val timer = SleepTimer(ramp, pause, backgroundScope)

        // Chapter A ends while no timer is active — signal cached.
        timer.signalChapterEnd()
        runCurrent()
        // User briefly armed and cancelled an EndOfChapter timer.
        timer.start(SleepTimerMode.EndOfChapter)
        runCurrent()
        timer.cancel()
        runCurrent()
        // The cancel above should clear the cache. So a fresh
        // EndOfChapter timer must NOT consume the stale chapter A
        // signal.
        timer.start(SleepTimerMode.EndOfChapter)
        runCurrent()
        advanceTimeBy(60_000L); runCurrent()

        assertEquals(0, pause.count)
        timer.cancel()
    }

    @Test fun `fadeAndPause clears the chapter-end replay cache`() = runTest {
        // Issue #34 corollary: after a successful EndOfChapter timer
        // completes, the consumed signal must not auto-arm the NEXT
        // EndOfChapter timer.
        val ramp = RecordingVolumeRamp()
        val pause = CountingPauseAction()
        val timer = SleepTimer(ramp, pause, backgroundScope)

        timer.start(SleepTimerMode.EndOfChapter)
        runCurrent()
        timer.signalChapterEnd()
        runCurrent()
        advanceTimeBy(11_000L); runCurrent() // 10s fade tail + slack
        assertEquals(1, pause.count)

        // Now arm a second EndOfChapter timer — the cache from the
        // first one must be empty.
        timer.start(SleepTimerMode.EndOfChapter)
        runCurrent()
        advanceTimeBy(60_000L); runCurrent()

        // No new signal fired, so pause count stays at 1.
        assertEquals(1, pause.count)
        timer.cancel()
    }
}
