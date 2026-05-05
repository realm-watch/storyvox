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
     */
    suspend fun initialize(preferredPackage: String? = voxSherpaPackage): TextToSpeech? =
        suspendCancellableCoroutine { cont ->
            val target = preferredPackage?.takeIf { isVoxSherpaInstalled() && it == voxSherpaPackage }
            lateinit var tts: TextToSpeech
            val onInit = TextToSpeech.OnInitListener { status ->
                if (cont.isCompleted) return@OnInitListener
                if (status == TextToSpeech.SUCCESS) {
                    cont.resume(tts)
                } else {
                    tts.shutdown()
                    cont.resume(null)
                }
            }
            tts = if (target != null) {
                TextToSpeech(context, onInit, target)
            } else {
                TextToSpeech(context, onInit)
            }
            cont.invokeOnCancellation { runCatching { tts.shutdown() } }
        }
}
