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
}

interface BrowseRepositoryUi {
    fun popular(): Flow<List<UiFiction>>
    fun newReleases(): Flow<List<UiFiction>>
    fun bestRated(): Flow<List<UiFiction>>
    fun search(query: String): Flow<List<UiFiction>>
}

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
)

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
    fun startListening(fictionId: String, chapterId: String)
}

data class UiVoice(
    val id: String,
    val label: String,
    val engine: String,
    val locale: String,
)

interface VoiceProviderUi {
    val installedVoices: Flow<List<UiVoice>>
    fun previewVoice(voice: UiVoice)
    /** Returns true if VoxSherpa is installed. */
    val isVoxSherpaInstalled: Flow<Boolean>
    fun openVoxSherpaInstall()
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
)

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
