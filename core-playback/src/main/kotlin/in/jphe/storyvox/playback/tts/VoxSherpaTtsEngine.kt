package `in`.jphe.storyvox.playback.tts

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.playback.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Discovery + initialization of the TTS engine. Prefers JP's forked VoxSherpa
 * (`com.codebysonu.voxsherpa` per build config) but falls back to system default
 * with user consent.
 */
@Singleton
class VoxSherpaTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val voxSherpaPackage: String = BuildConfig.VOXSHERPA_PACKAGE
    val installUrl: String = BuildConfig.VOXSHERPA_RELEASES_URL

    fun isVoxSherpaInstalled(): Boolean {
        val pm = context.packageManager
        val intent = Intent("android.intent.action.TTS_SERVICE")
        val resolved = pm.queryIntentServices(intent, 0)
        return resolved.any { it.serviceInfo.packageName == voxSherpaPackage }
    }

    /**
     * Initialize TextToSpeech bound to a specific engine package, suspending until
     * `onInit` returns. Returns null if the engine fails to init.
     *
     * The ctor → init → onInit path on Android can fire SYNCHRONOUSLY when the
     * engine resolution fails inside `initTts()` (e.g., the requested package
     * isn't installed or doesn't expose a TTS service). When that happens, the
     * naive `lateinit var tts; tts = TextToSpeech(...)` pattern crashes because
     * the listener runs before the assignment. We work around it by capturing
     * the status into a holder and resuming AFTER the ctor returns.
     */
    suspend fun initialize(preferredPackage: String? = voxSherpaPackage): TextToSpeech? =
        suspendCancellableCoroutine { cont ->
            val target = preferredPackage?.takeIf { isVoxSherpaInstalled() && it == voxSherpaPackage }
            val ttsRef = java.util.concurrent.atomic.AtomicReference<TextToSpeech?>()
            val syncStatus = java.util.concurrent.atomic.AtomicInteger(STATUS_PENDING)
            val onInit = TextToSpeech.OnInitListener { status ->
                val captured = ttsRef.get()
                if (captured == null) {
                    // Fired synchronously from inside the ctor — record and let
                    // the ctor caller path resume the continuation.
                    syncStatus.set(status)
                    return@OnInitListener
                }
                if (cont.isCompleted) return@OnInitListener
                if (status == TextToSpeech.SUCCESS) {
                    cont.resume(captured)
                } else {
                    captured.shutdown()
                    cont.resume(null)
                }
            }
            val tts = if (target != null) {
                TextToSpeech(context, onInit, target)
            } else {
                TextToSpeech(context, onInit)
            }
            ttsRef.set(tts)
            cont.invokeOnCancellation { runCatching { tts.shutdown() } }
            // If onInit already fired synchronously, finalize now.
            val s = syncStatus.get()
            if (s != STATUS_PENDING && !cont.isCompleted) {
                if (s == TextToSpeech.SUCCESS) {
                    cont.resume(tts)
                } else {
                    tts.shutdown()
                    cont.resume(null)
                }
            }
        }

    private companion object {
        const val STATUS_PENDING = Int.MIN_VALUE
    }
}
