package `in`.jphe.storyvox.source.azure

/**
 * Wraps a sentence of plain chapter text in the Azure-flavoured SSML
 * envelope expected by the cognitive-services /v1 endpoint.
 *
 * Output shape (whitespace trimmed for clarity):
 * ```
 * <speak version="1.0" xml:lang="en-US"
 *        xmlns="http://www.w3.org/2001/10/synthesis">
 *   <voice name="en-US-AvaDragonHDLatestNeural">
 *     <prosody rate="+10%" pitch="-5%">Hello world.</prosody>
 *   </voice>
 * </speak>
 * ```
 *
 * **Defensive XML escaping.** Chapter text reaches us downstream of
 * jsoup / commonmark normalization but the upstream HTML decoder can
 * still let `<`, `>`, `&`, `"`, or `'` through into the sentence
 * payload — Royal Road's editor inserts smart quotes and the
 * occasional stray entity. Any of those characters in raw form would
 * either break the SSML parse outright (Azure replies with a 400 and
 * a "the request is not valid XML" body) or, worse, let user-controlled
 * content escape the prosody element and inject sibling SSML.
 *
 * The trust-boundary statement: chapter text is **untrusted** for SSML
 * purposes. We escape every character that has SSML meaning. We do
 * NOT pass through SSML markup the chapter author embedded — that's
 * "Out of scope: SSML pass-through" in Solara's spec, deferred
 * indefinitely.
 *
 * **Prosody mapping.** The storyvox speed slider runs 0.75× to 2.5×.
 * SSML's `prosody rate` accepts percent-relative ("+50%") or named
 * (`x-slow`/`slow`/`medium`/`fast`/`x-fast`). Percent is more granular
 * and matches what Piper's speed param expresses. Pitch is the same
 * shape, but the slider doesn't currently expose pitch — every caller
 * passes 1.0f, which produces "0%" (the no-op). Reserving the pitch
 * dimension means a future "voice characterization" feature can pass
 * a non-1 pitch without changing this signature.
 */
internal object AzureSsmlBuilder {

    /**
     * Build the SSML body POSTed to Azure's TTS endpoint.
     *
     * @param text   the sentence to synthesize. XML-escaped before
     *               insertion. Empty / blank text produces an SSML
     *               envelope with an empty prosody — Azure responds
     *               with a 200 and zero bytes of PCM, which the
     *               engine handle treats as a skipped sentence.
     * @param voiceName  Azure voice id (e.g. `en-US-AvaDragonHDLatestNeural`).
     *                   Surfaced verbatim in the `<voice name=...>` attribute.
     * @param speed  storyvox speed multiplier (1.0 = normal). Mapped
     *               to a percent-relative SSML rate. Clamped client-side
     *               to ±50% to stay within Azure's accepted range.
     * @param pitch  storyvox pitch multiplier (1.0 = normal). Mapped
     *               to a percent-relative SSML pitch. Clamped to ±50%.
     * @param language language attribute on the root `<speak>` element.
     *                 Azure derives lexicon from the voice id, but the
     *                 root attribute is required by the SSML spec; we
     *                 default to `en-US` since storyvox's curated
     *                 catalog is English-first. Future-proof for
     *                 multilingual voices that pick a non-en lexicon.
     */
    fun build(
        text: String,
        voiceName: String,
        speed: Float = 1.0f,
        pitch: Float = 1.0f,
        language: String = "en-US",
    ): String {
        val rate = formatPercent(speed)
        val pitchPct = formatPercent(pitch)
        val escapedText = text.escapeForSsml()
        val escapedVoice = voiceName.escapeAttr()
        val escapedLang = language.escapeAttr()
        // Stay on one line — Azure's parser tolerates whitespace, but
        // keeping the body compact reduces the wire bytes (every
        // request pays for these on the egress side, even though
        // billing only counts the inner text).
        return "<speak version=\"1.0\" xml:lang=\"$escapedLang\" " +
            "xmlns=\"http://www.w3.org/2001/10/synthesis\">" +
            "<voice name=\"$escapedVoice\">" +
            "<prosody rate=\"$rate\" pitch=\"$pitchPct\">$escapedText</prosody>" +
            "</voice></speak>"
    }

    /** Turn a 1.0-centered multiplier into a clamped SSML percent. */
    private fun formatPercent(multiplier: Float): String {
        val percent = ((multiplier - 1f) * 100f).toInt().coerceIn(-50, 50)
        return if (percent >= 0) "+$percent%" else "$percent%"
    }
}

/**
 * XML-escape a string for inclusion as character data inside an SSML
 * element. Doesn't escape `'` or `"` (those only need escaping in
 * attribute contexts) — we use [escapeAttr] for the voice name and
 * language attributes.
 *
 * Order matters: `&` first, then everything else, otherwise the `&`
 * we emit for `<` would itself get re-escaped.
 */
internal fun String.escapeForSsml(): String {
    if (isEmpty()) return this
    val sb = StringBuilder(length)
    for (c in this) {
        when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

/**
 * Escape a string for inclusion in a double-quoted XML attribute
 * value. Same as [escapeForSsml] plus `"` (the quote that terminates
 * the attribute) and `'` for symmetry — voice names and language
 * codes shouldn't contain either, but defensive escaping costs ~zero
 * and prevents an attacker-controlled voice id (unlikely but possible
 * if a future "fetch voice list from Azure" path doesn't validate)
 * from breaking out of the attribute.
 */
internal fun String.escapeAttr(): String {
    if (isEmpty()) return this
    val sb = StringBuilder(length)
    for (c in this) {
        when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&apos;")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}
