package `in`.jphe.storyvox.llm.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

/**
 * PKCE pair shape tests (#181). Robolectric-backed because [PkcePair]
 * uses `android.util.Base64` (not java.util.Base64) for the URL-safe
 * encoding — same as the rest of `:core-llm`'s test suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PkcePairTest {

    @Test
    fun `verifier is URL-safe base64 within RFC 7636 length bounds`() {
        val pair = PkcePair.generate()
        // RFC 7636 §4.1: 43-128 unreserved-character verifier. 64 random
        // bytes encode to 86 URL-safe Base64 chars (no padding).
        assertTrue(pair.verifier.length in 43..128)
        // URL-safe alphabet: A-Z a-z 0-9 - _
        assertTrue(pair.verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `challenge is SHA256 of verifier in URL-safe base64`() {
        val pair = PkcePair.generate()
        val expected = android.util.Base64.encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(pair.verifier.toByteArray(Charsets.US_ASCII)),
            android.util.Base64.URL_SAFE or
                android.util.Base64.NO_PADDING or
                android.util.Base64.NO_WRAP,
        )
        assertEquals(expected, pair.challenge)
    }

    @Test
    fun `each generate yields a fresh pair`() {
        val a = PkcePair.generate()
        val b = PkcePair.generate()
        assertNotEquals(a.verifier, b.verifier)
        assertNotEquals(a.challenge, b.challenge)
    }
}
