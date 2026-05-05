package `in`.jphe.storyvox.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.repository.FictionRepository
import `in`.jphe.storyvox.data.source.WebViewFetcher
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.SearchOrder
import `in`.jphe.storyvox.data.source.model.SearchQuery
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    fun provideFictionRepositoryUi(repo: FictionRepository): FictionRepositoryUi =
        RealFictionRepositoryUi(repo)

    @Provides @Singleton
    fun provideBrowseRepositoryUi(repo: FictionRepository): BrowseRepositoryUi =
        RealBrowseRepositoryUi(repo)

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

/**
 * Adapter from [FictionRepositoryUi] (Aurora's UI contract) to
 * [FictionRepository] (Selene's data layer).
 *
 * Library and Follows lists come straight from `observeLibrary()` /
 * `observeFollowsRemote()` (Flow).
 *
 * `fictionById(id)` triggers a one-shot `refreshDetail(id)` on first
 * subscription and then returns `observeFiction(id)` mapped to UiFiction.
 * The first emission may be null (no row cached yet); subsequent emissions
 * carry the real data once `refreshDetail` upserts.
 */
private class RealFictionRepositoryUi(
    private val repo: FictionRepository,
) : FictionRepositoryUi {

    override val library: Flow<List<UiFiction>> =
        repo.observeLibrary().map { list -> list.map(::toUiFiction) }

    override val follows: Flow<List<UiFollow>> =
        repo.observeFollowsRemote().map { list ->
            list.map { UiFollow(fiction = toUiFiction(it), unreadCount = 0) }
        }

    override fun fictionById(id: String): Flow<UiFiction?> = flow {
        // Kick off a refresh on first subscription; ignore failure (cached row may exist).
        repo.refreshDetail(id)
        emitAll(repo.observeFiction(id).map { detail -> detail?.summary?.let(::toUiFiction) })
    }

    override fun chaptersFor(fictionId: String): Flow<List<UiChapter>> =
        repo.observeFiction(fictionId).map { detail ->
            detail?.chapters.orEmpty().map { ch ->
                UiChapter(
                    id = ch.id,
                    number = ch.index + 1,
                    title = ch.title,
                    publishedRelative = relativeTime(ch.publishedAt),
                    durationLabel = ch.wordCount?.let { "${(it / 250).coerceAtLeast(1)} min" } ?: "",
                    isDownloaded = false,
                    isFinished = false,
                )
            }
        }

    override suspend fun setDownloadMode(fictionId: String, mode: DownloadMode) {
        repo.setDownloadMode(fictionId, mode.toData())
    }

    override suspend fun follow(fictionId: String, follow: Boolean) {
        if (follow) repo.addToLibrary(fictionId, mode = null) else repo.removeFromLibrary(fictionId)
    }

    override suspend fun markAllCaughtUp() {
        // No-op for v1 — chapter-level "read" tracking lands when the reader is wired.
    }
}

private fun DownloadMode.toData(): `in`.jphe.storyvox.data.db.entity.DownloadMode = when (this) {
    DownloadMode.Lazy -> `in`.jphe.storyvox.data.db.entity.DownloadMode.LAZY
    DownloadMode.Eager -> `in`.jphe.storyvox.data.db.entity.DownloadMode.EAGER
    DownloadMode.Subscribe -> `in`.jphe.storyvox.data.db.entity.DownloadMode.SUBSCRIBE
}

private fun relativeTime(epochMs: Long?): String {
    if (epochMs == null) return ""
    val deltaSec = (System.currentTimeMillis() - epochMs) / 1000L
    return when {
        deltaSec < 60 -> "just now"
        deltaSec < 3600 -> "${deltaSec / 60}m ago"
        deltaSec < 86_400 -> "${deltaSec / 3600}h ago"
        deltaSec < 86_400 * 7 -> "${deltaSec / 86_400}d ago"
        deltaSec < 86_400 * 30 -> "${deltaSec / (86_400 * 7)}w ago"
        else -> "${deltaSec / (86_400 * 30)}mo ago"
    }
}

/**
 * Adapter from [BrowseRepositoryUi] (Aurora's UI contract) to
 * [FictionRepository] (Selene's data layer). Each tab triggers a one-shot
 * suspend fetch on subscription via `flow { emit(...) }`; results are mapped
 * to [UiFiction]. Failures emit an empty list — the UI's empty-state already
 * communicates "nothing to show", and we don't want a transient network blip
 * to crash the screen. v1.1 should surface error state via a separate Flow.
 */
private class RealBrowseRepositoryUi(
    private val repo: FictionRepository,
) : BrowseRepositoryUi {

    override fun popular(): Flow<List<UiFiction>> = fetch { repo.browsePopular(page = 1) }
    override fun newReleases(): Flow<List<UiFiction>> = fetch { repo.browseLatest(page = 1) }
    override fun bestRated(): Flow<List<UiFiction>> = fetch { repo.browseLatest(page = 1) } // RR has no /best-rated equivalent in our v1 source; falling back to latest until byGenre fronts a "Best Rated" pill
    override fun search(query: String): Flow<List<UiFiction>> {
        if (query.isBlank()) return flowOf(emptyList())
        return fetch {
            repo.search(SearchQuery(term = query, orderBy = SearchOrder.RELEVANCE, page = 1))
        }
    }

    private inline fun fetch(crossinline call: suspend () -> FictionResult<`in`.jphe.storyvox.data.source.model.ListPage<FictionSummary>>): Flow<List<UiFiction>> =
        flow {
            when (val res = call()) {
                is FictionResult.Success -> emit(res.value.items.map(::toUiFiction))
                is FictionResult.Failure -> {
                    android.util.Log.w("storyvox", "Browse fetch failed: ${res.message}", res.cause)
                    emit(emptyList())
                }
            }
        }
}

private fun toUiFiction(s: FictionSummary): UiFiction = UiFiction(
    id = s.id,
    title = s.title,
    author = s.author.ifBlank { "Royal Road" },
    coverUrl = s.coverUrl,
    rating = s.rating ?: 0f,
    chapterCount = s.chapterCount ?: 0,
    isOngoing = s.status == FictionStatus.ONGOING,
    synopsis = s.description.orEmpty(),
)
