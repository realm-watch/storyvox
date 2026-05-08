package `in`.jphe.storyvox.playback.voice

/**
 * Pure derivations from a voice id to its [QualityLevel] tier. Pulled
 * out of [VoiceCatalog] so the rules are unit-testable without spinning
 * up Android/Hilt — and so the catalog reads as data, not logic.
 *
 * Two engines, two rules:
 * - **Piper**: every Piper model name ends in `-low`, `-medium`, or
 *   `-high`. Parse the suffix.
 * - **Kokoro**: there's no per-voice quality variant — all 53 Kokoro
 *   speakers share one ~380 MB bundle. Tier is therefore curated:
 *   [STUDIO_KOKORO_IDS] lists the top-graded speakers (Studio); every
 *   other Kokoro voice is [QualityLevel.High].
 */
object VoiceTier {

    /**
     * Curated set of Kokoro voice ids surfaced as **Studio** tier in
     * the picker.
     *
     * Picked to match the highest-graded speakers in upstream
     * `hexgrad/Kokoro-82M` voice grades — these are the Kokoro speakers
     * that reliably produce broadcast-grade audio with negligible
     * artifacts. Stored as a `Set` for O(1) lookup; the canonical voice
     * ids in [VoiceCatalog.kokoroEntries] follow the
     * `kokoro_<name>_<locale>_<speakerId>` convention.
     *
     * If the kokoro roster grows or the upstream grading shifts, edit
     * this set — the catalog and UI will pick it up automatically via
     * [forKokoroId]. Keep the list short: Studio is meant to be a
     * curated peak, not a second "High" bucket.
     */
    val STUDIO_KOKORO_IDS: Set<String> = setOf(
        "kokoro_heart_en_US_3",   // upstream af_heart — grade A
        "kokoro_bella_en_US_2",   // upstream af_bella — grade A-
        "kokoro_nicole_en_US_6",  // upstream af_nicole — grade B-/A
    )

    /**
     * Parse a Piper model identifier's tier suffix. Piper voice ids in
     * the catalog look like `piper_<name>_<locale>_<tier>` where
     * `<tier>` is `low | medium | high`. Returns [QualityLevel.Medium]
     * as a safe fallback if the id is malformed — Medium is the most
     * common tier in the catalog so it minimises surprise.
     *
     * Tolerates extra trailing tokens (e.g. a hypothetical
     * `piper_foo_en_US_medium_v2`) by scanning from the end for the
     * first recognised tier token. That keeps this parser future-proof
     * against catalog schema additions without forcing a re-test.
     */
    fun forPiperId(voiceId: String): QualityLevel {
        val tokens = voiceId.split('_')
        for (i in tokens.indices.reversed()) {
            when (tokens[i].lowercase()) {
                "high" -> return QualityLevel.High
                "medium" -> return QualityLevel.Medium
                "low" -> return QualityLevel.Low
            }
        }
        return QualityLevel.Medium
    }

    /**
     * Map a Kokoro voice id to its tier. Studio is curated via
     * [STUDIO_KOKORO_IDS]; everything else is [QualityLevel.High] —
     * Kokoro's baseline quality is genuinely good, so a "default High"
     * isn't generous, it's accurate.
     */
    fun forKokoroId(voiceId: String): QualityLevel =
        if (voiceId in STUDIO_KOKORO_IDS) QualityLevel.Studio else QualityLevel.High
}
