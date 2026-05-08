package `in`.jphe.storyvox.source.github.auth

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [DeviceFlowApi] — RFC 8628 + GitHub-specific behaviors.
 *
 * Issue #91. Drives a real OkHttpClient against MockWebServer so the
 * wire format (form-encoded POST body, JSON response parsing, error
 * branch dispatch) is exactly what production sees on github.com.
 */
class DeviceFlowApiTest {

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient.Builder()
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun apiPointingAt(path: String): DeviceFlowApi {
        val base = server.url(path).toString()
        return object : DeviceFlowApi(http) {
            override val deviceCodeUrl: String = base
            override val accessTokenUrl: String = base
        }
    }

    // ── requestDeviceCode ──────────────────────────────────────────

    @Test
    fun `requestDeviceCode parses a 200 response into Success`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"device_code":"3584d83530557fdd1f46af8289938c8ef79f9dc5",
                     "user_code":"WDJB-MJHT",
                     "verification_uri":"https://github.com/login/device",
                     "verification_uri_complete":"https://github.com/login/device?user_code=WDJB-MJHT",
                     "expires_in":900,
                     "interval":5}
                    """.trimIndent(),
                )
                .setResponseCode(200),
        )
        val result = apiPointingAt("/login/device/code")
            .requestDeviceCode("client123", "read:user public_repo")
        assertTrue("expected Success, got $result", result is DeviceCodeResult.Success)
        result as DeviceCodeResult.Success
        assertEquals("WDJB-MJHT", result.userCode)
        assertEquals("3584d83530557fdd1f46af8289938c8ef79f9dc5", result.deviceCode)
        assertEquals(900, result.expiresInSeconds)
        assertEquals(5, result.intervalSeconds)
        assertEquals(
            "https://github.com/login/device?user_code=WDJB-MJHT",
            result.verificationUriComplete,
        )

        // Verify the wire format — GitHub expects form-encoded body with
        // client_id and scope keys (no client_secret).
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        val body = recorded.body.readUtf8()
        assertTrue("body should carry client_id, got $body", body.contains("client_id=client123"))
        assertTrue(
            "body should URL-encode the scope, got $body",
            body.contains("scope=read%3Auser+public_repo") || body.contains("scope=read%3Auser%20public_repo"),
        )
    }

    @Test
    fun `requestDeviceCode surfaces device_flow_disabled as a GitHubError`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"error":"device_flow_disabled","error_description":"OAuth app not configured"}""",
                )
                .setResponseCode(200),
        )
        val result = apiPointingAt("/login/device/code")
            .requestDeviceCode("client123", "read:user")
        assertTrue("expected GitHubError, got $result", result is DeviceCodeResult.GitHubError)
        result as DeviceCodeResult.GitHubError
        assertEquals("device_flow_disabled", result.code)
    }

    // ── pollAccessToken ────────────────────────────────────────────

    @Test
    fun `pollAccessToken returns Success when GitHub issues a token`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"access_token":"gho_abc123","scope":"read:user public_repo","token_type":"bearer"}""",
                )
                .setResponseCode(200),
        )
        val result = apiPointingAt("/login/oauth/access_token")
            .pollAccessToken("client123", "device-xyz")
        assertTrue(result is TokenPollResult.Success)
        result as TokenPollResult.Success
        assertEquals("gho_abc123", result.token)
        assertEquals("read:user public_repo", result.scopes)
        assertEquals("bearer", result.tokenType)

        // Verify the form-encoded POST body shape.
        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue("body should carry client_id, got $body", body.contains("client_id=client123"))
        assertTrue("body should carry device_code, got $body", body.contains("device_code=device-xyz"))
        assertTrue(
            "body should carry the device-flow grant_type, got $body",
            body.contains(
                "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code",
            ),
        )
    }

    @Test
    fun `pollAccessToken maps authorization_pending to Pending`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"authorization_pending"}""")
                .setResponseCode(200),
        )
        val result = apiPointingAt("/login/oauth/access_token")
            .pollAccessToken("client123", "device-xyz")
        assertTrue("expected Pending, got $result", result is TokenPollResult.Pending)
    }

    @Test
    fun `pollAccessToken maps slow_down to SlowDown`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"slow_down"}""")
                .setResponseCode(200),
        )
        val result = apiPointingAt("/login/oauth/access_token")
            .pollAccessToken("client123", "device-xyz")
        assertTrue("expected SlowDown, got $result", result is TokenPollResult.SlowDown)
    }

    @Test
    fun `pollAccessToken maps expired_token to Expired`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"expired_token"}""")
                .setResponseCode(200),
        )
        val result = apiPointingAt("/login/oauth/access_token")
            .pollAccessToken("client123", "device-xyz")
        assertTrue("expected Expired, got $result", result is TokenPollResult.Expired)
    }

    @Test
    fun `pollAccessToken maps access_denied to Denied`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"access_denied"}""")
                .setResponseCode(200),
        )
        val result = apiPointingAt("/login/oauth/access_token")
            .pollAccessToken("client123", "device-xyz")
        assertTrue("expected Denied, got $result", result is TokenPollResult.Denied)
    }

    @Test
    fun `pollAccessToken maps unknown error codes to GitHubError`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"incorrect_client_credentials","error_description":"bad id"}""")
                .setResponseCode(200),
        )
        val result = apiPointingAt("/login/oauth/access_token")
            .pollAccessToken("client123", "device-xyz")
        assertTrue("expected GitHubError, got $result", result is TokenPollResult.GitHubError)
        result as TokenPollResult.GitHubError
        assertEquals("incorrect_client_credentials", result.code)
        assertEquals("bad id", result.message)
    }

    @Test
    fun `pollAccessToken returns NetworkError when connection fails`() = runTest {
        // Shut down the server before the call — the OkHttp Call will fail
        // to connect and surface as IOException → NetworkError.
        server.shutdown()
        val result = apiPointingAt("/login/oauth/access_token")
            .pollAccessToken("client123", "device-xyz")
        assertTrue("expected NetworkError, got $result", result is TokenPollResult.NetworkError)
    }
}
