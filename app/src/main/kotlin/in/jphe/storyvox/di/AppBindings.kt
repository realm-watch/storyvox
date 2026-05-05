package `in`.jphe.storyvox.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.source.WebViewFetcher
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.feature.api.BrowseRepositoryUi
import `in`.jphe.storyvox.feature.api.DownloadMode
import `in`.jphe.storyvox.feature.api.FictionRepositoryUi
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.feature.api.UiChapter
import `in`.jphe.storyvox.feature.api.UiFiction
import `in`.jphe.storyvox.feature.api.UiFollow
import `in`.jphe.storyvox.feature.api.UiPlaybackState
import `in`.jphe.storyvox.feature.api.UiSettings
import `in`.jphe.storyvox.feature.api.UiVoice
import `in`.jphe.storyvox.feature.api.VoiceProviderUi
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * v1 stub adapters that bridge `feature.api.*` UI contracts to the concrete
 * core-data repositories and core-playback controller.
 *
 * Why stubs: Aurora's `feature.api` interfaces, Selene's Repository signatures,
 * and Hypnos's PlaybackController shape were drafted in parallel and don't yet
 * line up field-for-field. These stubs satisfy the Hilt graph so the build is
 * green; the screens render placeholder state. Each adapter has a clear TODO
 * for the integration session that wires it to its real repository.
 *
 * Replacement order, easiest first:
 * 1. SettingsRepositoryUi  → wire to real SettingsRepository (mostly UI-shaped already)
 * 2. PlaybackControllerUi  → wire to PlaybackController + ChapterRepository
 * 3. FictionRepositoryUi   → wire to LibraryRepository + FollowsRepository + FictionRepository
 * 4. BrowseRepositoryUi    → wire to FictionRepository.browsePopular/browseLatest
 * 5. VoiceProviderUi       → wire to TextToSpeech engine listing
 *
 * See `docs/superpowers/specs/2026-05-05-storyvox-design.md` §12 item #9.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppBindings {

    @Provides @Singleton
    fun provideFictionRepositoryUi(): FictionRepositoryUi = object : FictionRepositoryUi {
        override val library: Flow<List<UiFiction>> = flowOf(emptyList())
        override val follows: Flow<List<UiFollow>> = flowOf(emptyList())
        override fun fictionById(id: String): Flow<UiFiction?> = flowOf(null)
        override fun chaptersFor(fictionId: String): Flow<List<UiChapter>> = flowOf(emptyList())
        override suspend fun setDownloadMode(fictionId: String, mode: DownloadMode) {}
        override suspend fun follow(fictionId: String, follow: Boolean) {}
        override suspend fun markAllCaughtUp() {}
    }

    @Provides @Singleton
    fun provideBrowseRepositoryUi(): BrowseRepositoryUi = object : BrowseRepositoryUi {
        override fun popular(): Flow<List<UiFiction>> = flowOf(emptyList())
        override fun newReleases(): Flow<List<UiFiction>> = flowOf(emptyList())
        override fun bestRated(): Flow<List<UiFiction>> = flowOf(emptyList())
        override fun search(query: String): Flow<List<UiFiction>> = flowOf(emptyList())
    }

    @Provides @Singleton
    fun providePlaybackControllerUi(): PlaybackControllerUi = object : PlaybackControllerUi {
        private val _state = MutableStateFlow(emptyPlaybackState())
        override val state: Flow<UiPlaybackState> = _state
        override val chapterText: Flow<String> = flowOf("")
        override fun play() {}
        override fun pause() {}
        override fun seekTo(ms: Long) {}
        override fun skipForward() {}
        override fun skipBack() {}
        override fun nextChapter() {}
        override fun previousChapter() {}
        override fun setSpeed(speed: Float) {}
        override fun setPitch(pitch: Float) {}
        override fun setVoice(voiceId: String) {}
        override fun startListening(fictionId: String, chapterId: String) {}
    }

    @Provides @Singleton
    fun provideVoiceProviderUi(): VoiceProviderUi = object : VoiceProviderUi {
        override val installedVoices: Flow<List<UiVoice>> = flowOf(emptyList())
        override val isVoxSherpaInstalled: Flow<Boolean> = flowOf(false)
        override fun previewVoice(voice: UiVoice) {}
        override fun openVoxSherpaInstall() {}
    }

    @Provides @Singleton
    fun provideSettingsRepositoryUi(): SettingsRepositoryUi = object : SettingsRepositoryUi {
        override val settings: Flow<UiSettings> = flowOf(defaultSettings())
        override suspend fun setTheme(override: ThemeOverride) {}
        override suspend fun setDefaultSpeed(speed: Float) {}
        override suspend fun setDefaultPitch(pitch: Float) {}
        override suspend fun setDefaultVoice(voiceId: String?) {}
        override suspend fun setDownloadOnWifiOnly(enabled: Boolean) {}
        override suspend fun setPollIntervalHours(hours: Int) {}
        override suspend fun signIn() {}
        override suspend fun signOut() {}
    }

    /**
     * Stub WebViewFetcher — Selene's `:core-data` declares the interface; the
     * real impl in `:source-royalroad` is part of the deferred integration.
     * Returns a NetworkError with a clear message so any caller fails loudly.
     */
    @Provides @Singleton
    fun provideWebViewFetcher(): WebViewFetcher = object : WebViewFetcher {
        override suspend fun fetch(url: String, cookieHeader: String?): FictionResult<String> =
            FictionResult.NetworkError(
                message = "WebViewFetcher integration pending — see source-royalroad/_unintegrated/",
                cause = NotImplementedError("WebViewFetcher v1 stub"),
            )
    }
}

private fun emptyPlaybackState() = UiPlaybackState(
    fictionId = null,
    chapterId = null,
    chapterTitle = "",
    fictionTitle = "",
    coverUrl = null,
    isPlaying = false,
    positionMs = 0L,
    durationMs = 0L,
    sentenceStart = 0,
    sentenceEnd = 0,
    speed = 1.0f,
    pitch = 1.0f,
    voiceId = null,
    voiceLabel = "Default",
)

private fun defaultSettings() = UiSettings(
    ttsEngine = "System default",
    defaultVoiceId = null,
    defaultSpeed = 1.0f,
    defaultPitch = 1.0f,
    themeOverride = ThemeOverride.System,
    downloadOnWifiOnly = true,
    pollIntervalHours = 6,
    isSignedIn = false,
)
