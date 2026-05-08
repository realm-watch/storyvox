package `in`.jphe.storyvox.feature.settings

import `in`.jphe.storyvox.feature.api.BUFFER_DEFAULT_CHUNKS
import `in`.jphe.storyvox.feature.api.BUFFER_RECOMMENDED_MAX_CHUNKS
import `in`.jphe.storyvox.feature.api.PunctuationPause
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.feature.api.UiSettings
import `in`.jphe.storyvox.feature.api.UiSigil
import `in`.jphe.storyvox.feature.api.UiVoice
import `in`.jphe.storyvox.feature.api.VoiceProviderUi
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Verifies that [SettingsViewModel.setPlaybackBufferChunks] forwards the
 * value to the repository contract and that the ViewModel's exposed
 * [SettingsUiState.settings] reflects what the repository emits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelBufferTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setPlaybackBufferChunks forwards to the repository`() = runTest {
        val repo = FakeSettingsRepo(initial = baseSettings(buffer = BUFFER_DEFAULT_CHUNKS))
        val vm = SettingsViewModel(repo, FakeVoiceProvider())

        vm.setPlaybackBufferChunks(192)

        assertEquals(listOf(192), repo.bufferWrites)
    }

    @Test
    fun `viewmodel uiState surfaces repository's buffer value`() = runTest {
        val repo = FakeSettingsRepo(initial = baseSettings(buffer = 256))
        val vm = SettingsViewModel(repo, FakeVoiceProvider())

        // The shared flow's WhileSubscribed needs an active subscriber; first()
        // covers that for the duration of the read.
        val emitted = vm.uiState.first { it.settings != null }
        assertEquals(256, emitted.settings?.playbackBufferChunks)
    }

    @Test
    fun `viewmodel allows past-the-tick values`() = runTest {
        // Issue #84 — the ViewModel must not clamp at the recommended max;
        // that's the whole point of the experimental probe. Repo is the only
        // layer that applies the absolute mechanical bounds.
        val repo = FakeSettingsRepo(initial = baseSettings(buffer = BUFFER_DEFAULT_CHUNKS))
        val vm = SettingsViewModel(repo, FakeVoiceProvider())

        vm.setPlaybackBufferChunks(BUFFER_RECOMMENDED_MAX_CHUNKS * 8)

        assertEquals(listOf(BUFFER_RECOMMENDED_MAX_CHUNKS * 8), repo.bufferWrites)
    }

    private fun baseSettings(buffer: Int): UiSettings = UiSettings(
        ttsEngine = "VoxSherpa",
        defaultVoiceId = null,
        defaultSpeed = 1.0f,
        defaultPitch = 1.0f,
        themeOverride = ThemeOverride.System,
        downloadOnWifiOnly = true,
        pollIntervalHours = 6,
        isSignedIn = false,
        sigil = UiSigil.UNKNOWN,
        playbackBufferChunks = buffer,
    )

    private class FakeSettingsRepo(initial: UiSettings) : SettingsRepositoryUi {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<UiSettings> = state
        val bufferWrites: MutableList<Int> = mutableListOf()
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
        override suspend fun setPunctuationPause(mode: PunctuationPause) {
            state.value = state.value.copy(punctuationPause = mode)
        }
        override suspend fun setPlaybackBufferChunks(chunks: Int) {
            bufferWrites += chunks
            state.value = state.value.copy(playbackBufferChunks = chunks)
        }
        override suspend fun signIn() = Unit
        override suspend fun signOut() = Unit
    }

    private class FakeVoiceProvider : VoiceProviderUi {
        override val installedVoices: Flow<List<UiVoice>> = flowOf(emptyList())
        override fun previewVoice(voice: UiVoice) = Unit
    }
}
