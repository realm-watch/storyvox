package `in`.jphe.storyvox.playback.cache

import java.security.MessageDigest

/**
 * Identity of one cached chapter render. Six fields because the rendered
 * audio depends on every one:
 *  - [chapterId] — different chapters obviously different audio.
 *  - [voiceId] — same chapter under "cori" vs "amy" sounds different.
 *  - [speedHundredths] — `currentSpeed × 100`, rounded. Quantizing to
 *    hundredths means 1.00× and 1.02× share a key; 1.00× and 1.25× don't.
 *    The spec accepts this finite-set tradeoff (most users settle on
 *    1.0/1.25/1.5/1.75/2.0×, ≤ 5 distinct keys per chapter+voice).
 *  - [pitchHundredths] — same quantization story for pitch.
 *  - [chunkerVersion] — see [`in`.jphe.storyvox.playback.tts.CHUNKER_VERSION].
 *    A chunker change shifts sentence boundaries which makes the sidecar
 *    index wrong; bumping this invalidates without DB migration.
 *  - [pronunciationDictHash] — see [`in`.jphe.storyvox.data.repository.pronunciation.PronunciationDict.contentHash].
 *    The dictionary mutates the string fed to `engine.generateAudioPCM`,
 *    so the rendered PCM differs whenever the dict changes. Including
 *    the content hash here means adding/editing/removing an entry
 *    self-evicts the affected on-disk renders without a manual sweep
 *    (issue #135). For users with no dictionary configured this is
 *    `PronunciationDict.EMPTY.contentHash` — a stable constant — so
 *    pre-#135 caches that get re-keyed on first launch will all
 *    invalidate exactly once and rebuild cleanly.
 *
 * [fileBaseName] is a 64-char SHA-256 of `toString()`. Stable across
 * runs (no salt, no time component); a key derived twice with the same
 * fields hashes to the same string. The base name is opaque on disk
 * — never decoded back. Listing files in `pcm-cache/` and matching
 * basenames to known keys is a tombstone-driven sweep that lives in
 * `PcmCache`, not here.
 */
data class PcmCacheKey(
    val chapterId: String,
    val voiceId: String,
    val speedHundredths: Int,
    val pitchHundredths: Int,
    val chunkerVersion: Int,
    val pronunciationDictHash: Int,
) {
    /**
     * 64-char hex SHA-256 of `toString()`. Used as the on-disk basename
     * (`<sha>.pcm`, `<sha>.idx.json`, `<sha>.meta.json`).
     *
     * Deterministic — same key → same basename across runs and devices.
     * The Kotlin data-class `toString` separates fields with `, ` which
     * gives enough disambiguation between, e.g., a chapterId containing
     * a comma and a sibling key whose voiceId starts with one — the
     * `chapterId=` / `voiceId=` field-name prefixes are unambiguous.
     */
    fun fileBaseName(): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(toString().toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Quantize a Float speed/pitch to hundredths (`value × 100`,
         *  rounded half-up). The cache key uses Ints to be portable
         *  across float-rounding noise. */
        fun quantize(value: Float): Int = Math.round(value * 100f)
    }
}
