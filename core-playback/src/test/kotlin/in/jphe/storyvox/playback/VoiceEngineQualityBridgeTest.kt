package `in`.jphe.storyvox.playback

import com.CodeBySonu.VoxSherpa.KokoroEngine
import com.CodeBySonu.VoxSherpa.VoiceEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Issues #197 + #198 — verify the storyvox-side bridge writes the
 * right VoxSherpa static fields, including the empty-string clear
 * path. We touch the Java engine classes directly via their public
 * static volatile fields — same access pattern the bridge itself
 * uses, so a refactor that breaks the field names breaks both the
 * production write and this assertion in lockstep.
 *
 * The bridge writes are pure static-field assignments — no
 * instantiation, no Android dependencies, no Robolectric needed.
 * Runs on the plain JVM test classpath.
 *
 * Note we cannot assert that engine *construction* reads the field
 * — that requires `OfflineTtsConfig` + the sherpa-onnx JNI, which
 * are Android-only. The VoxSherpa upstream side has its own
 * EngineStaticKnobsTest (`app/src/test/java/com/CodeBySonu/VoxSherpa/`)
 * that covers the construction-time read; this test owns the
 * storyvox-bridge half of the contract.
 */
class VoiceEngineQualityBridgeTest {

    /** Snapshot of upstream-default field values, restored after each
     *  test. Tests run in parallel within one JVM in some Gradle
     *  configurations; keeping the static fields back at the defaults
     *  on @After prevents cross-test order-dependence. */
    private var savedVoiceLexicon = ""
    private var savedKokoroLexicon = ""
    private var savedPhonemizerLang = ""

    @Before
    fun captureBaselines() {
        savedVoiceLexicon = VoiceEngine.voiceLexicon ?: ""
        savedKokoroLexicon = KokoroEngine.voiceLexicon ?: ""
        savedPhonemizerLang = KokoroEngine.phonemizerLang ?: ""
    }

    @After
    fun restoreBaselines() {
        VoiceEngine.voiceLexicon = savedVoiceLexicon
        KokoroEngine.voiceLexicon = savedKokoroLexicon
        KokoroEngine.phonemizerLang = savedPhonemizerLang
    }

    @Test fun `applyLexicon writes to both engine static fields`() {
        VoiceEngineQualityBridge.applyLexicon("/data/user/0/in.jphe.storyvox/files/lexicons/v1.lexicon")
        assertEquals(
            "Piper voiceLexicon must reflect the bridge write",
            "/data/user/0/in.jphe.storyvox/files/lexicons/v1.lexicon",
            VoiceEngine.voiceLexicon,
        )
        assertEquals(
            "Kokoro voiceLexicon must reflect the bridge write",
            "/data/user/0/in.jphe.storyvox/files/lexicons/v1.lexicon",
            KokoroEngine.voiceLexicon,
        )
    }

    @Test fun `applyLexicon with empty string clears both engines`() {
        // Seed both fields with non-empty values so an effective clear
        // (vs a no-op) is observable.
        VoiceEngine.voiceLexicon = "/seed-piper.lexicon"
        KokoroEngine.voiceLexicon = "/seed-kokoro.lexicon"

        VoiceEngineQualityBridge.applyLexicon("")
        assertEquals("", VoiceEngine.voiceLexicon)
        assertEquals("", KokoroEngine.voiceLexicon)
    }

    @Test fun `applyLexicon with blank string normalizes to empty`() {
        VoiceEngine.voiceLexicon = "/seed-piper.lexicon"
        KokoroEngine.voiceLexicon = "/seed-kokoro.lexicon"

        // ifBlank on the bridge side maps "   " to "".
        VoiceEngineQualityBridge.applyLexicon("   ")
        assertEquals("", VoiceEngine.voiceLexicon)
        assertEquals("", KokoroEngine.voiceLexicon)
    }

    @Test fun `applyPhonemizerLang writes to KokoroEngine only`() {
        // Piper's VoiceEngine has no phonemizerLang field — the bridge
        // doesn't touch it. We assert the Kokoro field changes and
        // VoiceEngine.voiceLexicon (the only static field we can name
        // on VoiceEngine for the lang concept's negative test) is
        // unchanged by the lang write.
        val piperLexiconBefore = VoiceEngine.voiceLexicon
        VoiceEngineQualityBridge.applyPhonemizerLang("es")
        assertEquals("es", KokoroEngine.phonemizerLang)
        // Piper field untouched by the lang write.
        assertEquals(piperLexiconBefore, VoiceEngine.voiceLexicon)
    }

    @Test fun `applyPhonemizerLang with empty string clears Kokoro override`() {
        KokoroEngine.phonemizerLang = "es"

        VoiceEngineQualityBridge.applyPhonemizerLang("")
        assertEquals("", KokoroEngine.phonemizerLang)
    }

    @Test fun `KOKORO_PHONEMIZER_LANGS contains expected codes`() {
        // Guards the contract the Settings UI dropdown relies on. If
        // a code disappears (typo, refactor), the storyvox-side test
        // catches it before users see a missing chip on their
        // active-voice expander.
        val expected = setOf("en", "es", "fr", "pt", "it", "de", "hi", "zh", "ja")
        assertEquals(expected, KOKORO_PHONEMIZER_LANGS.toSet())
    }
}
