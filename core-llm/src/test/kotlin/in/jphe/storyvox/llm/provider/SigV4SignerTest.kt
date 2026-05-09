package `in`.jphe.storyvox.llm.provider

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-value test for SigV4 signing. The expected `Signature=…` was
 * computed by running the canonical Python implementation in
 * `cloud-chat-assistant/llm_stream.py:_aws_sign_v4` against the same
 * inputs (pinned timestamp, fixed credentials, fixed body). Matching
 * bit-for-bit means the Kotlin port reproduces the AWS algorithm — any
 * drift in canonical-request shape, header sorting, hash chain, or
 * scope formatting flips this test red.
 *
 * AWS credentials below are AWS's own published example values
 * (`AKIAIOSFODNN7EXAMPLE` / `wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY`)
 * — not real keys; they appear in every AWS SigV4 docs page.
 */
class SigV4SignerTest {

    @Test
    fun `signs Bedrock POST against Python golden`() {
        val date = utcDate(year = 2024, month = 1, day = 15, hour = 12, minute = 30, second = 45)
        // Same JSON shape Python produces with `json.dumps(...)` on the
        // golden body — Python's default separators include spaces, so
        // the byte sequence here matches that output exactly. Any
        // serialization difference (whitespace, key order) changes the
        // payload hash and breaks the signature.
        val payload =
            "{\"modelId\": \"anthropic.claude-haiku\", \"messages\": [{\"role\": \"user\", " +
                "\"content\": [{\"text\": \"hi\"}]}], \"inferenceConfig\": " +
                "{\"maxTokens\": 1, \"temperature\": 1.0}}"

        val headers = SigV4Signer.sign(
            method = "POST",
            url = "https://bedrock-runtime.us-east-1.amazonaws.com/model/" +
                "anthropic.claude-haiku/converse-stream",
            payload = payload.toByteArray(Charsets.UTF_8),
            accessKey = "AKIAIOSFODNN7EXAMPLE",
            secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            region = "us-east-1",
            service = "bedrock",
            date = date,
        )

        assertEquals("bedrock-runtime.us-east-1.amazonaws.com", headers["host"])
        assertEquals("20240115T123045Z", headers["x-amz-date"])
        assertEquals("application/json", headers["content-type"])
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20240115/us-east-1/" +
                "bedrock/aws4_request, SignedHeaders=content-type;host;x-amz-date, " +
                "Signature=e14baf24f7b275583342b9b8dd2aff0b0b8d8ca9a2f7d5ddea129c470c826da3",
            headers["authorization"],
        )
    }

    @Test
    fun `host header includes port when URL has explicit port`() {
        val headers = SigV4Signer.sign(
            method = "POST",
            url = "http://127.0.0.1:8080/model/x/converse-stream",
            payload = "{}".toByteArray(),
            accessKey = "AKIA",
            secretKey = "secret",
            region = "us-east-1",
            date = utcDate(2024, 1, 15, 12, 30, 45),
        )
        assertEquals("127.0.0.1:8080", headers["host"])
        // Authorization should include host in SignedHeaders regardless of port.
        assertTrue(headers["authorization"]!!.contains("SignedHeaders=content-type;host;x-amz-date"))
    }

    @Test
    fun `formatTimestamps emits compact UTC strings`() {
        val date = utcDate(2026, 5, 9, 0, 0, 0)
        val (amz, day) = SigV4Signer.formatTimestamps(date)
        assertEquals("20260509T000000Z", amz)
        assertEquals("20260509", day)
    }

    private fun utcDate(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int) =
        GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            set(year, month - 1, day, hour, minute, second)
            set(Calendar.MILLISECOND, 0)
        }.time
}
