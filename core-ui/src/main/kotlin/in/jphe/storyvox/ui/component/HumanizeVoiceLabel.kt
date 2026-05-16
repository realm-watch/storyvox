package `in`.jphe.storyvox.ui.component

/**
 * Issue #619 — humanize a raw `engine:voiceId` voice identifier into
 * a short, plain string suitable for chip labels and TTS subtitles.
 *
 * Examples:
 *   `piper:en_US-amy-medium`              → `Amy · medium quality`
 *   `piper:en_US-lessac-low`              → `Lessac · low quality`
 *   `piper:en_GB-alan-medium`             → `Alan · medium quality`
 *   `azure:en-US-BrianNeural`             → `Brian · US English`
 *   `azure:en-US-AvaMultilingualNeural`   → `Ava · multilingual`
 *   `azure:en-GB-RyanNeural`              → `Ryan · UK English`
 *   `voxsherpa:tier3/narrator-warm`       → `narrator-warm` (no proper-noun extractable)
 *   `kokoro:af_bella`                     → `Bella · Kokoro`
 *   `` (blank)                            → `` (blank, caller decides fallback copy)
 *   raw string without `:`                → returned untouched (engine-less id)
 *
 * The function is intentionally pure — no Hilt, no Android imports — so
 * it can be unit-tested in isolation and called from any module.
 *
 * Design philosophy: read the most-recognizable token as the speaker
 * name and surface ONE additional hint (locale or quality) so the user
 * can disambiguate "Amy low" from "Amy medium" without being shown the
 * raw token soup `en_US-amy-medium`.
 *
 * @param raw the raw `engine:voiceId` (or bare voiceId) string from
 *   the engine state. May be blank.
 * @return a humanized label suitable for an inline chip, never longer
 *   than ~32 characters in practice.
 */
fun humanizeVoiceLabel(raw: String): String {
    if (raw.isBlank()) return ""
    val (engineId, voiceId) = if (raw.contains(':')) {
        raw.substringBefore(':').lowercase() to raw.substringAfter(':')
    } else {
        "" to raw
    }

    // Azure naming convention: `<lang>-<REGION>-<Name>[Multilingual]Neural`.
    // Examples we want to humanize cleanly:
    //   en-US-BrianNeural               → Brian · US English
    //   en-US-AvaMultilingualNeural     → Ava · multilingual
    //   en-GB-RyanNeural                → Ryan · UK English
    //   fr-FR-DeniseNeural              → Denise · French
    val azureMatch = Regex("""^([a-z]{2,3})-([A-Z]{2})-([A-Z][a-z]+)(Multilingual)?(Neural|HD)?$""")
        .find(voiceId)
    if (azureMatch != null) {
        val lang = azureMatch.groupValues[1]
        val region = azureMatch.groupValues[2]
        val name = azureMatch.groupValues[3]
        val multilingual = azureMatch.groupValues[4].isNotEmpty()
        val locale = when {
            multilingual -> "multilingual"
            else -> azureLocaleLabel(lang, region)
        }
        return if (locale.isNotEmpty()) "$name · $locale" else name
    }

    // Piper naming convention: `<lang>_<REGION>-<name>-<quality>`.
    //   en_US-amy-medium                → Amy · medium quality
    //   en_US-lessac-low                → Lessac · low quality
    //   en_GB-alan-medium               → Alan · medium quality
    val piperMatch = Regex("""^([a-z]{2,3})_([A-Z]{2})-([a-z]+)-([a-z]+)$""").find(voiceId)
    if (piperMatch != null) {
        val rawName = piperMatch.groupValues[3]
        val quality = piperMatch.groupValues[4]
        val name = rawName.replaceFirstChar { it.uppercaseChar() }
        return "$name · $quality quality"
    }

    // Kokoro naming convention: `<lang_token>_<name>` (e.g. `af_bella`,
    // `am_michael`, `bf_emma`). The first 2 chars are locale + sex; the
    // tail is the speaker name. Best-effort — fall through if it doesn't
    // match.
    val kokoroMatch = Regex("""^[a-z]{2}_([a-z]+)$""").find(voiceId)
    if (kokoroMatch != null && engineId == "kokoro") {
        val name = kokoroMatch.groupValues[1].replaceFirstChar { it.uppercaseChar() }
        return "$name · Kokoro"
    }

    // VoxSherpa / sherpa-onnx custom voices: `tier3/narrator-warm`. We
    // can't extract a proper-noun name reliably; surface the descriptive
    // suffix without the tier prefix so the chip stays readable.
    if (voiceId.contains('/')) {
        return voiceId.substringAfter('/')
    }

    // Bare token without a recognizable shape — return as-is so the user
    // still sees *something* meaningful rather than the engine prefix.
    return voiceId
}

/**
 * Friendly locale label for the most common Azure voice locales. Keeps
 * the humanized string short — "US English" reads better than "en-US"
 * on a chip that already shows the speaker name.
 *
 * Falls back to the raw `lang-REGION` token so unknown locales still
 * render legibly without truncation.
 */
private fun azureLocaleLabel(lang: String, region: String): String = when ("$lang-$region") {
    "en-US" -> "US English"
    "en-GB" -> "UK English"
    "en-AU" -> "Australian English"
    "en-CA" -> "Canadian English"
    "en-IN" -> "Indian English"
    "fr-FR" -> "French"
    "fr-CA" -> "Canadian French"
    "de-DE" -> "German"
    "es-ES" -> "Spanish"
    "es-MX" -> "Mexican Spanish"
    "it-IT" -> "Italian"
    "ja-JP" -> "Japanese"
    "ko-KR" -> "Korean"
    "pt-BR" -> "Brazilian Portuguese"
    "zh-CN" -> "Mandarin"
    else -> "$lang-$region"
}
