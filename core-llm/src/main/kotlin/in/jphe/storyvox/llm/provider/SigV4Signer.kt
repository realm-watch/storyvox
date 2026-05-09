package `in`.jphe.storyvox.llm.provider

import java.net.URI
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 (HMAC-SHA256) signer. Pure stdlib — `javax.crypto`
 * + `java.security`. No AWS SDK; that's a ~5 MB dependency we don't need.
 *
 * Direct port of `_aws_sign_v4` in
 * `cloud-chat-assistant/llm_stream.py`. The wire format is documented at
 *   https://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html
 *
 * We sign three headers (host, x-amz-date, content-type) and only support
 * application/json POST bodies — that's all Bedrock converse-stream uses.
 * Keeping the signer narrow avoids the long tail of canonical-request
 * edge cases (empty paths, query strings with multiple values, header
 * casing) that the full AWS SDK has to handle.
 */
internal object SigV4Signer {

    private const val ALGO = "AWS4-HMAC-SHA256"
    private const val HMAC_SHA256 = "HmacSHA256"
    private const val SHA256 = "SHA-256"

    /**
     * Sign a request and return the headers to attach. The returned map
     * has lower-case keys; callers add them with `Request.Builder.header(k, v)`.
     *
     * @param method HTTP verb. Always "POST" for Bedrock converse-stream.
     * @param url full request URL — host + path are extracted; query
     *        string is signed if present (Bedrock doesn't use one).
     * @param payload request body bytes (already encoded). The payload
     *        hash is part of the canonical request.
     * @param accessKey AWS access key id (`AKIA…`).
     * @param secretKey AWS secret access key (the 40-char secret).
     * @param region e.g. "us-east-1".
     * @param service AWS service identifier — "bedrock" for converse-stream.
     * @param date the request timestamp; injected so tests can pin it. The
     *        production caller passes `Date()` (now).
     */
    fun sign(
        method: String,
        url: String,
        payload: ByteArray,
        accessKey: String,
        secretKey: String,
        region: String,
        service: String = "bedrock",
        date: Date = Date(),
    ): Map<String, String> {
        val uri = URI(url)
        // RFC 3986 unreserved + path-safe: AWS canonical-uri keeps path-safe
        // chars unencoded. Bedrock model IDs contain ":" and ".", which are
        // already safe — the cloud-chat-assistant Python uses
        // urllib.parse.quote(safe="/-_.~") and that matches this set.
        val canonicalUri = (uri.rawPath ?: "/").ifEmpty { "/" }
        val canonicalQuery = uri.rawQuery ?: ""

        val (amzDate, dateStamp) = formatTimestamps(date)
        val host = uri.host + (if (uri.port != -1) ":${uri.port}" else "")

        // The three headers we sign + send. Ordered by name (lower-case)
        // for the canonical-headers block. Bedrock does NOT require
        // x-amz-content-sha256 — we omit it to match the Python ref.
        val signedHeadersList = listOf("content-type", "host", "x-amz-date")
        val headerValues = mapOf(
            "content-type" to "application/json",
            "host" to host,
            "x-amz-date" to amzDate,
        )
        val canonicalHeaders = signedHeadersList.joinToString("") { name ->
            "$name:${headerValues[name]}\n"
        }
        val signedHeaders = signedHeadersList.joinToString(";")

        val payloadHash = sha256Hex(payload)

        val canonicalRequest = buildString {
            append(method); append('\n')
            append(canonicalUri); append('\n')
            append(canonicalQuery); append('\n')
            append(canonicalHeaders); append('\n')
            append(signedHeaders); append('\n')
            append(payloadHash)
        }

        val scope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = buildString {
            append(ALGO); append('\n')
            append(amzDate); append('\n')
            append(scope); append('\n')
            append(sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8)))
        }

        // Derive the signing key — AWS spec, four nested HMACs.
        val kDate = hmac("AWS4$secretKey".toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, service)
        val kSigning = hmac(kService, "aws4_request")

        val signature = toHex(hmac(kSigning, stringToSign))

        val authorization =
            "$ALGO Credential=$accessKey/$scope, " +
                "SignedHeaders=$signedHeaders, " +
                "Signature=$signature"

        return mapOf(
            "host" to host,
            "x-amz-date" to amzDate,
            "content-type" to "application/json",
            "authorization" to authorization,
        )
    }

    /** Returns (amzDate "YYYYMMDDTHHMMSSZ", dateStamp "YYYYMMDD"). */
    internal fun formatTimestamps(date: Date): Pair<String, String> {
        val utc = TimeZone.getTimeZone("UTC")
        val amz = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = utc
        }
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = utc }
        return amz.format(date) to day.format(date)
    }

    private fun hmac(key: ByteArray, message: String): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(key, HMAC_SHA256))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance(SHA256).digest(bytes)
        return toHex(digest)
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(HEX[(b.toInt() ushr 4) and 0xF])
            sb.append(HEX[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
