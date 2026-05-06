package `in`.jphe.storyvox.feature.voicelibrary

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.playback.voice.UiVoiceInfo
import `in`.jphe.storyvox.playback.voice.VoiceManager
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class VoiceLibraryUiState(
    val installed: List<UiVoiceInfo> = emptyList(),
    val available: List<UiVoiceInfo> = emptyList(),
    val activeVoiceId: String? = null,
    val currentDownload: DownloadingVoice? = null,
    val pendingDelete: UiVoiceInfo? = null,
    val errorMessage: String? = null,
)

@Immutable
data class DownloadingVoice(
    val voiceId: String,
    /** Null while the download is still resolving / no total known. */
    val progress: Float?,
)

@HiltViewModel
class VoiceLibraryViewModel @Inject constructor(
    private val voiceManager: VoiceManager,
) : ViewModel() {

    private val _currentDownload = MutableStateFlow<DownloadingVoice?>(null)
    private val _pendingDelete = MutableStateFlow<UiVoiceInfo?>(null)
    private val _error = MutableStateFlow<String?>(null)

    private var downloadJob: Job? = null

    val uiState: StateFlow<VoiceLibraryUiState> = combine(
        voiceManager.installedVoices,
        flowOf(voiceManager.availableVoices),
        voiceManager.activeVoice,
        _currentDownload.asStateFlow(),
        combine(_pendingDelete.asStateFlow(), _error.asStateFlow()) { d, e -> d to e },
    ) { installed, available, active, downloading, (pending, error) ->
        // Available list excludes anything already installed so the two
        // sections never present the same voice twice.
        val installedIds = installed.mapTo(mutableSetOf()) { it.id }
        VoiceLibraryUiState(
            installed = installed,
            available = available.filterNot { it.id in installedIds },
            activeVoiceId = active?.id,
            currentDownload = downloading,
            pendingDelete = pending,
            errorMessage = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VoiceLibraryUiState())

    fun onRowTapped(voice: UiVoiceInfo) {
        if (voice.isInstalled) activate(voice.id) else download(voice.id)
    }

    fun activate(voiceId: String) {
        viewModelScope.launch { voiceManager.setActive(voiceId) }
    }

    fun download(voiceId: String) {
        if (_currentDownload.value != null) return
        _currentDownload.value = DownloadingVoice(voiceId, progress = null)
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            try {
                voiceManager.download(voiceId).collect { p ->
                    when (p) {
                        is VoiceManager.DownloadProgress.Resolving -> {
                            _currentDownload.value = DownloadingVoice(voiceId, progress = null)
                        }
                        is VoiceManager.DownloadProgress.Downloading -> {
                            val frac = if (p.totalBytes > 0L) {
                                (p.bytesRead.toFloat() / p.totalBytes).coerceIn(0f, 1f)
                            } else null
                            _currentDownload.value = DownloadingVoice(voiceId, frac)
                        }
                        is VoiceManager.DownloadProgress.Done -> Unit
                        is VoiceManager.DownloadProgress.Failed -> {
                            _error.value = p.reason
                        }
                    }
                }
            } catch (t: Throwable) {
                _error.value = t.message ?: "Download failed"
            } finally {
                _currentDownload.value = null
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _currentDownload.value = null
    }

    fun requestDelete(voice: UiVoiceInfo) {
        _pendingDelete.value = voice
    }

    fun cancelDelete() {
        _pendingDelete.value = null
    }

    fun confirmDelete() {
        val voice = _pendingDelete.value ?: return
        _pendingDelete.value = null
        viewModelScope.launch { voiceManager.delete(voice.id) }
    }

    fun dismissError() {
        _error.value = null
    }
}
