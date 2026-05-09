package `in`.jphe.storyvox.llm.auth

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Anthropic Teams OAuth client tests (#181).
 *
 * Mirrors the shape of `:source-github`'s `DeviceFlowApiTest` —
 * MockWebServer-backed asserts on the request shape + response
 * decoding for the three result variants we care about (Success,
 * InvalidGrant, AnthropicError).
 */
class AnthropicTeamsAuthApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: AnthropicTeamsAuthApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = object : AnthropicTeamsAuthApi(OkHttpClient()) {
            override val tokenUrl: String =
                server.url("/v1/oauth/token").toString()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `authorizeUrl includes PKCE challenge state and redirect`() {
        val url = api.authorizeUrl(
            clientId = "test-client",
            scopes = "user:profile user:inference",
            challenge = "abc123",
            state = "state-nonce",
        )
        assertTrue(url.startsWith("https://claude.ai/oauth/authorize?"))
        assertTrue(url.contains("client_id=test-client"))
        assertTrue(url.contains("code_challenge=abc123"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=state-nonce"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("redirect_uri=https"))
    }

    @Test
    fun `exchangeCode returns Success on 200 with access_token`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "access_token": "bearer-123",
                      "refresh_token": "refresh-456",
                      "expires_in": 3600,
                      "token_type": "Bearer",
                      "scope": "user:profile user:inference"
                    }
                    """.trimIndent(),
                ),
        )
        val r = api.exchangeCode(
            clientId = "test-client",
            code = "auth-code",
            verifier = "pkce-verifier",
            state = "state-nonce",
        )
        assertTrue("expected Success, got $r", r is TokenResult.Success)
        val success = r as TokenResult.Success
        assertEquals("bearer-123", success.accessToken)
        assertEquals("refresh-456", success.refreshToken)
        assertEquals(3600L, success.expiresInSeconds)
        assertEquals("user:profile user:inference", success.scopes)
        // Confirm the request body carried the PKCE verifier + auth code.
        val req = server.takeRequest()
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"code\":\"auth-code\""))
        assertTrue(body.contains("\"code_verifier\":\"pkce-verifier\""))
        assertTrue(body.contains("\"grant_type\":\"authorization_code\""))
    }

    @Test
    fun `exchangeCode returns InvalidGrant on invalid_grant error`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"error":"invalid_grant","error_description":"code expired"}""",
                ),
        )
        val r = api.exchangeCode(
            clientId = "test-client",
            code = "bad-code",
            verifier = "pkce-verifier",
            state = "state-nonce",
        )
        assertTrue("expected InvalidGrant, got $r", r is TokenResult.InvalidGrant)
        assertEquals("code expired", (r as TokenResult.InvalidGrant).message)
    }

    @Test
    fun `refreshToken returns Success on 200`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "access_token": "fresh-bearer",
                      "expires_in": 7200,
                      "token_type": "Bearer"
                    }
                    """.trimIndent(),
                ),
        )
        val r = api.refreshToken(
            clientId = "test-client",
            refreshToken = "refresh-old",
        )
        assertTrue("expected Success, got $r", r is TokenResult.Success)
        val success = r as TokenResult.Success
        assertEquals("fresh-bearer", success.accessToken)
        // Anthropic doesn't always rotate the refresh token — null is OK,
        // the caller falls back to keeping the existing one.
        assertEquals(null, success.refreshToken)
        assertEquals(7200L, success.expiresInSeconds)
        // Body carries grant_type + refresh_token, not code/verifier.
        val req = server.takeRequest()
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"grant_type\":\"refresh_token\""))
        assertTrue(body.contains("\"refresh_token\":\"refresh-old\""))
    }

    @Test
    fun `exchangeCode returns AnthropicError on unrecognized error`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"error":"invalid_client","error_description":"client_id rejected"}""",
                ),
        )
        val r = api.exchangeCode(
            clientId = "test-client",
            code = "code",
            verifier = "v",
            state = "s",
        )
        assertTrue("expected AnthropicError, got $r", r is TokenResult.AnthropicError)
        val err = r as TokenResult.AnthropicError
        assertEquals("invalid_client", err.code)
        assertNotNull(err.message)
    }
}
