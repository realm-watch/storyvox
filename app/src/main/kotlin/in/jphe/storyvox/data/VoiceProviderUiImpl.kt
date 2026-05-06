package `in`.jphe.storyvox.data

import android.content.Context
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.feature.api.UiVoice
import `in`.jphe.storyvox.feature.api.VoiceProviderUi
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

/**
 * Legacy voice surface for SettingsViewModel + the legacy VoicePickerScreen +
 * ReaderViewModel. v0.4.0 introduced [in.jphe.storyvox.playback.voice.VoiceManager]
 * as the canonical source for voice install/select/download — those flows go
 * through it directly. This impl backs the framework-TTS-based "list voices the
 * OS knows about + preview them" affordance only.
 */
@Singleton
class VoiceProviderUiImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceProviderUi {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val installedVoices: Flow<List<UiVoice>> = flow {
        val tts = bootTts() ?: run {
            emit(emptyList())
            return@flow
        }
        val voices = runCatching { tts.voices?.toList().orEmpty() }.getOrDefault(emptyList())
        val mapped = voices
            .map {
                UiVoice(
                    id = it.name,
                    label = humanize(it.name),
                    engine = "System TTS",
                    locale = it.locale.toLanguageTag(),
                )
            }
            .sortedWith(compareBy({ it.locale }, { it.label }))
        runCatching { tts.shutdown() }
        emit(mapped)
    }.shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    override fun previewVoice(voice: UiVoice) {
        scope.launch {
            val tts = bootTts() ?: return@launch
            runCatching {
                tts.voices?.firstOrNull { it.name == voice.id }?.let { tts.voice = it }
                tts.speak(PREVIEW_TEXT, TextToSpeech.QUEUE_FLUSH, null, "preview-${voice.id}")
            }
            // Let the utterance play, then release.
            kotlinx.coroutines.delay(4_000L)
            runCatching { tts.shutdown() }
        }
    }

    /** Boot a one-shot Android [TextToSpeech] tied to whatever engine the OS picks. */
    private suspend fun bootTts(): TextToSpeech? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            if (cont.isCompleted) return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) cont.resume(tts) {} else {
                runCatching { tts?.shutdown() }
                cont.resume(null) {}
            }
        }
    }

    private fun humanize(name: String): String {
        val cleaned = name.replace('_', '-').split('-').filter { it.isNotBlank() }
        return cleaned.lastOrNull()?.replaceFirstChar { it.titlecase() } ?: name
    }

    private companion object {
        const val PREVIEW_TEXT = "The brass lantern flickers. Welcome back to the Library Nocturne."
    }
}
