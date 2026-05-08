package `in`.jphe.storyvox.source.github.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for [GitHubAuthInterceptor]. Verifies the contract
 * properties from spec § Auth header propagation:
 *
 *  1. Authenticated session → Bearer header attached on api.github.com.
 *  2. Anonymous session → no header.
 *  3. Expired session → no header (token suppressed in-memory).
 *  4. Non-api.github.com host → no header (defends against redirects).
 *  5. 401 response → markExpired() flips the in-memory session.
 *
 * Issue #91.
 */
class GitHubAuthInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * The interceptor only attaches when host == "api.github.com". To
     * exercise that without DNS, we rewrite the outgoing URL via a wrapping
     * interceptor: incoming request says api.github.com, we swap to the
     * MockWebServer's localhost URL just before chain.proceed().
     */
    private fun buildClient(auth: GitHubAuthRepository): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(GitHubAuthInterceptor(auth))
            // Rewrite host AFTER our interceptor has decided whether to
            // attach the header — so the header decision uses the original
            // api.github.com host (matching production), but the actual
            // request goes to MockWebServer.
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.url.host == "api.github.com" || req.url.host == "raw.githubusercontent.com") {
                    val rewritten = req.newBuilder()
                        .url(
                            req.url.newBuilder()
                                .scheme("http")
                                .host(server.hostName)
                                .port(server.port)
                                .build(),
                        )
                        .build()
                    chain.proceed(rewritten)
                } else {
                    chain.proceed(req)
                }
            }
            .build()

    @Test
    fun `attaches Bearer header when session is Authenticated and host is api github`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val auth = FakeAuth(
            GitHubSession.Authenticated(
                token = "gho_abc",
                login = "octocat",
                scopes = "read:user",
                grantedAt = 0L,
            ),
        )
        val client = buildClient(auth)
        val response = client.newCall(
            Request.Builder().url("https://api.github.com/user".toHttpUrl()).build(),
        ).execute()
        response.close()
        val recorded = server.takeRequest()
        assertEquals("Bearer gho_abc", recorded.getHeader("Authorization"))
    }

    @Test
    fun `does not attach header when session is Anonymous`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val auth = FakeAuth(GitHubSession.Anonymous)
        val client = buildClient(auth)
        client.newCall(
            Request.Builder().url("https://api.github.com/user".toHttpUrl()).build(),
        ).execute().close()
        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun `does not attach header when session is Expired`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val auth = FakeAuth(GitHubSession.Expired)
        val client = buildClient(auth)
        client.newCall(
            Request.Builder().url("https://api.github.com/user".toHttpUrl()).build(),
        ).execute().close()
        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun `does not attach header to non-api hosts`() {
        // Token leak defense — even with an Authenticated session, requests
        // to raw.githubusercontent.com (or any non-api host) must not bear
        // the Bearer header.
        server.enqueue(MockResponse().setResponseCode(200).setBody("plain content"))
        val auth = FakeAuth(
            GitHubSession.Authenticated(
                token = "gho_abc", login = null, scopes = "read:user", grantedAt = 0L,
            ),
        )
        val client = buildClient(auth)
        client.newCall(
            Request.Builder().url("https://raw.githubusercontent.com/x/y/z".toHttpUrl()).build(),
        ).execute().close()
        val recorded = server.takeRequest()
        assertNull(
            "raw.githubusercontent.com must not see the Bearer token",
            recorded.getHeader("Authorization"),
        )
    }

    @Test
    fun `401 response transitions session to Expired and the token stops attaching`() {
        // First request: 401 → markExpired() called.
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        // Second request: anonymous (no header).
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val auth = FakeAuth(
            GitHubSession.Authenticated(
                token = "gho_revoked", login = null, scopes = "read:user", grantedAt = 0L,
            ),
        )
        val client = buildClient(auth)

        val first = client.newCall(
            Request.Builder().url("https://api.github.com/user".toHttpUrl()).build(),
        ).execute()
        assertEquals(401, first.code)
        first.close()
        // After the 401, the interceptor should have flipped state to Expired.
        assertTrue(
            "expected Expired after 401, got ${auth.sessionState.value}",
            auth.sessionState.value is GitHubSession.Expired,
        )
        val firstRecorded = server.takeRequest()
        assertEquals("Bearer gho_revoked", firstRecorded.getHeader("Authorization"))

        // Subsequent request — the in-memory session is Expired so no header.
        client.newCall(
            Request.Builder().url("https://api.github.com/user".toHttpUrl()).build(),
        ).execute().close()
        val secondRecorded = server.takeRequest()
        assertNull(
            "expired-session second request must not bear the dead token",
            secondRecorded.getHeader("Authorization"),
        )
    }
}

/**
 * Test fake — the real [GitHubAuthRepositoryImpl] depends on
 * SharedPreferences which complicates pure-JVM tests. The interceptor
 * only needs the StateFlow + markExpired, so this scope is sufficient.
 */
private class FakeAuth(initial: GitHubSession) : GitHubAuthRepository {
    private val state = MutableStateFlow(initial)
    override val sessionState: StateFlow<GitHubSession> = state.asStateFlow()
    override suspend fun captureSession(token: String, login: String?, scopes: String) {
        state.value = GitHubSession.Authenticated(token, login, scopes, 0L)
    }
    override suspend fun clearSession() { state.value = GitHubSession.Anonymous }
    override fun markExpired() { state.value = GitHubSession.Expired }
}
