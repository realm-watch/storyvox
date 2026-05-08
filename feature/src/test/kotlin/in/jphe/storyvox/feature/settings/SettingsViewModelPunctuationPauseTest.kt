package `in`.jphe.storyvox.feature.settings

import `in`.jphe.storyvox.feature.api.BUFFER_DEFAULT_CHUNKS
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_DEFAULT_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_LONG_MULTIPLIER
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.feature.api.UiLlmProvider
import `in`.jphe.storyvox.feature.api.UiSettings
import `in`.jphe.storyvox.feature.api.UiSigil
import `in`.jphe.storyvox.feature.api.UiVoice
import `in`.jphe.storyvox.feature.api.VoiceProviderUi
import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmRepository
import `in`.jphe.storyvox.llm.provider.ClaudeApiProvider
import `in`.jphe.storyvox.llm.provider.OllamaProvider
import `in`.jphe.storyvox.llm.provider.OpenAiApiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Verifies the issue #109 punctuation-pause continuous slider on
 * [SettingsViewModel] forwards the float to the repository contract and
 * surfaces the repository's emission via [SettingsUiState.settings].
 *
 * Mirrors [SettingsViewModelBufferTest]'s shape — same Fake repo
 * scaffolding, same flow-collection pattern.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelPunctuationPauseTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setPunctuationPauseMultiplier forwards to the repository`() = runTest {
        val repo = FakeSettingsRepo(
            initial = baseSettings(multiplier = PUNCTUATION_PAUSE_DEFAULT_MULTIPLIER),
        )
        val vm = SettingsViewModel(repo, FakeVoiceProvider(), fakeLlm())

        vm.setPunctuationPauseMultiplier(2.5f)

        assertEquals(listOf(2.5f), repo.punctuationPauseMultiplierWrites)
    }

    @Test
    fun `viewmodel uiState surfaces repository's punctuation pause multiplier`() = runTest {
        val repo = FakeSettingsRepo(
            initial = baseSettings(multiplier = PUNCTUATION_PAUSE_LONG_MULTIPLIER),
        )
        val vm = SettingsViewModel(repo, FakeVoiceProvider(), fakeLlm())

        val emitted = vm.uiState.first { it.settings != null }
        assertEquals(
            PUNCTUATION_PAUSE_LONG_MULTIPLIER,
            emitted.settings?.punctuationPauseMultiplier ?: Float.NaN,
            0.0001f,
        )
    }

    @Test
    fun `viewmodel forwards values past the legacy Long stop`() = runTest {
        // The whole point of #109 is the slider goes wider than the legacy
        // 1.75× ceiling. The ViewModel must not clamp; that's the repo's job
        // (it applies the engine's [0..4] range).
        val repo = FakeSettingsRepo(
            initial = baseSettings(multiplier = PUNCTUATION_PAUSE_DEFAULT_MULTIPLIER),
        )
        val vm = SettingsViewModel(repo, FakeVoiceProvider(), fakeLlm())

        vm.setPunctuationPauseMultiplier(3.5f)

        assertEquals(listOf(3.5f), repo.punctuationPauseMultiplierWrites)
    }

    private fun baseSettings(multiplier: Float): UiSettings = UiSettings(
        ttsEngine = "VoxSherpa",
        defaultVoiceId = null,
        defaultSpeed = 1.0f,
        defaultPitch = 1.0f,
        themeOverride = ThemeOverride.System,
        downloadOnWifiOnly = true,
        pollIntervalHours = 6,
        isSignedIn = false,
        sigil = UiSigil.UNKNOWN,
        playbackBufferChunks = BUFFER_DEFAULT_CHUNKS,
        punctuationPauseMultiplier = multiplier,
    )

    private class FakeSettingsRepo(initial: UiSettings) : SettingsRepositoryUi {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<UiSettings> = state
        val punctuationPauseMultiplierWrites: MutableList<Float> = mutableListOf()
        override suspend fun setTheme(override: ThemeOverride) {
            state.value = state.value.copy(themeOverride = override)
        }
        override suspend fun setDefaultSpeed(speed: Float) {
            state.value = state.value.copy(defaultSpeed = speed)
        }
        override suspend fun setDefaultPitch(pitch: Float) {
            state.value = state.value.copy(defaultPitch = pitch)
        }
        override suspend fun setDefaultVoice(voiceId: String?) {
            state.value = state.value.copy(defaultVoiceId = voiceId)
        }
        override suspend fun setDownloadOnWifiOnly(enabled: Boolean) {
            state.value = state.value.copy(downloadOnWifiOnly = enabled)
        }
        override suspend fun setPollIntervalHours(hours: Int) {
            state.value = state.value.copy(pollIntervalHours = hours)
        }
        override suspend fun setPunctuationPauseMultiplier(multiplier: Float) {
            punctuationPauseMultiplierWrites += multiplier
            state.value = state.value.copy(punctuationPauseMultiplier = multiplier)
        }
        override suspend fun setPlaybackBufferChunks(chunks: Int) {
            state.value = state.value.copy(playbackBufferChunks = chunks)
        }
        override suspend fun setWarmupWait(enabled: Boolean) {
            state.value = state.value.copy(warmupWait = enabled)
        }
        override suspend fun setCatchupPause(enabled: Boolean) {
            state.value = state.value.copy(catchupPause = enabled)
        }
        override suspend fun signIn() = Unit
        override suspend fun signOut() = Unit
        // Memory Palace stubs (#79) — punctuation-pause-test fixture doesn't
        // exercise the palace surface; keep them no-op + return NotConfigured.
        override suspend fun setPalaceHost(host: String) = Unit
        override suspend fun setPalaceApiKey(apiKey: String) = Unit
        override suspend fun clearPalaceConfig() = Unit
        override suspend fun testPalaceConnection():
            `in`.jphe.storyvox.feature.api.PalaceProbeResult =
            `in`.jphe.storyvox.feature.api.PalaceProbeResult.NotConfigured

        // ── AI no-ops (#81) — punctuation-pause-test fixture doesn't exercise these. ──
        override suspend fun setAiProvider(provider: UiLlmProvider?) = Unit
        override suspend fun setClaudeApiKey(key: String?) = Unit
        override suspend fun setClaudeModel(model: String) = Unit
        override suspend fun setOpenAiApiKey(key: String?) = Unit
        override suspend fun setOpenAiModel(model: String) = Unit
        override suspend fun setOllamaBaseUrl(url: String) = Unit
        override suspend fun setOllamaModel(model: String) = Unit
        override suspend fun setSendChapterTextEnabled(enabled: Boolean) = Unit
        override suspend fun acknowledgeAiPrivacy() = Unit
        override suspend fun resetAiSettings() = Unit
    }

    /** Construct an LlmRepository with three real-but-stubbed provider
     *  instances. The punctuation-pause tests don't call any LLM methods,
     *  so we just need an LlmRepository that doesn't blow up at
     *  construction. Mirrors [SettingsViewModelBufferTest.fakeLlm]. */
    private fun fakeLlm(): LlmRepository {
        val cfg = flowOf(LlmConfig())
        val store = `in`.jphe.storyvox.llm.LlmCredentialsStore.forTesting()
        val http = OkHttpClient()
        val json = Json
        return LlmRepository(
            configFlow = cfg,
            claude = ClaudeApiProvider(http, store, cfg, json),
            openAi = OpenAiApiProvider(http, store, cfg, json),
            ollama = OllamaProvider(http, cfg, json),
        )
    }

    private class FakeVoiceProvider : VoiceProviderUi {
        override val installedVoices: Flow<List<UiVoice>> = flowOf(emptyList())
        override fun previewVoice(voice: UiVoice) = Unit
    }
}
