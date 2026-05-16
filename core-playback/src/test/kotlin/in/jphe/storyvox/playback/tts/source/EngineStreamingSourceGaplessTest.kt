package `in`.jphe.storyvox.playback.tts.source

import `in`.jphe.storyvox.playback.tts.Sentence
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #573 — Gapless auto-advance: tests for the new
 * [PcmSource.producedAllSentences] contract.
 *
 * The flag exists to give the consumer thread an unambiguous signal
 * for "this is a natural chapter end" that doesn't race against
 * `pipelineRunning` flips from a concurrent stopPlaybackPipeline.
 *
 * Pre-#573 the consumer inferred natural-end from
 * `pipelineRunning.get()` captured at END_PILL dequeue time, then
 * re-checked it ~100 ms later in the finally block — opening a race
 * window that silently swallowed handleChapterDone on Notion sources
 * (the on-device symptom that drove this fix; see PR thread).
 *
 * These tests pin the contract:
 *  1. Before any nextChunk, flag is false.
 *  2. After producer drains all sentences and consumer dequeues
 *     null, flag is true. (Natural end.)
 *  3. After close() (simulating stopPlaybackPipeline), null returns
 *     without setting the flag. (Cancellation, NOT a chapter end.)
 *
 * The integration with EnginePlayer's finally-block gating is tested
 * indirectly: with the flag wired into the gate (see
 * EnginePlayer.startPlaybackPipeline's consumer finally), point 2
 * fires handleChapterDone, point 3 does not.
 */
class EngineStreamingSourceGaplessTest {

    @Test
    fun `producedAllSentences is false before any chunk is drained`() = runBlocking {
        val sentences = listOf(
            Sentence(0, 0, 10, "One."),
            Sentence(1, 11, 20, "Two."),
        )
        val src = EngineStreamingSource(
            sentences = sentences,
            startSentenceIndex = 0,
            engine = GaplessFakeVoiceEngine(22050) { ByteArray(100) },
            speed = 1f,
            pitch = 1f,
            engineMutex = Mutex(),
        )

        assertFalse(
            "producedAllSentences must be false until the producer reaches end-of-list",
            src.producedAllSentences,
        )

        src.close()
    }

    @Test
    fun `producedAllSentences flips true when consumer drains to null on natural end`() =
        runBlocking {
            val sentences = listOf(
                Sentence(0, 0, 10, "One."),
                Sentence(1, 11, 20, "Two."),
                Sentence(2, 21, 30, "Three."),
            )
            val src = EngineStreamingSource(
                sentences = sentences,
                startSentenceIndex = 0,
                engine = GaplessFakeVoiceEngine(22050) { ByteArray(50) },
                speed = 1f,
                pitch = 1f,
                engineMutex = Mutex(),
            )

            // Drain every sentence, then the END_PILL.
            assertNotNull(src.nextChunk())
            assertNotNull(src.nextChunk())
            assertNotNull(src.nextChunk())
            val end = src.nextChunk()
            assertNull("End-of-stream chunk must be null", end)

            assertTrue(
                "producedAllSentences must be true after natural END_PILL",
                src.producedAllSentences,
            )

            src.close()
        }

    @Test
    fun `producedAllSentences stays false when close races a partially-drained source`() =
        runBlocking {
            // Many sentences, but we close after consuming only one. The
            // producer is mid-loop when close cancels it — the natural-end
            // branch (which sets producedAll=true) never executes.
            val sentences = (0 until 50).map {
                Sentence(it, it * 10, it * 10 + 8, "Sentence $it.")
            }
            val src = EngineStreamingSource(
                sentences = sentences,
                startSentenceIndex = 0,
                engine = GaplessFakeVoiceEngine(22050) { ByteArray(50) },
                speed = 1f,
                pitch = 1f,
                engineMutex = Mutex(),
            )

            // Pull one chunk so the producer is definitely past startup.
            assertNotNull(src.nextChunk())

            // Close mid-stream — same path as stopPlaybackPipeline.close.
            src.close()

            // A subsequent nextChunk returns null (close pushed END_PILL).
            // But the flag is the test target — it must remain false
            // because the producer was CANCELLED, not naturally exhausted.
            // The post-#573 EnginePlayer gate (`naturalEnd =
            // source.producedAllSentences`) therefore correctly skips
            // handleChapterDone for this case (user-initiated stop, not
            // a chapter end).
            val end = src.nextChunk()
            assertNull(end)

            assertFalse(
                "producedAllSentences must NOT be true after close mid-stream — " +
                    "this would falsely fire chapter-done on a user pause / voice swap",
                src.producedAllSentences,
            )
        }

    @Test
    fun `producedAllSentences flips true even on a single-sentence chapter`() =
        runBlocking {
            // Edge case: chapter with exactly one sentence. The natural-end
            // path still has to fire — pre-#573 a one-sentence chapter
            // (e.g. Royal Road short chapters) exited the producer's
            // for-loop after one iteration and hit the END_PILL push
            // immediately, but the consumer-thread `naturalEnd =
            // pipelineRunning.get()` could observe a transient false if
            // the user tapped pause in the same millisecond. The
            // producer-set flag is monotonic — once we exited the loop
            // naturally, the flag is sticky-true.
            val sentences = listOf(Sentence(0, 0, 10, "Solo."))
            val src = EngineStreamingSource(
                sentences = sentences,
                startSentenceIndex = 0,
                engine = GaplessFakeVoiceEngine(22050) { ByteArray(50) },
                speed = 1f,
                pitch = 1f,
                engineMutex = Mutex(),
            )

            assertNotNull(src.nextChunk())
            assertNull(src.nextChunk())

            assertTrue(src.producedAllSentences)

            src.close()
        }

    @Test
    fun `producedAllSentences flips true on zero-sentence chapter via empty list`() =
        runBlocking {
            // Issue #442 edge: chunker yields zero sentences. EnginePlayer
            // bails before constructing the source for this case (with
            // a typed error), so this is mostly defensive — but if a
            // future code path ever constructs an empty source, the
            // natural-end path should still fire cleanly with no chunks.
            val src = EngineStreamingSource(
                sentences = emptyList(),
                startSentenceIndex = 0,
                engine = GaplessFakeVoiceEngine(22050) { ByteArray(50) },
                speed = 1f,
                pitch = 1f,
                engineMutex = Mutex(),
            )

            // First chunk is immediately null (producer loop iterated
            // zero times, then pushed END_PILL).
            assertNull(src.nextChunk())
            assertTrue(
                "Zero-sentence source must still report natural end so the consumer " +
                    "advances past the empty chapter rather than stalling",
                src.producedAllSentences,
            )

            src.close()
        }
}

/** Local copy of the test-only engine handle. The sibling
 *  EngineStreamingSourceTest declares the same shape `private`; this
 *  inlined copy keeps both test classes independent so a change to one
 *  test's fake doesn't ripple into the other. Identity-only behaviour. */
private class GaplessFakeVoiceEngine(
    override val sampleRate: Int,
    val pcmFor: (String) -> ByteArray,
) : EngineStreamingSource.VoiceEngineHandle {
    override fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray? = pcmFor(text)
}
