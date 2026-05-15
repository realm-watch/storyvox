package `in`.jphe.storyvox.playback.tts.source

import `in`.jphe.storyvox.playback.tts.Sentence
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accessibility scaffold Phase 2 (#486 / #488, v0.5.43) — verifies
 * the extra inter-sentence silence the TalkBack pacing knob splices
 * actually shows up in [EngineStreamingSource]'s [PcmChunk] output.
 *
 * The pad is bytes added to the existing punctuation-pause silence;
 * outside TalkBack the upstream `A11yPacingConfig` emits 0 and the
 * v0.5.42 math is unchanged (verified separately by
 * `punctuationPauseMultiplier scales trailing silence` in the
 * existing test file).
 */
class EnginePlayerTalkBackPacingTest {

    private val sampleRate = 22050

    @Test
    fun `extra a11y silence adds to the punctuation pause when TalkBack is active`() = runBlocking {
        val sentences = listOf(
            Sentence(0, 0, 10, "First."),
            Sentence(1, 11, 20, "Second."),
        )
        val pcm = ByteArray(100)
        val fakeEngine = newFakeEngine(sampleRate, pcm)

        // Baseline — punctuation pause active (multiplier=1, default),
        // no TalkBack pad. Matches v0.5.42 behavior.
        val baseline = firstChunkSilenceBytes(sentences, fakeEngine, multiplier = 1f, padMs = 0)

        // TalkBack-on — 500 ms additional silence (the default slider
        // value). Should produce baseline + silenceBytesFor(500ms).
        val withPad = firstChunkSilenceBytes(sentences, fakeEngine, multiplier = 1f, padMs = 500)

        val expectedPadBytes = silenceBytesFor(500, sampleRate)
        assertEquals(
            "TalkBack pad should add exactly silenceBytesFor(500ms) on top of the baseline",
            baseline + expectedPadBytes,
            withPad,
        )
    }

    @Test
    fun `extra a11y silence is zero when pad is zero — preserves v0 5 42 math`() = runBlocking {
        // When TalkBack isn't active (upstream config emits 0), the
        // pipeline must produce bit-identical silence-bytes to the
        // v0.5.42 pre-#486 path. This guarantees sighted listeners
        // don't see ANY behavior change from the a11y wiring landing.
        val sentences = listOf(Sentence(0, 0, 10, "Hello."))
        val pcm = ByteArray(100)
        val fakeEngine = newFakeEngine(sampleRate, pcm)

        val v0_5_42 = firstChunkSilenceBytes(sentences, fakeEngine, multiplier = 1f, padMs = 0)
        // Re-construct without the parameter (relies on default value
        // matching 0). If the default ever drifted away from 0 this
        // would break.
        val withoutParam = firstChunkSilenceBytesDefault(sentences, fakeEngine, multiplier = 1f)

        assertEquals(
            "extraA11ySilenceMs default of 0 must produce v0.5.42-identical silence bytes",
            v0_5_42,
            withoutParam,
        )
    }

    @Test
    fun `extra a11y silence respects playback speed`() = runBlocking {
        // Same as the punctuation-pause: at 2× speed the user has
        // chosen to listen faster, so the pad scales DOWN. The slider
        // value reads as "pause length at 1× speed".
        val sentences = listOf(Sentence(0, 0, 10, "Speedy."))
        val pcm = ByteArray(100)
        val fakeEngine = newFakeEngine(sampleRate, pcm)

        val at1x = firstChunkSilenceBytes(sentences, fakeEngine, multiplier = 0f, padMs = 500, speed = 1f)
        val at2x = firstChunkSilenceBytes(sentences, fakeEngine, multiplier = 0f, padMs = 500, speed = 2f)

        // multiplier=0f kills the punctuation pause, isolating the a11y pad.
        // 500ms at 1× → silenceBytesFor(500ms). At 2× → silenceBytesFor(250ms).
        assertEquals(silenceBytesFor(500, sampleRate), at1x)
        assertEquals(silenceBytesFor(250, sampleRate), at2x)
        assertTrue("2× speed should produce less silence than 1×", at2x < at1x)
    }

    @Test
    fun `extra a11y silence clamps negative values to zero`() = runBlocking {
        // Defense-in-depth: a buggy caller passing a negative pad
        // shouldn't make silenceBytesFor return a weird value.
        // silenceBytesFor already coerces non-positive durations to
        // 0; this test pins that contract for the a11y path.
        val sentences = listOf(Sentence(0, 0, 10, "Hello."))
        val pcm = ByteArray(100)
        val fakeEngine = newFakeEngine(sampleRate, pcm)

        val baseline = firstChunkSilenceBytes(sentences, fakeEngine, multiplier = 0f, padMs = 0)
        val withNegativePad = firstChunkSilenceBytes(sentences, fakeEngine, multiplier = 0f, padMs = -100)

        // multiplier=0f kills punctuation pause; negative pad coerces
        // to 0 inside silenceBytesFor. Both should produce 0 bytes.
        assertEquals(0, baseline)
        assertEquals(0, withNegativePad)
    }

    private suspend fun firstChunkSilenceBytes(
        sentences: List<Sentence>,
        engine: EngineStreamingSource.VoiceEngineHandle,
        multiplier: Float,
        padMs: Int,
        speed: Float = 1f,
    ): Int {
        val src = EngineStreamingSource(
            sentences = sentences,
            startSentenceIndex = 0,
            engine = engine,
            speed = speed,
            pitch = 1f,
            engineMutex = Mutex(),
            punctuationPauseMultiplier = multiplier,
            extraA11ySilenceMs = padMs,
        )
        val chunk = src.nextChunk()
        src.close()
        return chunk?.trailingSilenceBytes ?: -1
    }

    private fun newFakeEngine(sampleRate: Int, pcm: ByteArray): EngineStreamingSource.VoiceEngineHandle =
        object : EngineStreamingSource.VoiceEngineHandle {
            override val sampleRate: Int = sampleRate
            override fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray = pcm
        }

    private suspend fun firstChunkSilenceBytesDefault(
        sentences: List<Sentence>,
        engine: EngineStreamingSource.VoiceEngineHandle,
        multiplier: Float,
    ): Int {
        // No extraA11ySilenceMs param — exercises the default value
        // path so the default's value is pinned by the test.
        val src = EngineStreamingSource(
            sentences = sentences,
            startSentenceIndex = 0,
            engine = engine,
            speed = 1f,
            pitch = 1f,
            engineMutex = Mutex(),
            punctuationPauseMultiplier = multiplier,
        )
        val chunk = src.nextChunk()
        src.close()
        return chunk?.trailingSilenceBytes ?: -1
    }
}
