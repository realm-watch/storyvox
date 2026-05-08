package `in`.jphe.storyvox.playback.tts.source

import `in`.jphe.storyvox.playback.tts.Sentence
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineStreamingSourceTest {

    @Test
    fun `nextChunk returns sentences in order then null at end`() = runBlocking {
        val sentences = listOf(
            Sentence(0, 0, 10, "One."),
            Sentence(1, 11, 20, "Two."),
            Sentence(2, 21, 30, "Three."),
        )
        val fakeEngine = FakeVoiceEngine(
            sampleRate = 22050,
            pcmFor = { text -> ByteArray(text.length * 2) { 0 } },
        )
        val source = EngineStreamingSource(
            sentences = sentences,
            startSentenceIndex = 0,
            engine = fakeEngine,
            speed = 1.0f,
            pitch = 1.0f,
            engineMutex = Mutex(),
        )

        val c0 = source.nextChunk()
        val c1 = source.nextChunk()
        val c2 = source.nextChunk()
        val end = source.nextChunk()

        assertEquals(0, c0?.sentenceIndex)
        assertEquals(1, c1?.sentenceIndex)
        assertEquals(2, c2?.sentenceIndex)
        assertNull(end)

        source.close()
    }

    @Test
    fun `close makes a subsequent nextChunk return null`() = runBlocking {
        // Engine that releases generation only when the test signals; this
        // guarantees the producer is parked while close() runs, no timing race.
        val gate = java.util.concurrent.CountDownLatch(1)
        val gatedEngine = object : EngineStreamingSource.VoiceEngineHandle {
            override val sampleRate: Int = 22050
            override fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray? {
                // Wait until the test releases us. If close() came before
                // the test releases, the next iteration's running.get()
                // check + the runInterruptible queue.put will both
                // short-circuit, so no chunk reaches the queue.
                gate.await()
                return ByteArray(100)
            }
        }
        val sentences = listOf(Sentence(0, 0, 10, "One."))
        val source = EngineStreamingSource(sentences, 0, gatedEngine, 1f, 1f, Mutex())

        // Producer is now parked at gate.await() inside engine.generateAudioPCM.
        // Give Dispatchers.IO a moment to schedule it; not strictly required
        // since close()'s contract holds even if the producer hasn't started
        // yet, but easier to reason about.
        delay(50)

        source.close()

        // Now release the producer. Its `running.get()` post-engine guard
        // should fire, the runInterruptible put should observe cancellation,
        // and no chunk reaches the queue. nextChunk should return null
        // (END_PILL placed by close).
        gate.countDown()

        val started = System.currentTimeMillis()
        val result = source.nextChunk()
        val elapsed = System.currentTimeMillis() - started

        assertNull(result)
        assert(elapsed < 500) { "nextChunk after close took $elapsed ms" }
    }

    @Test
    fun `bufferHeadroomMs reflects queued audio duration`() = runBlocking {
        val sentences = listOf(
            Sentence(0, 0, 10, "One."),
            Sentence(1, 11, 20, "Two."),
        )
        // 22050 Hz × 2 bytes per sample = 44100 bytes per second of audio.
        // 44100 bytes ⇒ 1000 ms.
        val fakeEngine = FakeVoiceEngine(22050) { _ -> ByteArray(44100) }
        val source = EngineStreamingSource(sentences, 0, fakeEngine, 1f, 1f, Mutex())

        // Wait for producer to fill the queue with both sentences.
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline &&
               source.bufferHeadroomMs.value < 2_000) {
            delay(10)
        }
        // 2 sentences × (1000 ms PCM + 350 ms cadence) = 2700 ms expected
        // (cadence is 350 ms because "One." / "Two." end in '.').
        val before = source.bufferHeadroomMs.value
        assert(before >= 2_000) { "expected ≥ 2000 ms headroom, got $before" }

        val first = source.nextChunk()
        assertEquals(0, first?.sentenceIndex)
        val after = source.bufferHeadroomMs.value
        // Headroom should drop by ~1350 ms (1000 ms PCM + 350 ms cadence).
        assert(after < before - 1_000) {
            "expected headroom to drop by > 1000 ms after take; was $before → $after"
        }

        source.close()
    }
}

private class FakeVoiceEngine(
    override val sampleRate: Int,
    val pcmFor: (String) -> ByteArray,
) : EngineStreamingSource.VoiceEngineHandle {
    override fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray? = pcmFor(text)
}
