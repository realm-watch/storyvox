package `in`.jphe.storyvox.feature.api

import kotlinx.coroutines.flow.Flow

/**
 * Lightweight contracts the feature module depends on. The real impls live in
 * core-data (FictionRepository, FollowsRepository) and core-playback (PlaybackController).
 * Aurora ships these as interfaces so feature-module screens compile against a stable
 * surface; Selene + Hypnos provide bindings via Hilt in the app module.
 *
 * If any signature here doesn't match what core-data/core-playback expose by merge time,
 * Davis (orchestrator) reconciles in the spec-merge step.
 */

data class UiFiction(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val rating: Float,
    val chapterCount: Int,
    val isOngoing: Boolean,
    val synopsis: String,
)

data class UiChapter(
    val id: String,
    val number: Int,
    val title: String,
    val publishedRelative: String,
    val durationLabel: String,
    val isDownloaded: Boolean,
    val isFinished: Boolean,
)

data class UiFollow(
    val fiction: UiFiction,
    val unreadCount: Int,
)

enum class DownloadMode { Lazy, Eager, Subscribe }

interface FictionRepositoryUi {
    val library: Flow<List<UiFiction>>
    val follows: Flow<List<UiFollow>>
    fun fictionById(id: String): Flow<UiFiction?>
    fun chaptersFor(fictionId: String): Flow<List<UiChapter>>
    suspend fun setDownloadMode(fictionId: String, mode: DownloadMode)
    suspend fun follow(fictionId: String, follow: Boolean)
    suspend fun markAllCaughtUp()
    /**
     * Best-effort refresh of the user's source-side follows list. No-op if
     * the user isn't signed in. Caller doesn't await the result — the local
     * `follows` Flow will emit when the DB is upserted.
     */
    suspend fun refreshFollows()
}

interface BrowseRepositoryUi {
    /** Curated tabs — implemented as preset filters under the hood. */
    fun popular(): Flow<List<UiFiction>>
    fun newReleases(): Flow<List<UiFiction>>
    fun bestRated(): Flow<List<UiFiction>>
    /** Plain-text search keeps the legacy entry point for the search tab field. */
    fun search(query: String): Flow<List<UiFiction>>
    /** Full filtered search — encodes every Royal Road filter we surface in the UI. */
    fun filtered(filter: BrowseFilter): Flow<List<UiFiction>>
}

/**
 * UI-side filter spec that mirrors the Royal Road `/fictions/search` form.
 * Lives in feature/api so the bottom sheet and ViewModel can hold and mutate
 * it without taking a dep on `core-data`. The app-module adapter maps it onto
 * `SearchQuery` at the boundary.
 */
data class BrowseFilter(
    val term: String = "",
    val tagsInclude: Set<String> = emptySet(),
    val tagsExclude: Set<String> = emptySet(),
    val statuses: Set<UiFictionStatus> = emptySet(),
    val type: UiFictionType = UiFictionType.All,
    val warningsRequire: Set<UiContentWarning> = emptySet(),
    val warningsExclude: Set<UiContentWarning> = emptySet(),
    val minPages: Int? = null,
    val maxPages: Int? = null,
    val minRating: Float? = null,
    val maxRating: Float? = null,
    val orderBy: UiSearchOrder = UiSearchOrder.Popularity,
    val direction: UiSortDirection = UiSortDirection.Desc,
)

enum class UiFictionStatus { Ongoing, Completed, Hiatus, Stub, Dropped }
enum class UiFictionType { All, Original, FanFiction }
enum class UiContentWarning { Profanity, SexualContent, GraphicViolence, SensitiveContent, AiAssisted, AiGenerated }
enum class UiSearchOrder { Relevance, Popularity, Rating, LastUpdate, ReleaseDate, Followers, Length, Views, Title }
enum class UiSortDirection { Asc, Desc }

data class UiPlaybackState(
    val fictionId: String?,
    val chapterId: String?,
    val chapterTitle: String,
    val fictionTitle: String,
    val coverUrl: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** Char index into the chapter text where the current sentence begins. */
    val sentenceStart: Int,
    /** Exclusive end index of the current sentence. */
    val sentenceEnd: Int,
    val speed: Float,
    val pitch: Float,
    val voiceId: String?,
    val voiceLabel: String,
    /** Null when no sleep timer is active; otherwise milliseconds until it fires. */
    val sleepTimerRemainingMs: Long? = null,
)

/** Mirrors core-playback's SleepTimerMode without leaking the playback module to feature. */
sealed class UiSleepTimerMode {
    data class Duration(val minutes: Int) : UiSleepTimerMode()
    data object EndOfChapter : UiSleepTimerMode()
}

interface PlaybackControllerUi {
    val state: Flow<UiPlaybackState>
    val chapterText: Flow<String>
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun skipForward()
    fun skipBack()
    fun nextChapter()
    fun previousChapter()
    fun setSpeed(speed: Float)
    fun setPitch(pitch: Float)
    fun setVoice(voiceId: String)
    fun startListening(fictionId: String, chapterId: String, charOffset: Int = 0)
    fun startSleepTimer(mode: UiSleepTimerMode)
    fun cancelSleepTimer()
}

data class UiVoice(
    val id: String,
    val label: String,
    val engine: String,
    val locale: String,
)

/**
 * Legacy voice surface. v0.4.0 introduced [VoiceManager] in core-playback as the
 * canonical source for voice install/select/download — new code should depend on
 * that directly. This contract is kept slim for the few remaining consumers
 * (SettingsViewModel, the legacy VoicePickerScreen, ReaderViewModel) that just
 * need an Android-TextToSpeech-backed voice list and one-shot preview.
 */
interface VoiceProviderUi {
    val installedVoices: Flow<List<UiVoice>>
    fun previewVoice(voice: UiVoice)
}

data class UiSettings(
    val ttsEngine: String,
    val defaultVoiceId: String?,
    val defaultSpeed: Float,
    val defaultPitch: Float,
    val themeOverride: ThemeOverride,
    val downloadOnWifiOnly: Boolean,
    val pollIntervalHours: Int,
    val isSignedIn: Boolean,
    val sigil: UiSigil = UiSigil.UNKNOWN,
)

/**
 * Realm-sigil version metadata captured at build time. Surfaced in the
 * Settings → About row. A "fantasy"-realm sigil reads as e.g.
 * "Blazing Crown · ef6a4cf3".
 */
data class UiSigil(
    val name: String,
    val realm: String,
    val hash: String,
    val branch: String,
    val dirty: Boolean,
    val built: String,
    val repo: String,
    val versionName: String,
) {
    val commitUrl: String
        get() = if (hash != "dev" && repo.isNotBlank()) "$repo/commit/$hash" else ""

    companion object {
        val UNKNOWN = UiSigil(
            name = "Unsigned · dev",
            realm = "fantasy",
            hash = "dev",
            branch = "unknown",
            dirty = false,
            built = "unknown",
            repo = "",
            versionName = "0.0.0",
        )
    }
}

enum class ThemeOverride { System, Dark, Light }

interface SettingsRepositoryUi {
    val settings: Flow<UiSettings>
    suspend fun setTheme(override: ThemeOverride)
    suspend fun setDefaultSpeed(speed: Float)
    suspend fun setDefaultPitch(pitch: Float)
    suspend fun setDefaultVoice(voiceId: String?)
    suspend fun setDownloadOnWifiOnly(enabled: Boolean)
    suspend fun setPollIntervalHours(hours: Int)
    suspend fun signIn()
    suspend fun signOut()
}
