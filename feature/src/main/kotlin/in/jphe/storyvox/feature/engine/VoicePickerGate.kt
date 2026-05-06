package `in`.jphe.storyvox.feature.engine

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import `in`.jphe.storyvox.playback.voice.UiVoiceInfo
import `in`.jphe.storyvox.playback.voice.VoiceManager
import `in`.jphe.storyvox.playback.voice.VoiceManager.DownloadProgress
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.MagicSkeletonTile
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * First-launch gate that wraps the rest of the app. While no voice is
 * active in [VoiceManager] we render a sigil-themed picker over everything;
 * once an active voice exists (or the user opts into reader-only mode for
 * the session) the gate is invisible and [content] renders normally.
 *
 * The gate is reactive — it collects [VoiceManager.activeVoice] as a
 * Flow and dismisses itself the moment the DataStore-backed selection
 * flips to non-null. No manual refresh hook is required.
 */
@Composable
fun VoicePickerGate(
    onOpenVoiceLibrary: () -> Unit,
    content: @Composable () -> Unit,
) {
    val vm: VoicePickerGateViewModel = hiltViewModel()
    val activeVoice by vm.activeVoice.collectAsStateWithLifecycle()
    val downloadingId by vm.downloadingVoiceId.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val bypassed by vm.bypassed.collectAsStateWithLifecycle()

    if (bypassed || activeVoice != null) {
        content()
        return
    }

    VoicePickerScreen(
        recommended = vm.recommended,
        downloadingVoiceId = downloadingId,
        progress = progress,
        onPick = vm::pick,
        onSkip = vm::bypass,
        onOpenLibrary = {
            // Dismiss the gate so the NavHost is visible, then route to the
            // library. If the user picks a voice there, activeVoice flips
            // to non-null and the gate stays dismissed permanently. If not,
            // they got reader-only mode for the session.
            vm.bypass()
            onOpenVoiceLibrary()
        },
        onDismissProgress = vm::dismissProgress,
    )
}

@HiltViewModel
class VoicePickerGateViewModel @Inject constructor(
    private val voices: VoiceManager,
) : ViewModel() {

    /** First three catalog entries — Amy low / Lessac medium / Ryan high (en_US Piper). */
    val recommended: List<UiVoiceInfo> = voices.availableVoices.take(3)

    val activeVoice: StateFlow<UiVoiceInfo?> = voices.activeVoice
        .let { flow ->
            val s = MutableStateFlow<UiVoiceInfo?>(null)
            viewModelScope.launch { flow.collect { s.value = it } }
            s.asStateFlow()
        }

    private val _downloadingVoiceId = MutableStateFlow<String?>(null)
    val downloadingVoiceId: StateFlow<String?> = _downloadingVoiceId.asStateFlow()

    private val _progress = MutableStateFlow<DownloadProgress?>(null)
    val progress: StateFlow<DownloadProgress?> = _progress.asStateFlow()

    /** Sticky session-only opt-out so a user without a voice can still use
     *  the reader without staring at the gate. Not persisted across app
     *  restart — that's by design (next launch nudges toward picking). */
    private val _bypassed = MutableStateFlow(false)
    val bypassed: StateFlow<Boolean> = _bypassed.asStateFlow()

    fun pick(voiceId: String) {
        if (_downloadingVoiceId.value != null) return
        _downloadingVoiceId.value = voiceId
        _progress.value = DownloadProgress.Resolving
        viewModelScope.launch {
            voices.download(voiceId).collect { p ->
                _progress.value = p
                if (p is DownloadProgress.Done) {
                    voices.setActive(voiceId)
                    _downloadingVoiceId.value = null
                    _progress.value = null
                }
            }
        }
    }

    fun bypass() {
        _bypassed.value = true
    }

    fun dismissProgress() {
        _downloadingVoiceId.value = null
        _progress.value = null
    }
}

@Composable
private fun VoicePickerScreen(
    recommended: List<UiVoiceInfo>,
    downloadingVoiceId: String?,
    progress: DownloadProgress?,
    onPick: (String) -> Unit,
    onSkip: () -> Unit,
    onOpenLibrary: () -> Unit,
    onDismissProgress: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacing.lg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(spacing.xl))
            MagicSkeletonTile(
                modifier = Modifier.size(width = 180.dp, height = 240.dp),
                shape = MaterialTheme.shapes.medium,
                glyphSize = 96.dp,
            )
            Spacer(Modifier.height(spacing.lg))
            Text(
                "Pick a voice",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(spacing.sm))
            Text(
                "Storyvox uses an offline neural TTS engine. Pick a starter " +
                    "voice — you can add more in Settings → Voice library later.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(spacing.xl))

            recommended.forEach { voice ->
                VoiceTile(
                    voice = voice,
                    isDownloading = downloadingVoiceId == voice.id,
                    progress = progress.takeIf { downloadingVoiceId == voice.id },
                    enabled = downloadingVoiceId == null,
                    onPick = { onPick(voice.id) },
                    onDismissError = onDismissProgress,
                )
                Spacer(Modifier.height(spacing.sm))
            }

            Spacer(Modifier.height(spacing.md))
            BrassButton(
                label = "More voices →",
                onClick = onOpenLibrary,
                variant = BrassButtonVariant.Text,
                enabled = downloadingVoiceId == null,
            )
            Spacer(Modifier.height(spacing.xs))
            BrassButton(
                label = "Continue without audio",
                onClick = onSkip,
                variant = BrassButtonVariant.Text,
                enabled = downloadingVoiceId == null,
            )
            Spacer(Modifier.height(spacing.xs))
            Text(
                "Reader-only mode skips audio playback for this session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(spacing.xl))
        }
    }
}

@Composable
private fun VoiceTile(
    voice: UiVoiceInfo,
    isDownloading: Boolean,
    progress: DownloadProgress?,
    enabled: Boolean,
    onPick: () -> Unit,
    onDismissError: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val sizeMb = (voice.sizeBytes / 1_000_000L).coerceAtLeast(1L)
    val failed = progress as? DownloadProgress.Failed

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled && !isDownloading) { onPick() }
            .padding(spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Text(
                    voice.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${voice.language} · ${voice.qualityLevel.name.lowercase()} · ${sizeMb} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (isDownloading && progress != null) {
            Spacer(Modifier.height(spacing.sm))
            DownloadProgressBlock(
                progress = progress,
                onDismissError = onDismissError,
            )
        } else if (failed != null) {
            Spacer(Modifier.height(spacing.sm))
            DownloadProgressBlock(progress = failed, onDismissError = onDismissError)
        }
    }
}

@Composable
private fun DownloadProgressBlock(
    progress: DownloadProgress,
    onDismissError: () -> Unit,
) {
    val spacing = LocalSpacing.current
    when (progress) {
        DownloadProgress.Resolving -> {
            Text("Resolving…", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(spacing.xs))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is DownloadProgress.Downloading -> {
            val pct = if (progress.totalBytes > 0L) {
                (progress.bytesRead.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
            } else 0f
            val mb = progress.bytesRead / 1_000_000
            val totalMb = progress.totalBytes / 1_000_000
            Text(
                "Downloading… $mb MB / $totalMb MB",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(spacing.xs))
            if (progress.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = { pct },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        DownloadProgress.Done -> {
            Text(
                "Voice ready.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        is DownloadProgress.Failed -> {
            Text(
                progress.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(spacing.xs))
            BrassButton(
                label = "Try again",
                onClick = onDismissError,
                variant = BrassButtonVariant.Text,
            )
        }
    }
}
