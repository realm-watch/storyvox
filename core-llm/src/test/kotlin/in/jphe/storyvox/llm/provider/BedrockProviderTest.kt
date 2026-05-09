package `in`.jphe.storyvox.llm.provider

import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmCredentialsStore
import `in`.jphe.storyvox.llm.LlmError
import `in`.jphe.storyvox.llm.LlmMessage
import `in`.jphe.storyvox.llm.ProviderId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BedrockProviderTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }
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

    /** Same golden frames as [EventStreamParserTest] — captured Bedrock
     *  streaming response with two text deltas + a stop frame. */
    private val goldenFrames: ByteArray = decodeHex(
        "0000009d00000057000000000d3a6d6573736167652d747970650700056576656e74" +
            "0b3a6576656e742d74797065070011636f6e74656e74426c6f636b44656c7461" +
            "0d3a636f6e74656e742d747970650700106170706c69636174696f6e2f6a736f6e" +
            "7b22636f6e74656e74426c6f636b496e646578223a20302c202264656c7461223a" +
            "207b2274657874223a202248656c6c6f2c20227d7d00000000" +
            "0000009c00000057000000000d3a6d6573736167652d747970650700056576656e74" +
            "0b3a6576656e742d74797065070011636f6e74656e74426c6f636b44656c7461" +
            "0d3a636f6e74656e742d747970650700106170706c69636174696f6e2f6a736f6e" +
            "7b22636f6e74656e74426c6f636b496e646578223a20302c202264656c7461223a" +
            "207b2274657874223a2022776f726c642e227d7d00000000" +
            "0000007b00000051000000000d3a6d6573736167652d747970650700056576656e74" +
            "0b3a6576656e742d7479706507000b6d65737361676553746f70" +
            "0d3a636f6e74656e742d747970650700106170706c69636174696f6e2f6a736f6e" +
            "7b2273746f70526561736f6e223a2022656e645f7475726e227d00000000",
    )

    /** Build a provider whose endpoint URL points at MockWebServer. The
     *  Bedrock model id is URL-quoted in the path; we use a simple one
     *  here so the path stays readable in MockWebServer.recordedRequest. */
    private fun providerWith(
        access: String?,
        secret: String?,
        bedrockModel: String = "claude-haiku-4.5",
    ): BedrockProvider {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val store = FakeBedrockStore(access, secret)
        val cfg = flowOf(
            LlmConfig(
                bedrockModel = bedrockModel,
                bedrockRegion = "us-east-1",
            ),
        )
        return object : BedrockProvider(http, store, cfg, json) {
            override fun endpointUrl(region: String, modelId: String): String =
                "$baseUrl/model/$modelId/converse-stream"

            override val signingRegion: String? = "us-east-1"
        }
    }

    @Test
    fun `streams text deltas in order from event-stream frames`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/vnd.amazon.eventstream")
                .setBody(Buffer().write(goldenFrames))
                .setResponseCode(200),
        )

        val tokens = providerWith("AKIATEST", "secret").stream(
            messages = listOf(LlmMessage(LlmMessage.Role.user, "hi")),
        ).toList()
        assertEquals(listOf("Hello, ", "world."), tokens)

        val req = server.takeRequest()
        // SigV4 emits Authorization with the AWS4-HMAC-SHA256 algorithm
        // and the credential scope we'd expect for the test region.
        val auth = req.getHeader("Authorization")
        assertNotNull(auth)
        assertTrue(auth!!.startsWith("AWS4-HMAC-SHA256"))
        assertTrue(auth.contains("Credential=AKIATEST/"))
        assertTrue(auth.contains("us-east-1/bedrock/aws4_request"))
        // x-amz-date format YYYYMMDDTHHMMSSZ.
        val amzDate = req.getHeader("x-amz-date")
        assertNotNull(amzDate)
        assertTrue(amzDate!!.matches(Regex("\\d{8}T\\d{6}Z")))
        // POST body is the converse-stream JSON shape (modelId + messages).
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"modelId\""))
        assertTrue(body.contains("\"converse-stream\"") || body.contains("\"messages\""))
        assertTrue(body.contains("\"inferenceConfig\""))
    }

    @Test
    fun `missing keys raises NotConfigured`() = runTest {
        val provider = providerWith(access = null, secret = null)
        val ex = assertThrows(LlmError.NotConfigured::class.java) {
            runBlocking {
                provider.stream(listOf(LlmMessage(LlmMessage.Role.user, "x"))).toList()
            }
        }
        assertEquals(ProviderId.Bedrock, ex.provider)
    }

    @Test
    fun `partial credentials still treated as NotConfigured`() = runTest {
        val provider = providerWith(access = "AKIATEST", secret = "")
        assertThrows(LlmError.NotConfigured::class.java) {
            runBlocking {
                provider.stream(listOf(LlmMessage(LlmMessage.Role.user, "x"))).toList()
            }
        }
    }

    @Test
    fun `403 raises AuthFailed`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody("InvalidSignatureException"),
        )
        val provider = providerWith("AKIATEST", "secret")
        val ex = assertThrows(LlmError.AuthFailed::class.java) {
            runBlocking {
                provider.stream(listOf(LlmMessage(LlmMessage.Role.user, "x"))).toList()
            }
        }
        assertEquals(ProviderId.Bedrock, ex.provider)
    }

    @Test
    fun `5xx raises ProviderError with body excerpt`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(503).setBody("ServiceUnavailable"),
        )
        val provider = providerWith("AKIATEST", "secret")
        val ex = assertThrows(LlmError.ProviderError::class.java) {
            runBlocking {
                provider.stream(listOf(LlmMessage(LlmMessage.Role.user, "x"))).toList()
            }
        }
        assertEquals(503, ex.status)
        assertTrue(ex.detail.contains("ServiceUnavailable"))
    }

    @Test
    fun `unknown canonical model raises ProviderError`() = runTest {
        // No HTTP request should fire — error short-circuits before request.
        val provider = providerWith("AKIATEST", "secret", bedrockModel = "totally-fake")
        val ex = assertThrows(LlmError.ProviderError::class.java) {
            runBlocking {
                provider.stream(listOf(LlmMessage(LlmMessage.Role.user, "x"))).toList()
            }
        }
        assertTrue(ex.detail.contains("Unknown Bedrock model"))
        assertEquals(0, server.requestCount)
    }

    private fun decodeHex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
        }
    }
}

/** Test-only LlmCredentialsStore overriding the bedrock getters. */
internal class FakeBedrockStore(
    private val access: String?,
    private val secret: String?,
) : LlmCredentialsStore() {
    override fun bedrockAccessKey(): String? = access
    override fun bedrockSecretKey(): String? = secret
}
