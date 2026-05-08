package `in`.jphe.storyvox.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.feature.api.PalaceProbeResult
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.feature.api.UiSettings
import `in`.jphe.storyvox.feature.api.UiVoice
import `in`.jphe.storyvox.feature.api.VoiceProviderUi
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class SettingsUiState(
    val settings: UiSettings? = null,
    val voices: List<UiVoice> = emptyList(),
    /** Last [`SettingsRepositoryUi.testPalaceConnection`] result, or null
     *  before the user has tried. Drives the inline status message under
     *  the Memory Palace section. */
    val palaceProbe: PalaceProbeResult? = null,
    /** True while a probe is in flight (button shows spinner). */
    val palaceProbing: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepositoryUi,
    private val voices: VoiceProviderUi,
) : ViewModel() {

    private val palaceProbe = MutableStateFlow<PalaceProbeResult?>(null)
    private val palaceProbing = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> = combine(
        repo.settings,
        voices.installedVoices,
        palaceProbe,
        palaceProbing,
    ) { settings, installed, probe, probing ->
        SettingsUiState(
            settings = settings,
            voices = installed,
            palaceProbe = probe,
            palaceProbing = probing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(t: ThemeOverride) = viewModelScope.launch { repo.setTheme(t) }
    fun setSpeed(s: Float) = viewModelScope.launch { repo.setDefaultSpeed(s) }
    fun setPitch(p: Float) = viewModelScope.launch { repo.setDefaultPitch(p) }
    fun setDefaultVoice(id: String?) = viewModelScope.launch { repo.setDefaultVoice(id) }
    fun setWifiOnly(enabled: Boolean) = viewModelScope.launch { repo.setDownloadOnWifiOnly(enabled) }
    fun setPollHours(h: Int) = viewModelScope.launch { repo.setPollIntervalHours(h) }
    /** Issue #109 — continuous inter-sentence pause multiplier (was a
     *  3-stop selector under #93). Repo coerces to the engine's [0..4]
     *  range; the slider in the screen passes a raw Float. */
    fun setPunctuationPauseMultiplier(multiplier: Float) =
        viewModelScope.launch { repo.setPunctuationPauseMultiplier(multiplier) }
    fun setPlaybackBufferChunks(n: Int) = viewModelScope.launch { repo.setPlaybackBufferChunks(n) }
    /** Issue #98 — Mode A toggle. */
    fun setWarmupWait(enabled: Boolean) = viewModelScope.launch { repo.setWarmupWait(enabled) }
    /** Issue #98 — Mode B toggle. */
    fun setCatchupPause(enabled: Boolean) = viewModelScope.launch { repo.setCatchupPause(enabled) }
    fun signIn() = viewModelScope.launch { repo.signIn() }
    fun signOut() = viewModelScope.launch { repo.signOut() }
    fun previewVoice(voice: UiVoice) = voices.previewVoice(voice)

    // ─── Memory Palace (#79) ────────────────────────────────────────────
    fun setPalaceHost(host: String) = viewModelScope.launch {
        repo.setPalaceHost(host)
        // Clear any previous probe result — the user is changing the
        // address so the previous status is no longer authoritative.
        palaceProbe.value = null
    }

    fun setPalaceApiKey(apiKey: String) = viewModelScope.launch {
        repo.setPalaceApiKey(apiKey)
        palaceProbe.value = null
    }

    fun clearPalaceConfig() = viewModelScope.launch {
        repo.clearPalaceConfig()
        palaceProbe.value = null
    }

    fun testPalaceConnection() = viewModelScope.launch {
        if (palaceProbing.value) return@launch
        palaceProbing.value = true
        try {
            palaceProbe.value = repo.testPalaceConnection()
        } finally {
            palaceProbing.value = false
        }
    }
}
