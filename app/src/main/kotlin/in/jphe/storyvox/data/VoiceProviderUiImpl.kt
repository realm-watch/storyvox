package `in`.jphe.storyvox.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.feature.api.UiVoice
import `in`.jphe.storyvox.feature.api.VoiceProviderUi
import `in`.jphe.storyvox.playback.tts.VoxSherpaTtsEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

@Singleton
class VoiceProviderUiImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: VoxSherpaTtsEngine,
) : VoiceProviderUi {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Snapshots the engine's voice list once on first subscription and reuses it.
     * We boot a throwaway [TextToSpeech] (preferring VoxSherpa), read `voices`,
     * shut it down. Cheap enough; revisit if the user can install voices at runtime.
     */
    override val installedVoices: Flow<List<UiVoice>> = flow {
        val tts = engine.initialize() ?: run {
            emit(emptyList())
            return@flow
        }
        val voices = runCatching { tts.voices?.toList().orEmpty() }.getOrDefault(emptyList())
        val mapped = voices
            .map {
                UiVoice(
                    id = it.name,
                    label = humanize(it.name),
                    engine = if (engine.isVoxSherpaInstalled()) "VoxSherpa" else "System TTS",
                    locale = it.locale.toLanguageTag(),
                )
            }
            .sortedWith(compareBy({ it.locale }, { it.label }))
        runCatching { tts.shutdown() }
        emit(mapped)
    }.shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val _voxSherpaInstalled = MutableStateFlow(engine.isVoxSherpaInstalled())
    override val isVoxSherpaInstalled: Flow<Boolean> = _voxSherpaInstalled.asStateFlow()

    override fun previewVoice(voice: UiVoice) {
        scope.launch {
            val tts = engine.initialize() ?: return@launch
            runCatching {
                tts.voices?.firstOrNull { it.name == voice.id }?.let { tts.voice = it }
                tts.speak(PREVIEW_TEXT, TextToSpeech.QUEUE_FLUSH, null, "preview-${voice.id}")
            }
            // Let the utterance play; shut down after a short delay.
            kotlinx.coroutines.delay(4_000L)
            runCatching { tts.shutdown() }
        }
    }

    override fun openVoxSherpaInstall() {
        val uri: Uri = engine.installUrl.toUri()
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    private fun humanize(name: String): String {
        // Voice names look like "en-US-AvaNeural" or "en_us_x_iol_local". Trim and
        // hand back the most readable trailing token.
        val cleaned = name.replace('_', '-').split('-').filter { it.isNotBlank() }
        return cleaned.lastOrNull()?.replaceFirstChar { it.titlecase() } ?: name
    }

    private companion object {
        const val PREVIEW_TEXT = "The brass lantern flickers. Welcome back to the Library Nocturne."
    }
}
