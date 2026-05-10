package `in`.jphe.storyvox.source.azure

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cloud TTS engine handle. Adapts [AzureSpeechClient] to the same
 * [EngineStreamingSource.VoiceEngineHandle] contract Piper and
 * Kokoro implement, so `EnginePlayer` can plug Azure in via the
 * existing producer/consumer pipeline with **zero refactor of the
 * engine seam**.
 *
 * The contract is "give me PCM for this sentence text, return null
 * to skip"; we satisfy it via an HTTPS round-trip to Azure instead
 * of a JNI call into a local model. Producer queue, consumer
 * AudioTrack, sentence highlighting, PCM cache — all unchanged.
 *
 * **Stateless across sentences.** Each [synthesize] call is its own
 * HTTPS request; OkHttp's connection pool reuses the TLS session,
 * so steady-state cost is one round-trip per sentence. There's no
 * "engine warm-up" path equivalent to Kokoro's 30-second model load
 * — first-sentence latency is one round-trip, ~150–400 ms on
 * residential WiFi.
 *
 * **Wired in PR-2; activated in PR-4.** This class compiles and runs,
 * and is unit-tested below; but `EnginePlayer.activeVoiceEngineHandle`
 * doesn't yet route `EngineType.Azure` to it. PR-4 in Solara's plan
 * is the one-line switch that wires this engine into the pipeline.
 *
 * **Error mapping.** [AzureError] thrown by the client surfaces as a
 * null PCM return — the existing producer skip-and-keep-going branch
 * handles "this sentence had no PCM". PR-5 wires the auth-error case
 * to `stopPlaybackPipeline()` (no point synthesizing further sentences
 * with a bad key) plus a side-channel `PlaybackState.error` update.
 * For PR-2 we just log the error type and return null — the loud
 * guard message tells the next dev to come back here when PR-5 lands.
 */
@Singleton
open class AzureVoiceEngine @Inject constructor(
    private val client: AzureSpeechClient,
    private val credentials: AzureCredentials,
) {

    /** Sample rate of synthesized PCM. Constant — every Azure HD
     *  voice we request goes through with the 24 kHz output format
     *  (see [AzureSpeechClient.SAMPLE_RATE_HZ]). The engine handle
     *  surfaces this to AudioTrack on voice swap. */
    val sampleRate: Int = AzureSpeechClient.SAMPLE_RATE_HZ

    private val _lastError = MutableStateFlow<AzureError?>(null)

    /**
     * PR-5 (#184) — last error this engine surfaced, or null when
     * synthesis is healthy. EnginePlayer observes this Flow and
     * translates Azure errors into [PlaybackError] types on
     * `PlaybackState.error`; the Settings → Cloud Voices section can
     * also surface it as a "last failure" hint.
     *
     * Cleared on the next successful synthesize() (so the user sees
     * the error disappear when reconnect succeeds) and exposed via
     * [clearLastError] so the Settings flow can dismiss it on a
     * key change.
     */
    val lastError: StateFlow<AzureError?> = _lastError.asStateFlow()

    /** Clear the [lastError] state — called when the user re-pastes
     *  a key or otherwise indicates the prior failure is moot. */
    fun clearLastError() {
        _lastError.value = null
    }

    /**
     * Synthesize one sentence to PCM via Azure.
     *
     * Returns the raw PCM bytes on success. Returns null when:
     * - The sentence is blank (no point round-tripping for whitespace).
     * - Credentials are missing — the client throws AuthFailed; we
     *   swallow into null so the producer's skip path keeps going.
     *   PR-5 elevates this to `stopPlaybackPipeline()` because every
     *   subsequent sentence will fail the same way.
     * - Any other [AzureError] — also swallowed to null in PR-2;
     *   PR-5 routes throttles, server errors, and network failures
     *   to the appropriate `PlaybackState.error` types and the
     *   offline-fallback path.
     *
     * @param text the sentence to synthesize.
     * @param voiceName the Azure voice id (e.g.
     *                  `en-US-AvaDragonHDLatestNeural`). Surfaced
     *                  verbatim in the SSML `<voice name=...>` attr.
     *                  Pre-PR-4 this was an `EngineType.Azure`
     *                  descriptor; PR-4 unwraps to the raw String
     *                  because :source-azure can no longer depend on
     *                  :core-playback (where EngineType lives) without
     *                  introducing a Gradle dep cycle. Region + key
     *                  still come from [credentials].
     * @param speed   storyvox speed multiplier; mapped to SSML rate.
     * @param pitch   storyvox pitch multiplier; mapped to SSML pitch.
     */
    open fun synthesize(
        text: String,
        voiceName: String,
        speed: Float,
        pitch: Float,
    ): ByteArray? {
        if (text.isBlank()) return null
        if (!credentials.isConfigured) {
            Log.w(TAG, "Azure synth requested but credentials not configured")
            return null
        }
        val ssml = AzureSsmlBuilder.build(
            text = text,
            voiceName = voiceName,
            speed = speed,
            pitch = pitch,
        )
        return try {
            val pcm = client.synthesize(ssml)
            // Successful synth — clear any previously-recorded error
            // so the UI's "last failure" surface dismisses naturally
            // once the connection recovers.
            if (_lastError.value != null) _lastError.value = null
            pcm
        } catch (e: AzureError.AuthFailed) {
            // PR-5 (#184) — auth failure is terminal. Every subsequent
            // sentence will fail the same way, so we re-throw to let
            // the producer's exception path stop the pipeline. The
            // _lastError surface gives the UI (PlaybackError mapping
            // in EnginePlayer + Settings → Cloud Voices) a place to
            // read the failure type without parsing exception text.
            Log.w(TAG, "Azure auth failed: ${e.message}")
            _lastError.value = e
            throw e
        } catch (e: AzureError) {
            // Throttle / 5xx / network — non-terminal. Skip this
            // sentence (return null) and let the producer keep going;
            // the next sentence may succeed. _lastError lets the UI
            // surface a one-shot error banner without halting playback.
            Log.w(TAG, "Azure synth failed: ${e::class.java.simpleName}: ${e.message}")
            _lastError.value = e
            null
        }
    }

    // 2026-05-09 (PR-4): the `asEngineHandle(...)` adapter was here
    // pre-PR-4 to give EnginePlayer a one-liner switch. PR-4 inlines
    // the adapter directly in EnginePlayer.activeVoiceEngineHandle's
    // object literal so :source-azure no longer needs a reverse dep
    // on :core-playback (which would create a Gradle cycle now that
    // :core-playback depends on :source-azure for the engine wiring).

    private companion object {
        const val TAG = "AzureVoiceEngine"
    }
}
