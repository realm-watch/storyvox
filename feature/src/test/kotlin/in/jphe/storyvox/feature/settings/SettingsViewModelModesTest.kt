package `in`.jphe.storyvox.feature.settings

import `in`.jphe.storyvox.feature.api.BUFFER_DEFAULT_CHUNKS
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
 * Verifies the issue #98 Mode A / Mode B launchers on
 * [SettingsViewModel] forward the value to the repository contract and
 * that [SettingsUiState.settings] surfaces the repository's emissions.
 *
 * Mirrors [SettingsViewModelBufferTest]'s shape so the pair stays
 * easy to compare side-by-side.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelModesTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setWarmupWait forwards to the repository`() = runTest {
        val repo = FakeSettingsRepo(initial = baseSettings(warmupWait = true, catchupPause = true))
        val vm = SettingsViewModel(repo, FakeVoiceProvider())

        vm.setWarmupWait(false)

        assertEquals(listOf(false), repo.warmupWaitWrites)
    }

    @Test
    fun `setCatchupPause forwards to the repository`() = runTest {
        val repo = FakeSettingsRepo(initial = baseSettings(warmupWait = true, catchupPause = true))
        val vm = SettingsViewModel(repo, FakeVoiceProvider())

        vm.setCatchupPause(false)

        assertEquals(listOf(false), repo.catchupPauseWrites)
    }

    @Test
    fun `viewmodel uiState surfaces both mode values`() = runTest {
        val repo = FakeSettingsRepo(initial = baseSettings(warmupWait = false, catchupPause = false))
        val vm = SettingsViewModel(repo, FakeVoiceProvider())

        val emitted = vm.uiState.first { it.settings != null }
        assertEquals(false, emitted.settings?.warmupWait)
        assertEquals(false, emitted.settings?.catchupPause)
    }

    @Test
    fun `Mode A and Mode B writes are independent`() = runTest {
        val repo = FakeSettingsRepo(initial = baseSettings(warmupWait = true, catchupPause = true))
        val vm = SettingsViewModel(repo, FakeVoiceProvider())

        vm.setWarmupWait(false)
        vm.setCatchupPause(false)
        vm.setWarmupWait(true)

        assertEquals(listOf(false, true), repo.warmupWaitWrites)
        assertEquals(listOf(false), repo.catchupPauseWrites)
    }

    private fun baseSettings(warmupWait: Boolean, catchupPause: Boolean): UiSettings = UiSettings(
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
        warmupWait = warmupWait,
        catchupPause = catchupPause,
    )

    private class FakeSettingsRepo(initial: UiSettings) : SettingsRepositoryUi {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<UiSettings> = state
        val warmupWaitWrites: MutableList<Boolean> = mutableListOf()
        val catchupPauseWrites: MutableList<Boolean> = mutableListOf()
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
            state.value = state.value.copy(punctuationPauseMultiplier = multiplier)
        }
        override suspend fun setPlaybackBufferChunks(chunks: Int) {
            state.value = state.value.copy(playbackBufferChunks = chunks)
        }
        override suspend fun setWarmupWait(enabled: Boolean) {
            warmupWaitWrites += enabled
            state.value = state.value.copy(warmupWait = enabled)
        }
        override suspend fun setCatchupPause(enabled: Boolean) {
            catchupPauseWrites += enabled
            state.value = state.value.copy(catchupPause = enabled)
        }
        override suspend fun signIn() = Unit
        override suspend fun signOut() = Unit
        // Memory Palace stubs (#79) — modes-test fixture doesn't exercise
        // the palace surface; keep them no-op + return NotConfigured.
        override suspend fun setPalaceHost(host: String) = Unit
        override suspend fun setPalaceApiKey(apiKey: String) = Unit
        override suspend fun clearPalaceConfig() = Unit
        override suspend fun testPalaceConnection():
            `in`.jphe.storyvox.feature.api.PalaceProbeResult =
            `in`.jphe.storyvox.feature.api.PalaceProbeResult.NotConfigured
    }

    private class FakeVoiceProvider : VoiceProviderUi {
        override val installedVoices: Flow<List<UiVoice>> = flowOf(emptyList())
        override fun previewVoice(voice: UiVoice) = Unit
    }
}
