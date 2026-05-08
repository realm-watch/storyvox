package `in`.jphe.storyvox.playback.tts

import `in`.jphe.storyvox.playback.SentenceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceTrackerTest {

    private fun sentence(idx: Int, start: Int, end: Int) =
        Sentence(index = idx, startChar = start, endChar = end, text = "ignored")

    private val sample = listOf(
        sentence(0, 0, 10),     // "Sentence A"
        sentence(1, 12, 22),    // "Sentence B"
        sentence(2, 24, 34),    // "Sentence C"
    )

    private class Recorder {
        val sentences = mutableListOf<SentenceRange>()
        var chapterDoneCount = 0
        val errors = mutableListOf<Pair<String, Int>>()
        var speedRunnerCount = 0

        fun newTracker(
            corpus: List<Sentence>,
            parseIndex: (String) -> Int? = { it.removePrefix("s").substringBefore("_").toIntOrNull() },
        ) = SentenceTracker(
            sentences = corpus,
            onSentence = { sentences += it },
            onChapterDone = { chapterDoneCount++ },
            onErrorEmitted = { id, code -> errors += id to code },
            parseIndex = parseIndex,
            onSpeedRunnerDetected = { speedRunnerCount++ },
        )
    }

    // -- onStart ----------------------------------------------------------------

    @Test fun `onStart emits whole-sentence range for valid utteranceId`() {
        val r = Recorder()
        val tracker = r.newTracker(sample)

        tracker.onStart("s1_p0")

        assertEquals(1, r.sentences.size)
        assertEquals(SentenceRange(1, 12, 22), r.sentences[0])
    }

    @Test fun `onStart with malformed utteranceId is ignored`() {
        val r = Recorder()
        val tracker = r.newTracker(sample)

        tracker.onStart("garbage")
        tracker.onStart("")

        assertEquals(0, r.sentences.size)
    }

    @Test fun `onStart with out-of-range index is ignored`() {
        val r = Recorder()
        val tracker = r.newTracker(sample)

        tracker.onStart("s99_p0")
        tracker.onStart("s-1_p0")

        assertEquals(0, r.sentences.size)
    }

    @Test fun `onStart on empty corpus emits nothing`() {
        val r = Recorder()
        val tracker = r.newTracker(emptyList())

        tracker.onStart("s0_p0")

        assertEquals(0, r.sentences.size)
    }

    // -- onRangeStart -----------------------------------------------------------

    @Test fun `onRangeStart flips hasReceivedRange and emits sub-sentence range`() {
        val r = Recorder()
        val tracker = r.newTracker(sample)
        assertFalse(tracker.hasReceivedRange)

        tracker.onRangeStart("s1_p0", start = 2, end = 5, frame = 0)

        assertTrue(tracker.hasReceivedRange)
        assertEquals(1, r.sentences.size)
        // Range offsets are sentence-relative; SentenceTracker re-bases them
        // onto the chapter-level startChar.
        assertEquals(SentenceRange(1, 14, 17), r.sentences[0])
    }

    @Test fun `onRangeStart with malformed id is ignored AND does not flip hasReceivedRange`() {
        // Issue #43: a parse failure means the engine sent us a callback we
        // can't act on. The "engine supports per-word ranges" flag should
        // NOT flip on garbage — treating it as supported misleads the
        // whole-sentence fallback path into thinking the engine works.
        val r = Recorder()
        val tracker = r.newTracker(sample)

        tracker.onRangeStart("garbage", 0, 1, 0)

        assertFalse(tracker.hasReceivedRange)
        assertEquals(0, r.sentences.size)
    }

    @Test fun `onRangeStart with out-of-range index flips hasReceivedRange but emits nothing`() {
        // Distinct from the malformed case: parse succeeded (returns 99),
        // we just have no sentence at that index (e.g. a stale callback
        // after a seek). The engine demonstrably emits per-word ranges,
        // so the flag flip is correct.
        val r = Recorder()
        val tracker = r.newTracker(sample)

        tracker.onRangeStart("s99_p0", 0, 1, 0)

        assertTrue(tracker.hasReceivedRange)
        assertEquals(0, r.sentences.size)
    }

    // -- onDone -----------------------------------------------------------------

    @Test fun `onDone on last sentence fires onChapterDone`() {
        val r = Recorder()
        val tracker = r.newTracker(sample)

        tracker.onDone("s2_p0")

        assertEquals(1, r.chapterDoneCount)
    }

    @Test fun `onDone on non-last sentence does NOT fire onChapterDone`() {
        val r = Recorder()
        val tracker = r.newTracker(sample)

        tracker.onDone("s0_p0")
        tracker.onDone("s1_p0")

        assertEquals(0, r.chapterDoneCount)
    }

    @Test fun `onDone with malformed id is ignored`() {
        val r = Recorder()
        val tracker = r.newTracker(sample)

        tracker.onDone("garbage")
        tracker.onDone("")

        assertEquals(0, r.chapterDoneCount)
    }

    @Test fun `onDone on empty corpus does not fire onChapterDone for any positive index`() {
        // No sentences => lastIndex == -1, so any utteranceId parsing to a
        // non-negative integer cannot match. Callers should never see a
        // chapter-done event for an empty chapter.
        val r = Recorder()
        val tracker = r.newTracker(emptyList())

        tracker.onDone("s0_p0")
        tracker.onDone("s5_p0")

        assertEquals(0, r.chapterDoneCount)
    }

    // -- onError ----------------------------------------------------------------

    @Test fun `onError(id, code) relays errorCode verbatim`() {
        val r = Recorder()
        val tracker = r.newTracker(sample)

        tracker.onError("s1_p0", 42)

        assertEquals(listOf("s1_p0" to 42), r.errors)
    }

    @Test fun `deprecated onError(id) relays code -1`() {
        val r = Recorder()
        val tracker = r.newTracker(sample)

        @Suppress("DEPRECATION")
        tracker.onError("s2_p0")

        assertEquals(listOf("s2_p0" to -1), r.errors)
    }

    @Test fun `onError does NOT also fire chapterDone or sentence callbacks`() {
        val r = Recorder()
        val tracker = r.newTracker(sample)

        tracker.onError("s2_p0", 7)

        assertEquals(0, r.chapterDoneCount)
        assertEquals(0, r.sentences.size)
    }

    // -- Speed-runner detection -------------------------------------------------

    @Test fun `single fast onStart does not trigger speed-runner`() {
        val r = Recorder()
        val tracker = r.newTracker(sample)

        // Simulate two sub-floor onStart calls — one gap.
        tracker.onStart("s0_p0")
        tracker.onStart("s1_p0")

        assertEquals(0, r.speedRunnerCount)
        // Both sentences still emitted.
        assertEquals(2, r.sentences.size)
    }

    @Test fun `nine consecutive fast onStarts do not trigger speed-runner`() {
        // Trigger threshold is 10 consecutive sub-floor gaps.
        val r = Recorder()
        val tracker = r.newTracker(List(20) { sentence(it, it * 10, it * 10 + 5) })

        // 10 onStart calls back-to-back == 9 gaps.
        repeat(10) { tracker.onStart("s${it}_p0") }

        assertEquals(0, r.speedRunnerCount)
    }

    @Test fun `eleven consecutive fast onStarts trigger speed-runner exactly once`() {
        val r = Recorder()
        val tracker = r.newTracker(List(20) { sentence(it, it * 10, it * 10 + 5) })

        // 11 onStart calls back-to-back == 10 gaps == trigger.
        repeat(11) { tracker.onStart("s${it}_p0") }

        assertEquals(1, r.speedRunnerCount)
    }

    @Test fun `speed-runner trigger suppresses sentence emission for the trigger call`() {
        // The trigger call does an early return BEFORE emitting onSentence —
        // so the eleventh call publishes the speedRunner event instead of the
        // sentence.
        val r = Recorder()
        val tracker = r.newTracker(List(20) { sentence(it, it * 10, it * 10 + 5) })

        repeat(11) { tracker.onStart("s${it}_p0") }

        // 10 sentences before the trigger; 11th was suppressed.
        assertEquals(10, r.sentences.size)
        assertEquals(1, r.speedRunnerCount)
    }

    @Test fun `slow start interrupts streak and resets fast-start counter`() {
        // Without reset, 9 + 9 fast starts (separated by a slow gap) would
        // accumulate to a streak of 16 — well past the threshold of 10.
        // With reset, the slow gap drops the counter to 0, and the second
        // batch of 9 fast starts only produces 8 fast gaps — below trigger.
        val r = Recorder()
        val tracker = r.newTracker(List(30) { sentence(it, it * 10, it * 10 + 5) })

        // 9 fast starts == 8 fast gaps, streak peaks at 8.
        repeat(9) { tracker.onStart("s${it}_p0") }
        // Wait long enough that the next gap is well above SPEED_RUNNER_FLOOR_MS.
        Thread.sleep(80)
        // First start after slow gap resets streak to 0; this anchors lastStartAtMs.
        tracker.onStart("s9_p0")
        // 9 more fast starts == 8 more fast gaps relative to s9, streak peaks at 8.
        repeat(9) { tracker.onStart("s${it + 10}_p0") }

        assertEquals(0, r.speedRunnerCount)
    }

    @Test fun `first onStart never counts as fast start`() {
        // lastStartAtMs starts at 0; the gap check requires lastStartAtMs > 0
        // to consider a gap. So the very first call should never increment
        // the streak.
        val r = Recorder()
        val tracker = r.newTracker(sample)

        // Just one call; check that even a thousand calls in a row would
        // need the second call to be the first counted gap.
        tracker.onStart("s0_p0")

        assertEquals(0, r.speedRunnerCount)
        assertEquals(1, r.sentences.size)
    }
}

