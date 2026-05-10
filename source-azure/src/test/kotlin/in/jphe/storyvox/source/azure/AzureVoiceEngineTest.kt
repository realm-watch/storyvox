package `in`.jphe.storyvox.source.azure

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [AzureVoiceEngine]. We stub the [AzureSpeechClient]
 * at the class boundary — it returns canned PCM or throws an
 * [AzureError]; the engine's job is to translate sentence text into
 * an SSML body, hand it to the client, and translate failures into a
 * null-PCM "skip this sentence" signal.
 *
 * No Hilt, no Android, no MockWebServer here — those live in
 * [AzureSpeechClientTest].
 */
class AzureVoiceEngineTest {

    /** Test double for the credentials store — configurable per-case. */
    private fun creds(configured: Boolean): AzureCredentials = object : AzureCredentials() {
        override fun key(): String? = if (configured) "test-key" else null
        override val isConfigured: Boolean get() = configured
    }

    /** Recording client — captures the SSML bodies it received and
     *  returns canned PCM. */
    private class RecordingClient(
        private val response: ByteArray = byteArrayOf(0x01, 0x02),
        private val error: AzureError? = null,
    ) : AzureSpeechClient(
        // Real OkHttp + creds — never reached because synthesize() is
        // overridden below. Pass a real-shaped instance so the Hilt
        // base class's @Inject dependencies are happy.
        okhttp3.OkHttpClient(),
        AzureCredentials.forTesting(),
    ) {
        val captured = mutableListOf<String>()
        override fun synthesize(ssml: String): ByteArray {
            captured += ssml
            error?.let { throw it }
            return response
        }
    }

    @Test
    fun `synthesize returns client PCM on success`() {
        val client = RecordingClient(response = byteArrayOf(0x10, 0x20, 0x30))
        val engine = AzureVoiceEngine(client, creds(configured = true))

        val pcm = engine.synthesize(
            text = "Hello.",
            voiceName = "en-US-AvaDragonHDLatestNeural",
            speed = 1.0f,
            pitch = 1.0f,
        )

        assertNotNull(pcm)
        assertArrayEquals(byteArrayOf(0x10, 0x20, 0x30), pcm)
        assertEquals("client called once", 1, client.captured.size)
    }

    @Test
    fun `synthesize embeds the voice name in the SSML body`() {
        val client = RecordingClient()
        val engine = AzureVoiceEngine(client, creds(configured = true))

        engine.synthesize(
            text = "x",
            voiceName = "en-US-AndrewDragonHDLatestNeural",
            speed = 1.0f,
            pitch = 1.0f,
        )

        val ssml = client.captured.single()
        assertEquals(
            "voice tag matches voiceName arg",
            true,
            ssml.contains("name=\"en-US-AndrewDragonHDLatestNeural\""),
        )
    }

    @Test
    fun `blank text returns null without calling the client`() {
        val client = RecordingClient()
        val engine = AzureVoiceEngine(client, creds(configured = true))

        val pcm = engine.synthesize(
            text = "   ",
            voiceName = "v",
            speed = 1.0f,
            pitch = 1.0f,
        )

        assertNull(pcm)
        assertEquals("client never called", 0, client.captured.size)
    }

    @Test
    fun `unconfigured credentials short-circuit to null`() {
        val client = RecordingClient()
        val engine = AzureVoiceEngine(client, creds(configured = false))

        val pcm = engine.synthesize(
            text = "Hello.",
            voiceName = "v",
            speed = 1.0f,
            pitch = 1.0f,
        )

        assertNull(pcm)
        assertEquals("client never called", 0, client.captured.size)
    }

    @Test
    fun `AuthFailed re-throws so producer halts the pipeline`() {
        // PR-5 (#184) — auth failure is terminal. Every subsequent
        // sentence will fail the same way, so AzureVoiceEngine
        // re-throws (rather than swallowing to null) and the
        // EngineStreamingSource producer's exception path winds the
        // pipeline down. _lastError is also set so EnginePlayer's
        // observer can map to PlaybackError.AzureAuthFailed.
        val client = RecordingClient(
            error = AzureError.AuthFailed("bad key"),
        )
        val engine = AzureVoiceEngine(client, creds(configured = true))

        org.junit.Assert.assertThrows(AzureError.AuthFailed::class.java) {
            engine.synthesize(
                text = "x",
                voiceName = "v",
                speed = 1.0f,
                pitch = 1.0f,
            )
        }
        assertEquals(
            "lastError surface set so EnginePlayer can map to PlaybackError",
            "bad key",
            engine.lastError.value?.message,
        )
    }

    @Test
    fun `Throttled error swallowed to null`() {
        val client = RecordingClient(error = AzureError.Throttled("429"))
        val engine = AzureVoiceEngine(client, creds(configured = true))

        val pcm = engine.synthesize(
            text = "x",
            voiceName = "v",
            speed = 1.0f,
            pitch = 1.0f,
        )

        assertNull(pcm)
    }

    @Test
    fun `NetworkError swallowed to null`() {
        val client = RecordingClient(
            error = AzureError.NetworkError(java.io.IOException("dns")),
        )
        val engine = AzureVoiceEngine(client, creds(configured = true))

        val pcm = engine.synthesize(
            text = "x",
            voiceName = "v",
            speed = 1.0f,
            pitch = 1.0f,
        )

        assertNull(pcm)
    }

    @Test
    fun `engine sample rate matches client constant`() {
        val client = RecordingClient()
        val engine = AzureVoiceEngine(client, creds(configured = true))

        assertEquals(24_000, engine.sampleRate)
        assertEquals(AzureSpeechClient.SAMPLE_RATE_HZ, engine.sampleRate)
    }
}
