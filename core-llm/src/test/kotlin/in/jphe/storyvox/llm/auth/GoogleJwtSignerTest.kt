package `in`.jphe.storyvox.llm.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Issue #219 — round-trip test for the JWT signer. Loads the
 * test SA JSON, signs a JWT, then verifies the signature against
 * the matching PUBLIC key (also in test/resources/). If signing or
 * the base64-url encoding are wrong, verification fails — same
 * cryptographic chain Google's token endpoint will use to validate
 * the JWT in production.
 *
 * Robolectric runner because [GoogleJwtSigner] uses `android.util.Base64`
 * for the URL-safe encoding (matches PkcePairTest's pattern).
 */
@RunWith(RobolectricTestRunner::class)
class GoogleJwtSignerTest {

    @Test
    fun `signed JWT verifies against the matching public key`() {
        val sa = GoogleServiceAccount.parse(readFixture("test-sa.json"))
        val now = 1_700_000_000L  // fixed clock so claim contents are stable.
        val jwt = GoogleJwtSigner.sign(
            sa = sa,
            scope = GoogleServiceAccount.SCOPE_CLOUD_PLATFORM,
            nowSecondsSinceEpoch = now,
        )
        // Shape: three base64url segments separated by dots.
        val parts = jwt.split('.')
        assertEquals("JWT must have header.payload.sig", 3, parts.size)

        // Header should decode to {alg:RS256, typ:JWT}. Use stdlib
        // (java.util.Base64) here in the test, not android.util.Base64,
        // because we want to verify the URL-safe encoding directly.
        val header = String(decodeUrlSafe(parts[0]), Charsets.UTF_8)
        assertTrue(header.contains("\"alg\":\"RS256\""))
        assertTrue(header.contains("\"typ\":\"JWT\""))

        // Payload should contain the iss/scope/aud/iat/exp claims.
        val payload = String(decodeUrlSafe(parts[1]), Charsets.UTF_8)
        assertTrue(payload.contains("\"iss\":\"${sa.clientEmail}\""))
        assertTrue(payload.contains("\"aud\":\"${sa.tokenUri}\""))
        assertTrue(payload.contains("\"scope\":\"${GoogleServiceAccount.SCOPE_CLOUD_PLATFORM}\""))
        assertTrue(payload.contains("\"iat\":$now"))
        assertTrue(payload.contains("\"exp\":${now + 3600}"))

        // Verify the RSA-SHA256 signature against the public key. If
        // any of: PKCS#8 parsing, payload assembly, or base64 padding
        // is off, this call returns false.
        val publicKey = readFixture("test-sa-public.pem")
        val pubBody = publicKey
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        val pubKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pubBody)))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(pubKey)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.UTF_8))
        assertTrue(
            "Signature failed to verify with the SA's public key",
            verifier.verify(decodeUrlSafe(parts[2])),
        )
    }

    @Test
    fun `lifetime is clamped to Google's 3600s ceiling`() {
        val sa = GoogleServiceAccount.parse(readFixture("test-sa.json"))
        val now = 1_700_000_000L
        // Caller asks for 2 hours; signer must clamp to 3600s.
        val jwt = GoogleJwtSigner.sign(
            sa = sa,
            scope = GoogleServiceAccount.SCOPE_CLOUD_PLATFORM,
            nowSecondsSinceEpoch = now,
            lifetimeSeconds = 7200L,
        )
        val payload = String(decodeUrlSafe(jwt.split('.')[1]), Charsets.UTF_8)
        assertTrue(
            "exp claim was not clamped: $payload",
            payload.contains("\"exp\":${now + 3600}"),
        )
    }

    /** Decode a URL-safe base64 segment with no padding — what the
     *  signer emits per RFC 7515. */
    private fun decodeUrlSafe(s: String): ByteArray =
        Base64.getUrlDecoder().decode(padBase64(s))

    /** Java's stdlib URL decoder requires padding; the signer drops
     *  it per spec, so we re-add the right number of '=' chars. */
    private fun padBase64(s: String): String {
        val mod = s.length % 4
        return if (mod == 0) s else s + "=".repeat(4 - mod)
    }

    private fun readFixture(name: String): String =
        javaClass.classLoader!!.getResource(name)!!.readText()
}
