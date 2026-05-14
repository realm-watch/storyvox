package `in`.jphe.storyvox.llm.auth

import `in`.jphe.storyvox.llm.LlmError
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * Issue #219 — cache + refresh + error tests for the JWT-bearer
 * token source. MockWebServer stands in for
 * `oauth2.googleapis.com/token`; the SA JSON in test-sa.json gets
 * its `token_uri` rewritten to the mock server's URL via a small
 * helper so the production parser still accepts it.
 *
 * Robolectric runner — the JWT signer uses android.util.Base64 on
 * the production codepath, same as PkcePairTest.
 */
@RunWith(RobolectricTestRunner::class)
class GoogleOAuthTokenSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `mints a token from a successful exchange`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"ya29.test","expires_in":3599,"token_type":"Bearer"}""")
                .setResponseCode(200),
        )
        val source = GoogleOAuthTokenSource(http) { 1_700_000_000L }
        val sa = saFixture()

        val token = source.accessToken(sa)
        assertEquals("ya29.test", token)

        // Wire shape: POST to the SA's token_uri with form body
        // grant_type + assertion (a JWT).
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        val body = req.body.readUtf8()
        assertTrue(body.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"))
        assertTrue(body.contains("assertion="))
    }

    @Test
    fun `caches the access token across calls`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"ya29.cached","expires_in":3599,"token_type":"Bearer"}""")
                .setResponseCode(200),
        )
        val source = GoogleOAuthTokenSource(http) { 1_700_000_000L }
        val sa = saFixture()

        val first = source.accessToken(sa)
        val second = source.accessToken(sa)
        assertEquals("ya29.cached", first)
        assertEquals(first, second)
        // Only one network round-trip. A second enqueue() wasn't
        // even prepared — if the cache didn't hold, MockWebServer
        // would 404 the second call.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `refreshes when the cached token is within skew of expiry`() = runTest {
        var now = 1_700_000_000L
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"ya29.first","expires_in":3600,"token_type":"Bearer"}""")
                .setResponseCode(200),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"ya29.second","expires_in":3600,"token_type":"Bearer"}""")
                .setResponseCode(200),
        )
        val source = GoogleOAuthTokenSource(http) { now }
        val sa = saFixture()

        assertEquals("ya29.first", source.accessToken(sa))
        // Jump time forward to within the 5-minute refresh skew of
        // expiry. The cache must consider the token stale and re-mint.
        // expires_at = 1_700_000_000 + 3600 = 1_700_003_600.
        // skew = 300. So now = 1_700_003_301 should trigger refresh.
        now = 1_700_003_301L
        assertEquals("ya29.second", source.accessToken(sa))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `keeps the token when still within validity window`() = runTest {
        var now = 1_700_000_000L
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"ya29.keep","expires_in":3600,"token_type":"Bearer"}""")
                .setResponseCode(200),
        )
        val source = GoogleOAuthTokenSource(http) { now }
        val sa = saFixture()
        assertEquals("ya29.keep", source.accessToken(sa))
        // Jump time forward but still well outside the skew window
        // (10 minutes in; skew is 5 min from expiry, expires at +3600).
        now += 600L
        assertEquals("ya29.keep", source.accessToken(sa))
        // No second call — cache held.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `surfaces a 401 from the token endpoint as AuthFailed`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"invalid_grant","error_description":"Invalid JWT"}""")
                .setResponseCode(401),
        )
        val source = GoogleOAuthTokenSource(http) { 1_700_000_000L }
        val ex = assertThrows(LlmError.AuthFailed::class.java) {
            runBlocking { source.accessToken(saFixture()) }
        }
        assertTrue(ex.message!!.contains("Token exchange failed"))
        // The Google error body bubbles up truncated so a user can grep
        // "invalid_grant" in logs.
        assertTrue(ex.message!!.contains("invalid_grant"))
    }

    @Test
    fun `invalidate forces a fresh mint`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"ya29.a","expires_in":3600,"token_type":"Bearer"}""")
                .setResponseCode(200),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"ya29.b","expires_in":3600,"token_type":"Bearer"}""")
                .setResponseCode(200),
        )
        val source = GoogleOAuthTokenSource(http) { 1_700_000_000L }
        val sa = saFixture()
        assertEquals("ya29.a", source.accessToken(sa))
        source.invalidate()
        assertEquals("ya29.b", source.accessToken(sa))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `cache key includes private_key_id so SA rotation re-mints`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"ya29.old","expires_in":3600,"token_type":"Bearer"}""")
                .setResponseCode(200),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"ya29.rotated","expires_in":3600,"token_type":"Bearer"}""")
                .setResponseCode(200),
        )
        val source = GoogleOAuthTokenSource(http) { 1_700_000_000L }
        val sa1 = saFixture()
        val sa2 = sa1.copy(privateKeyId = "rotated-key-id-abc")
        assertEquals("ya29.old", source.accessToken(sa1))
        assertEquals("ya29.rotated", source.accessToken(sa2))
        assertEquals(2, server.requestCount)
    }

    /** Load + rewrite the test SA so token_uri points at MockWebServer. */
    private fun saFixture(): GoogleServiceAccount {
        val raw = javaClass.classLoader!!.getResource("test-sa.json")!!.readText()
        // Rewrite token_uri in the JSON literal. Cheaper than parsing,
        // re-emitting, and re-parsing — and we don't want this rewrite
        // to depend on Json's encoder defaults.
        val rewritten = raw.replace(
            "\"https://oauth2.googleapis.com/token\"",
            "\"${server.url("/token")}\"",
        )
        return GoogleServiceAccount.parse(rewritten)
    }
}
