package `in`.jphe.storyvox.llm.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PKCE (RFC 7636) verifier + S256 challenge pair for the Anthropic
 * Teams OAuth flow (#181).
 *
 * The verifier is a cryptographically random 64-byte URL-safe Base64
 * string; the challenge is `BASE64URL(SHA256(verifier))`. Per RFC 7636
 * §4.2, the verifier MUST be 43-128 characters; 64 random bytes
 * encode to exactly 86 URL-safe Base64 characters (no padding).
 */
data class PkcePair(
    val verifier: String,
    val challenge: String,
) {
    companion object {
        /**
         * Generate a fresh PKCE pair. `SecureRandom` is the JVM standard
         * RNG — backed by `/dev/urandom` on Android, which is the right
         * source for OAuth nonces.
         */
        fun generate(): PkcePair {
            val bytes = ByteArray(64).also { SecureRandom().nextBytes(it) }
            val verifier = Base64.encodeToString(
                bytes,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
            )
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
            val challenge = Base64.encodeToString(
                digest,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
            )
            return PkcePair(verifier = verifier, challenge = challenge)
        }
    }
}
