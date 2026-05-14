package `in`.jphe.storyvox.feature.voicelibrary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #197 — the SAF picker in [VoiceAdvancedExpander] hands the
 * raw `Uri.toString()` to [resolveLexiconPath] before persistence.
 * The resolver guards two failure modes that the upstream codec in
 * [`in.jphe.storyvox.data.SettingsRepositoryUiImpl`] silently drops:
 *
 *  - empty / blank strings — would persist as a phantom entry that
 *    decodes back to an empty value and looks like "set" to the UI
 *  - paths containing `;` or `=` — collide with the flat-string
 *    `voiceId=path;voiceId=path` codec; the second `;` would split
 *    one entry into two corrupt records on decode
 *
 * Pre-empting them at the SAF callback turns a silent
 * "your setting vanished" mystery into a hard no-op the user can
 * recover from (re-pick a file with a sane path).
 *
 * This test stays in pure-JVM territory — no Android ContentResolver,
 * no mocked Uri — by exercising the resolver function directly with
 * the shape of strings real Android SAF returns.
 */
class LexiconPathSafParseTest {

    @Test
    fun `accepts content URI shape from SAF OpenDocument`() {
        val saf = "content://com.android.externalstorage.documents/document/primary%3Alexicons%2Fbella.lexicon"
        assertEquals(saf, resolveLexiconPath(saf))
    }

    @Test
    fun `accepts plain absolute path`() {
        // Test fixture / instrumentation path — sometimes returned by
        // older file pickers or by direct File.toString() callers.
        val absolute = "/data/user/0/in.jphe.storyvox/files/lexicons/bella.lexicon"
        assertEquals(absolute, resolveLexiconPath(absolute))
    }

    @Test
    fun `trims leading and trailing whitespace`() {
        // Uri.toString() shouldn't leak whitespace but defense-in-depth
        // is cheap; users pasting paths through ADB / debug overlays
        // routinely include trailing newlines.
        assertEquals(
            "/lex/bella.lexicon",
            resolveLexiconPath("  /lex/bella.lexicon\n"),
        )
    }

    @Test
    fun `rejects empty input`() {
        assertNull(resolveLexiconPath(""))
        assertNull(resolveLexiconPath("   "))
    }

    @Test
    fun `rejects path containing semicolon`() {
        // `;` is the entry separator in the per-voice map codec — a
        // path containing one would split into two corrupt records.
        assertNull(resolveLexiconPath("/lex/bella;evil.lexicon"))
    }

    @Test
    fun `rejects path containing equals`() {
        // `=` is the key-value separator — a path containing one would
        // shift everything after into the value half on decode.
        assertNull(resolveLexiconPath("/lex/version=2/bella.lexicon"))
    }

    @Test
    fun `accepts percent-encoded SAF URI with no raw codec collisions`() {
        // SAF percent-encodes `;` and `=` to %3B / %3D, so they round-
        // trip safely. This is the whole reason we accept content://
        // URIs verbatim rather than rewriting them.
        val saf = "content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fnames%253B%253D.lexicon"
        assertEquals(saf, resolveLexiconPath(saf))
    }
}
