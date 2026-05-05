package `in`.jphe.storyvox.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.feature.api.UiSettings
import `in`.jphe.storyvox.feature.api.UiVoice
import `in`.jphe.storyvox.feature.api.VoiceProviderUi
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class SettingsUiState(
    val settings: UiSettings? = null,
    val voices: List<UiVoice> = emptyList(),
    val isVoxSherpaInstalled: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepositoryUi,
    private val voices: VoiceProviderUi,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        repo.settings,
        voices.installedVoices,
        voices.isVoxSherpaInstalled,
    ) { settings, installed, voxSherpa ->
        SettingsUiState(settings = settings, voices = installed, isVoxSherpaInstalled = voxSherpa)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(t: ThemeOverride) = viewModelScope.launch { repo.setTheme(t) }
    fun setSpeed(s: Float) = viewModelScope.launch { repo.setDefaultSpeed(s) }
    fun setPitch(p: Float) = viewModelScope.launch { repo.setDefaultPitch(p) }
    fun setDefaultVoice(id: String?) = viewModelScope.launch { repo.setDefaultVoice(id) }
    fun setWifiOnly(enabled: Boolean) = viewModelScope.launch { repo.setDownloadOnWifiOnly(enabled) }
    fun setPollHours(h: Int) = viewModelScope.launch { repo.setPollIntervalHours(h) }
    fun signIn() = viewModelScope.launch { repo.signIn() }
    fun signOut() = viewModelScope.launch { repo.signOut() }
    fun installVoxSherpa() = voices.openVoxSherpaInstall()
    fun previewVoice(voice: UiVoice) = voices.previewVoice(voice)
}
