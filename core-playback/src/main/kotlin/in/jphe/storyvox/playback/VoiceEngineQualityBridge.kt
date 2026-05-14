package `in`.jphe.storyvox.playback

import com.CodeBySonu.VoxSherpa.KokoroEngine
import com.CodeBySonu.VoxSherpa.VoiceEngine

/**
 * Issue #193 — bridge between storyvox's user-facing Settings toggle
 * and the two VoxSherpa-TTS engine classes' `sonicQuality` static
 * fields. Both engines instantiate a fresh `Sonic` per
 * `generateAudioPCM` call and read `sonicQuality` at construction
 * time, so flipping these fields takes effect on the *next* chapter
 * render (any in-flight render keeps its existing Sonic instance).
 *
 * Kept here in `:core-playback` rather than `:app` because the
 * VoxSherpa-TTS dep lives here. `:app`'s SettingsRepositoryUiImpl
 * routes through this bridge to avoid a direct compile-time
 * dependency on the Java engine classes.
 *
 * Extended in v2.7.14 (#197 + #198) with two per-voice knobs that
 * Settings re-applies whenever the active voice changes:
 *
 * - [applyLexicon] — comma-separated `.lexicon` file paths for
 *   per-token IPA / X-SAMPA phoneme overrides. Engine reads at
 *   construction time, so the caller must apply BEFORE the next
 *   voice loadModel() to take effect. Affects both Piper and
 *   Kokoro (sherpa-onnx OfflineTts{Vits,Kokoro}ModelConfig).
 *
 * - [applyPhonemizerLang] — Kokoro-only language override for the
 *   phonemizer. Useful when an English Kokoro voice needs to
 *   pronounce embedded Spanish/Japanese/etc. dialogue. Piper
 *   ignores the field (its language is baked into the voice
 *   metadata).
 *
 * Both bridge methods are pure static-field writes — no engine
 * reload, no model reload, just a volatile write. The caller is
 * responsible for ensuring the engine instantiates *after* the
 * write (storyvox's [VoiceManager] reloads on active-voice change).
 */
object VoiceEngineQualityBridge {

    /**
     * Apply the user's "high-quality pitch interpolation" preference
     * to both engines. true → quality=1 (smoother, ~20% slower);
     * false → quality=0 (Sonic's upstream default — virtually as
     * good at neutral pitch, gritty at non-neutral).
     */
    fun applyPitchQuality(highQuality: Boolean) {
        val q = if (highQuality) 1 else 0
        VoiceEngine.sonicQuality = q
        KokoroEngine.sonicQuality = q
    }

    /**
     * #197 — write the per-voice lexicon override to both engines.
     * Empty / null = no override (engine uses its built-in lexicon).
     * Non-empty = comma-separated absolute paths to `.lexicon` files
     * (sherpa-onnx parses each one and merges in order; later
     * entries win on duplicate keys).
     *
     * Both Piper and Kokoro read the field at construction time:
     * - Piper:  OfflineTtsVitsModelConfig.setLexicon(paths)
     * - Kokoro: OfflineTtsKokoroModelConfig.setLexicon(paths)
     *
     * Storyvox wiring: per-voice overrides live in a
     * `Map<voiceId, path>` in DataStore. On active-voice change,
     * [`in.jphe.storyvox.data.SettingsRepositoryUiImpl`] reads the
     * map for the new voice and calls this method *before* the
     * VoiceManager triggers loadModel(). Switching back to a voice
     * with no override clears it by calling `applyLexicon("")`.
     */
    fun applyLexicon(paths: String) {
        val v = paths.ifBlank { "" }
        VoiceEngine.voiceLexicon = v
        KokoroEngine.voiceLexicon = v
    }

    /**
     * #198 — write the Kokoro phonemizer language override.
     * Empty / null = no override (Kokoro derives the language from
     * the active speaker's voice metadata, e.g. `en` for `af_bella`,
     * `es` for `ef_dora`).
     *
     * Non-empty values should be one of Kokoro's documented language
     * codes (see [KOKORO_PHONEMIZER_LANGS]). The engine quietly
     * falls back to the voice's native language for unrecognized
     * codes, so a typo doesn't crash — it just doesn't take effect.
     *
     * Only KokoroEngine reads this field; VoiceEngine (Piper) has
     * no equivalent setter because Piper voices are per-language.
     * We still set it as a no-op on VoiceEngine to keep the bridge
     * symmetric with [applyLexicon] — except VoiceEngine doesn't
     * expose `phonemizerLang` at all, so we only touch Kokoro.
     */
    fun applyPhonemizerLang(lang: String) {
        KokoroEngine.phonemizerLang = lang.ifBlank { "" }
    }
}

/**
 * Documented Kokoro phonemizer language codes for #198's override
 * picker. Pulled from sherpa-onnx's Kokoro VOICES.md — the codes the
 * upstream phonemizer recognizes. Surfaced as a public list so the
 * Settings UI dropdown can render it without duplicating the strings,
 * and the validation test can assert UI / persistence stays in sync.
 *
 * The list is conservative — it omits experimental codes and the
 * Kokoro variants that ship with no speaker mapped. If sherpa-onnx
 * adds a language, we extend this list explicitly rather than wiring
 * a dynamic probe (the engine doesn't expose its supported-langs
 * table at runtime).
 */
val KOKORO_PHONEMIZER_LANGS: List<String> = listOf(
    "en",  // English (default for af_*, am_*, bf_*, bm_* voices)
    "es",  // Spanish (ef_*, em_* speakers)
    "fr",  // French (ff_* speakers)
    "pt",  // Portuguese (pf_*, pm_* speakers)
    "it",  // Italian (if_*, im_* speakers)
    "de",  // German (no native speakers yet; phonemizer-only)
    "hi",  // Hindi (hf_*, hm_* speakers)
    "zh",  // Mandarin Chinese (zf_*, zm_* speakers)
    "ja",  // Japanese (jf_*, jm_* speakers)
)
