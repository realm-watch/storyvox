package `in`.jphe.storyvox.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.feature.api.PalaceProbeResult
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.feature.api.UiLlmProvider
import `in`.jphe.storyvox.feature.api.UiSettings
import `in`.jphe.storyvox.feature.api.UiVoice
import `in`.jphe.storyvox.feature.api.VoiceProviderUi
import `in`.jphe.storyvox.llm.LlmRepository
import `in`.jphe.storyvox.llm.ProbeResult
import `in`.jphe.storyvox.llm.ProviderId
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
    /** Most recent Test-connection probe outcome. Settings UI
     *  surfaces this as a transient toast/message under the Test
     *  button. */
    val probeOutcome: ProbeOutcome? = null,
)

/** Stable-typed probe message for the AI Settings UI. Distinct from
 *  [ProbeResult] so the feature module doesn't expose :core-llm
 *  types directly to the UI layer (this VM is the conversion seam). */
sealed class ProbeOutcome {
    object Ok : ProbeOutcome()
    data class Failure(val message: String) : ProbeOutcome()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepositoryUi,
    private val voices: VoiceProviderUi,
    private val llm: LlmRepository,
) : ViewModel() {

    private val palaceProbe = MutableStateFlow<PalaceProbeResult?>(null)
    private val palaceProbing = MutableStateFlow(false)
    private val _probe = MutableStateFlow<ProbeOutcome?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        repo.settings,
        voices.installedVoices,
        palaceProbe,
        palaceProbing,
        _probe,
    ) { settings, installed, palaceProbeResult, probing, probe ->
        SettingsUiState(
            settings = settings,
            voices = installed,
            palaceProbe = palaceProbeResult,
            palaceProbing = probing,
            probeOutcome = probe,
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
    /** Issue #85 — Voice-Determinism preset (Steady / Expressive). */
    fun setVoiceSteady(enabled: Boolean) = viewModelScope.launch { repo.setVoiceSteady(enabled) }
    fun signIn() = viewModelScope.launch { repo.signIn() }
    fun signOut() = viewModelScope.launch { repo.signOut() }
    /** GitHub OAuth (#91) — local sign-out. Remote revoke deep-links to github.com. */
    fun signOutGitHub() = viewModelScope.launch { repo.signOutGitHub() }
    /** Issue #203 — toggle "Enable private repos." Takes effect on the
     *  next sign-in; existing sessions keep their original scope until
     *  the user re-runs Device Flow. */
    fun setGitHubPrivateReposEnabled(enabled: Boolean) =
        viewModelScope.launch { repo.setGitHubPrivateReposEnabled(enabled) }
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

    // ── AI settings (issue #81) ────────────────────────────────────

    fun setAiProvider(provider: UiLlmProvider?) = viewModelScope.launch {
        // Spec-only providers can be persisted (they're enum values)
        // but the runtime providers will throw NotConfigured if used.
        // The UI's "coming soon" rows already prevent that path.
        repo.setAiProvider(provider)
    }
    fun setClaudeApiKey(key: String?) = viewModelScope.launch { repo.setClaudeApiKey(key) }
    fun setClaudeModel(model: String) = viewModelScope.launch { repo.setClaudeModel(model) }
    fun setOpenAiApiKey(key: String?) = viewModelScope.launch { repo.setOpenAiApiKey(key) }
    fun setOpenAiModel(model: String) = viewModelScope.launch { repo.setOpenAiModel(model) }
    fun setOllamaBaseUrl(url: String) = viewModelScope.launch { repo.setOllamaBaseUrl(url) }
    fun setOllamaModel(model: String) = viewModelScope.launch { repo.setOllamaModel(model) }
    fun setVertexApiKey(key: String?) = viewModelScope.launch { repo.setVertexApiKey(key) }
    fun setVertexModel(model: String) = viewModelScope.launch { repo.setVertexModel(model) }
    fun setFoundryApiKey(key: String?) = viewModelScope.launch { repo.setFoundryApiKey(key) }
    fun setFoundryEndpoint(url: String) = viewModelScope.launch { repo.setFoundryEndpoint(url) }
    fun setFoundryDeployment(deployment: String) =
        viewModelScope.launch { repo.setFoundryDeployment(deployment) }
    fun setFoundryServerless(serverless: Boolean) =
        viewModelScope.launch { repo.setFoundryServerless(serverless) }
    fun setBedrockAccessKey(key: String?) = viewModelScope.launch { repo.setBedrockAccessKey(key) }
    fun setBedrockSecretKey(key: String?) = viewModelScope.launch { repo.setBedrockSecretKey(key) }
    fun setBedrockRegion(region: String) = viewModelScope.launch { repo.setBedrockRegion(region) }
    fun setBedrockModel(model: String) = viewModelScope.launch { repo.setBedrockModel(model) }
    fun setSendChapterTextEnabled(enabled: Boolean) =
        viewModelScope.launch { repo.setSendChapterTextEnabled(enabled) }
    // Issue #212 — chat grounding-level toggles.
    fun setChatGroundChapterTitle(enabled: Boolean) =
        viewModelScope.launch { repo.setChatGroundChapterTitle(enabled) }
    fun setChatGroundCurrentSentence(enabled: Boolean) =
        viewModelScope.launch { repo.setChatGroundCurrentSentence(enabled) }
    fun setChatGroundEntireChapter(enabled: Boolean) =
        viewModelScope.launch { repo.setChatGroundEntireChapter(enabled) }
    fun setChatGroundEntireBookSoFar(enabled: Boolean) =
        viewModelScope.launch { repo.setChatGroundEntireBookSoFar(enabled) }
    fun acknowledgeAiPrivacy() = viewModelScope.launch { repo.acknowledgeAiPrivacy() }
    /** Anthropic Teams (OAuth) — local sign-out. Remote revoke deep-links
     *  to claude.ai/settings. (#181) */
    fun signOutTeams() = viewModelScope.launch { repo.signOutTeams() }
    fun resetAiSettings() = viewModelScope.launch {
        repo.resetAiSettings()
        _probe.value = null
    }

    /** Run the probe for the named UI provider. UI surfaces the
     *  outcome as a one-shot toast/message under the button. */
    fun testAiConnection(uiProvider: UiLlmProvider) = viewModelScope.launch {
        _probe.value = null
        val result = llm.probe(uiProvider.toCoreId())
        _probe.value = when (result) {
            ProbeResult.Ok -> ProbeOutcome.Ok
            is ProbeResult.AuthError -> ProbeOutcome.Failure(result.message)
            is ProbeResult.Misconfigured -> ProbeOutcome.Failure(result.message)
            is ProbeResult.NotReachable -> ProbeOutcome.Failure(result.message)
        }
    }

    fun clearProbeOutcome() { _probe.value = null }
}

/** Map the feature-layer enum to the :core-llm enum. The two are
 *  kept in lockstep — when a new value is added in one, the other
 *  must follow. */
private fun UiLlmProvider.toCoreId(): ProviderId = when (this) {
    UiLlmProvider.Claude -> ProviderId.Claude
    UiLlmProvider.OpenAi -> ProviderId.OpenAi
    UiLlmProvider.Ollama -> ProviderId.Ollama
    UiLlmProvider.Bedrock -> ProviderId.Bedrock
    UiLlmProvider.Vertex -> ProviderId.Vertex
    UiLlmProvider.Foundry -> ProviderId.Foundry
    UiLlmProvider.Teams -> ProviderId.Teams
}
