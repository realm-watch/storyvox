package `in`.jphe.storyvox.llm.auth

import android.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Issue #219 — RFC 7515 / RFC 7519 JWT signer for the JWT-bearer
 * OAuth grant (RFC 7523). Google's `oauth2.googleapis.com/token`
 * endpoint accepts a self-signed JWT proving the service account's
 * identity and trades it for a short-lived access token.
 *
 * Algorithm is fixed at `RS256` (RSASSA-PKCS1-v1_5 with SHA-256) —
 * the only algorithm Google's service-account JWT flow accepts. The
 * private key is parsed once per call rather than cached because
 * (a) [GoogleServiceAccount] instances are short-lived (we re-parse
 * the SA JSON each time the access token expires) and (b) caching
 * `PrivateKey` instances complicates secure-wipe on sign-out.
 *
 * Hand-rolled rather than pulling in nimbus-jose-jwt / google-auth —
 * the spec wants minimal dependency surface, and this is genuinely
 * tiny: PKCS#8-PEM → RSAPrivateKey → Signature.sign(). The four
 * unit-test cases in [GoogleJwtSignerTest] cover the wire shape.
 *
 * URL-safe Base64 (no padding) per RFC 7515 §3 — Java's stdlib
 * `Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP` matches.
 */
internal object GoogleJwtSigner {

    /** Standard `{alg, typ}` header for an RS256-signed JWT. The
     *  `typ` field is optional per RFC 7519 but Google's token
     *  endpoint accepts (and the gcloud CLI sends) it. */
    private val HEADER = buildJsonObject {
        put("alg", JsonPrimitive("RS256"))
        put("typ", JsonPrimitive("JWT"))
    }

    /**
     * Sign a JWT claiming the given [scope] from [sa].
     *
     * @param sa the parsed service-account JSON.
     * @param scope OAuth scope(s) — single space-separated string.
     * @param nowSecondsSinceEpoch caller-injected clock for tests.
     *        In production pass [System.currentTimeMillis] / 1000.
     * @param lifetimeSeconds JWT validity window. Google caps this at
     *        3600 (one hour); we default to that so a token-source
     *        cache aligned to the same window doesn't have to refresh
     *        before the JWT itself expires.
     */
    fun sign(
        sa: GoogleServiceAccount,
        scope: String,
        nowSecondsSinceEpoch: Long,
        lifetimeSeconds: Long = MAX_LIFETIME_SECONDS,
    ): String {
        val claims = buildClaims(sa, scope, nowSecondsSinceEpoch, lifetimeSeconds)
        val signingInput = base64UrlEncode(HEADER.toString().toByteArray(Charsets.UTF_8)) +
            "." +
            base64UrlEncode(claims.toString().toByteArray(Charsets.UTF_8))
        val signature = rsaSha256(sa.privateKey, signingInput.toByteArray(Charsets.UTF_8))
        return signingInput + "." + base64UrlEncode(signature)
    }

    /**
     * Build the RFC 7523 §2.1 claims payload. Google's token
     * endpoint requires:
     *   - `iss`: SA email
     *   - `scope`: requested OAuth scopes
     *   - `aud`: the token URI itself (NOT the API endpoint)
     *   - `iat`/`exp`: issued-at, expiry. exp - iat ≤ 3600.
     */
    private fun buildClaims(
        sa: GoogleServiceAccount,
        scope: String,
        nowSeconds: Long,
        lifetime: Long,
    ): JsonObject = buildJsonObject {
        put("iss", JsonPrimitive(sa.clientEmail))
        put("scope", JsonPrimitive(scope))
        put("aud", JsonPrimitive(sa.tokenUri))
        put("iat", JsonPrimitive(nowSeconds))
        put("exp", JsonPrimitive(nowSeconds + lifetime.coerceAtMost(MAX_LIFETIME_SECONDS)))
    }

    /**
     * Sign [payload] with [pemPrivateKey] using RSA-SHA256. The PEM
     * has to be PKCS#8 (Google's JSON exports are); PKCS#1 ("BEGIN
     * RSA PRIVATE KEY") would need a different parser path, but
     * [GoogleServiceAccount.parse] rejects those upstream.
     */
    private fun rsaSha256(pemPrivateKey: String, payload: ByteArray): ByteArray {
        val pk = parsePkcs8PrivateKey(pemPrivateKey)
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(pk)
        sig.update(payload)
        return sig.sign()
    }

    /** Strip PEM armour + whitespace, base64-decode, hand to
     *  [KeyFactory]. Exposed `internal` so tests can verify a
     *  signature with the matching public key without round-tripping
     *  through the JWT. */
    internal fun parsePkcs8PrivateKey(pem: String): PrivateKey {
        val body = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        // Standard base64 (NOT url-safe) — PEM bodies use the regular
        // alphabet. Android's Base64 + NO_WRAP handles the strict
        // mode we need here.
        val der = Base64.decode(body, Base64.DEFAULT)
        return KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(der))
    }

    /** RFC 7515 §3 — URL-safe Base64, no padding, no line wrap. */
    internal fun base64UrlEncode(bytes: ByteArray): String =
        Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )

    /** Google's hard ceiling for SA-JWT lifetime. */
    private const val MAX_LIFETIME_SECONDS = 3600L
}
