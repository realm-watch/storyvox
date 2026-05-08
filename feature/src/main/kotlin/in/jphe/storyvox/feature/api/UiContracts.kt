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

/** Outcome of pasting a URL into the add-fiction sheet. */
sealed class UiAddByUrlResult {
    /** Resolved + persisted; UI navigates to the detail screen. */
    data class Success(val fictionId: String) : UiAddByUrlResult()
    /** No source matched the input. */
    data object UnrecognizedUrl : UiAddByUrlResult()
    /** Recognised but not yet supported (GitHub today). */
    data class UnsupportedSource(val sourceId: String) : UiAddByUrlResult()
    /** Source-layer failure (network, 404, auth, rate-limit, ...). [message] is user-facing. */
    data class Error(val message: String) : UiAddByUrlResult()
}

interface FictionRepositoryUi {
    val library: Flow<List<UiFiction>>
    val follows: Flow<List<UiFollow>>
    fun fictionById(id: String): Flow<UiFiction?>
    /**
     * Mirrors [fictionById]'s underlying first-subscription refresh — emits
     * the error message of the most recent `refreshDetail(id)` attempt, or
     * null on success / not-yet-attempted. Lets screens distinguish
     * "still loading" (fiction == null, error == null) from "refresh failed
     * and we have no cache" (fiction == null, error != null). Cleared on
     * the next successful refresh.
     */
    fun fictionLoadError(id: String): Flow<String?>
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

    /**
     * Resolve a pasted URL (or short form) to a fiction, persist it, and
     * fetch detail. Implementation routes through `UrlRouter` + the
     * multi-source map. Returns enough information for the sheet to
     * navigate on success or surface a useful message on failure.
     */
    suspend fun addByUrl(url: String): UiAddByUrlResult
}

interface BrowseRepositoryUi {
    /**
     * Build a paginator for the given browse [source] against the
     * named [sourceId]. Callers consume `items` / `isLoading` /
     * `isAppending` / `hasMore` as Flows and call `loadNext()` on
     * initial composition + on near-end scroll.
     *
     * [sourceId] defaults to `royalroad` for backward compatibility
     * with existing call sites; the BrowseScreen source-picker UI
     * passes `github` when the user has chosen the GitHub tab.
     */
    fun paginator(
        source: BrowseSource,
        sourceId: String = `in`.jphe.storyvox.data.source.SourceIds.ROYAL_ROAD,
    ): BrowsePaginator
}

/** What kind of listing the paginator should fetch. The repository
 *  adapter maps each variant onto a source call (Royal Road for the
 *  legacy variants, GitHub for [FilteredGitHub]). */
sealed interface BrowseSource {
    data object Popular : BrowseSource
    data object NewReleases : BrowseSource
    data object BestRated : BrowseSource
    data class Search(val query: String) : BrowseSource
    data class Filtered(val filter: BrowseFilter) : BrowseSource
    /**
     * GitHub-shaped filter routed through `/search/repositories`.
     * `query` is the user's typed search term (may be blank); the
     * filter contributes additional GitHub query qualifiers (stars,
     * language, pushed-since, sort).
     */
    data class FilteredGitHub(
        val query: String,
        val filter: GitHubSearchFilter,
    ) : BrowseSource
}

/** A page-by-page accumulating cursor over a remote fiction listing.
 *  `loadNext()` is idempotent under concurrent calls — guarded by a
 *  mutex so the LazyGrid's near-end LaunchedEffect can fire freely. */
interface BrowsePaginator {
    val items: Flow<List<UiFiction>>
    /** True only on the very first page fetch (drives the skeleton grid). */
    val isLoading: Flow<Boolean>
    /** True while a subsequent page is being appended (drives the
     *  spinner footer below the existing grid). */
    val isAppending: Flow<Boolean>
    /** False once the source returned `hasNext = false`; the grid then
     *  shows an "end of list" footer. */
    val hasMore: Flow<Boolean>
    val error: Flow<String?>

    suspend fun loadNext()
    /** Reset to page 1 (caller should follow with [loadNext]). Wired up
     *  for future pull-to-refresh; not used in v1 of the feature. */
    suspend fun refresh()
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

/**
 * GitHub-shaped filter for Browse → GitHub. Different dimensions
 * from [BrowseFilter] because GitHub's `/search/repositories`
 * qualifiers don't translate to Royal Road's filter form. The
 * full set per spec (tags, status, length, last-updated, min-stars,
 * language, sort) is split: this PR (step 8c) lands minStars /
 * language / pushedSince / sort. Tag-multi-select and status would
 * be a follow-up if the curated registry grows enough that browsing
 * stops being good enough.
 */
data class GitHubSearchFilter(
    val minStars: Int? = null,
    /** ISO 639-1 language code, e.g. `"en"`. Null = no language
     *  qualifier (GitHub returns repos in any language). */
    val language: String? = null,
    val pushedSince: GitHubPushedSince = GitHubPushedSince.Any,
    val sort: GitHubSort = GitHubSort.BestMatch,
)

/** GitHub `pushed:>YYYY-MM-DD` cutoffs. `Any` omits the qualifier. */
enum class GitHubPushedSince { Any, Last7Days, Last30Days, Last90Days, LastYear }

/** GitHub `sort=` axis. `BestMatch` is the API default and omits the
 *  `sort=` parameter; the others map directly to GitHub's values. */
enum class GitHubSort { BestMatch, Stars, Updated }

data class UiPlaybackState(
    val fictionId: String?,
    val chapterId: String?,
    val chapterTitle: String,
    val fictionTitle: String,
    val coverUrl: String?,
    val isPlaying: Boolean,
    /** True when the streaming TTS pipeline has paused AudioTrack waiting
     *  for the producer to refill the queue past the underrun threshold.
     *  UI surfaces a "Buffering..." spinner; differs from `!isPlaying`
     *  (user pause) and from initial voice warm-up (see [isWarmingUp]).
     *
     *  Issue #98 — when the user disables Mode B (Catch-up Pause), the
     *  EnginePlayer consumer thread no longer pauses on underrun, so this
     *  flag stays false through underrun events and the consumer drains
     *  through the silence instead. UI surfaces no buffering spinner in
     *  that mode by construction. */
    val isBuffering: Boolean = false,
    /** True when the user has hit play but the voice engine hasn't
     *  produced the first sentence's audio yet. Distinct from
     *  [isBuffering] (mid-stream underrun). UI surfaces the "warming up"
     *  spinner + freezes wall-time scrubber interpolation.
     *
     *  Issue #98 — Mode A (Warm-up Wait) gates this. With Mode A on
     *  (default), this is `isPlaying && sentenceEnd == 0`. With Mode A
     *  off, this is always false: the UI behaves as if playback started
     *  immediately and the listener accepts silence at chapter start. */
    val isWarmingUp: Boolean = false,
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
    /** Seek by char offset into chapter text (used by tap-on-sentence). */
    fun seekToChar(charOffset: Int)
    fun skipForward()
    fun skipBack()
    fun nextChapter()
    fun previousChapter()
    fun setSpeed(speed: Float)
    fun setPitch(pitch: Float)
    fun setVoice(voiceId: String)
    /**
     * Scale the inter-sentence punctuation pause (issue #90). 0f disables
     * trailing silence entirely; 1f is the audiobook-tuned default; >1f
     * lengthens. Applied to the next sentence the producer generates —
     * the live pipeline rebuilds so the change takes effect on the next
     * sentence boundary, mirroring [setSpeed]/[setPitch].
     */
    fun setPunctuationPauseMultiplier(multiplier: Float)
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
    /**
     * Issue #109 — inter-sentence pause as a continuous multiplier (was a
     * 3-stop selector under #93). 0× disables trailing silence; 1× is the
     * audiobook-tuned default; the engine already coerces to [0..4]
     * ([in.jphe.storyvox.playback.tts.EnginePlayer.setPunctuationPauseMultiplier]).
     * Migrated from `pref_punctuation_pause` (enum) on first read; see
     * `PunctuationPauseEnumToMultiplierMigration` in
     * `:app`'s `SettingsRepositoryUiImpl` for the mapping.
     */
    val punctuationPauseMultiplier: Float = PUNCTUATION_PAUSE_DEFAULT_MULTIPLIER,
    val sigil: UiSigil = UiSigil.UNKNOWN,
    /**
     * Audio pre-synth queue depth, in sentence-chunks. Maps directly to
     * [EngineStreamingSource.queueCapacity]. The minimum 2 keeps a real
     * back-pressure buffer (1 in flight + 1 queued); the mechanical max is
     * intentionally well past where we think Android's LMK starts killing the
     * app — see [BUFFER_RECOMMENDED_MAX_CHUNKS] for the conservative tick.
     *
     * Issue #84 tracks the empirical work to find the real LMK threshold on
     * Tab A7 Lite (Helio P22T, 3 GB). Treat values past the recommended max
     * as exploratory; we want telemetry from users running there.
     */
    val playbackBufferChunks: Int = BUFFER_DEFAULT_CHUNKS,
    /**
     * Issue #98 — Mode A. When true (default), the UI shows a "warming up"
     * spinner + freezes wall-time scrubber interpolation while the voice
     * engine is loading + producing the first sentence's audio. When false,
     * the UI behaves as if playback started immediately; the listener hears
     * silence until the engine catches up but the scrubber and play button
     * look "playing" the whole time. Useful for users who'd rather see motion
     * than a spinner on slower devices.
     */
    val warmupWait: Boolean = true,
    /**
     * Issue #98 — Mode B. When true (default), the streaming pipeline pauses
     * AudioTrack on mid-stream underrun (PR #77's pause-buffer-resume) and
     * surfaces a "Buffering..." spinner while the producer catches up. When
     * false, the consumer thread drains through underruns; the listener
     * hears moments of dead air instead of paused-then-resumed playback,
     * but never sees the buffering spinner.
     */
    val catchupPause: Boolean = true,
    /** Memory Palace daemon config (#79). Empty host = source disabled. */
    val palace: UiPalaceConfig = UiPalaceConfig(),
)

/**
 * UI projection of the MemPalace daemon connection config. The
 * Settings screen shows two fields (host, optional API key) and the
 * "test connection" button feeds [`SettingsRepositoryUi.testPalace`].
 *
 * The api key is shown masked in the field; reading it back hands the
 * unmasked value to the UI so it can pre-populate the password field
 * on a re-edit. This keeps the UX honest — the user typed the secret,
 * they can read it back.
 */
data class UiPalaceConfig(
    val host: String = "",
    val apiKey: String = "",
) {
    val isConfigured: Boolean get() = host.isNotBlank()
}

/** Default queue depth — current hardcoded `EngineStreamingSource(queueCapacity = 8)`. */
const val BUFFER_DEFAULT_CHUNKS: Int = 8

/** Lower bound — 1 in flight + 1 queued is the minimum that gives any back-pressure benefit. */
const val BUFFER_MIN_CHUNKS: Int = 2

/**
 * Conservative tick where the slider color flips amber. Below this we believe
 * the queue is safe on a 3 GB device. Past this, copy intensifies + slider
 * track turns amber → red as the user enters experimental territory. Picked
 * to give Piper-high ≈ 160 s of headroom (≈ 64 chunks × 2.5 s/sentence ≈ 7 MB
 * of PCM); refine as the LMK probe data arrives.
 */
const val BUFFER_RECOMMENDED_MAX_CHUNKS: Int = 64

/**
 * Mechanical upper bound. The LinkedBlockingQueue can hold this many; whether
 * the heap survives is JP's experimental question. 1500 chunks ≈ 165 MB of
 * PCM at 22050 Hz mono — comfortably past the worst-case LMK guess for a 3 GB
 * Helio P22T.
 */
const val BUFFER_MAX_CHUNKS: Int = 1500

/** Slider color shifts to red (intensified warning) past this multiple of the recommended max. */
const val BUFFER_DANGER_MULTIPLIER: Int = 4

/**
 * Inter-sentence pause multiplier bounds (issue #109).
 *
 * The base pause table lives in `EngineStreamingSource.trailingPauseMs(...)`
 * — `.`/`?`/`!` get 350 ms, `;`/`:` get 200 ms, `,`/dashes get 120 ms,
 * fallback 60 ms. Storyvox scales every output by the multiplier the user
 * sets in Settings → Performance & buffering.
 *
 * Issue #109 widened the original 3-stop selector (Off=0×, Normal=1×,
 * Long=1.75× under #93) into a continuous slider. The ceiling is now 4×
 * to match the engine's existing internal coerceIn(0f, 4f) clamp — past
 * that the engine truncates anyway. Tick marks at 0×/1×/1.75×/4× anchor
 * the historical stops + the new max.
 *
 * The legacy enum stops are exposed as constants so the slider can render
 * "Off" / "Normal" / "Long" tick labels and the migration code in
 * [SettingsRepositoryUi]'s impl can map old enum names → multiplier.
 */
const val PUNCTUATION_PAUSE_MIN_MULTIPLIER: Float = 0f
const val PUNCTUATION_PAUSE_MAX_MULTIPLIER: Float = 4f
const val PUNCTUATION_PAUSE_DEFAULT_MULTIPLIER: Float = 1f

/** Legacy enum stop multipliers — used by the migration shim and tick labels. */
const val PUNCTUATION_PAUSE_OFF_MULTIPLIER: Float = 0f
const val PUNCTUATION_PAUSE_NORMAL_MULTIPLIER: Float = 1f
const val PUNCTUATION_PAUSE_LONG_MULTIPLIER: Float = 1.75f

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
    /**
     * Issue #109 — set the inter-sentence pause multiplier (continuous,
     * coerced to [PUNCTUATION_PAUSE_MIN_MULTIPLIER]..[PUNCTUATION_PAUSE_MAX_MULTIPLIER]).
     * Replaces the pre-#109 `setPunctuationPause(mode: PunctuationPause)`.
     */
    suspend fun setPunctuationPauseMultiplier(multiplier: Float)
    suspend fun setPlaybackBufferChunks(chunks: Int)
    /** Issue #98 — Mode A toggle. See [UiSettings.warmupWait]. */
    suspend fun setWarmupWait(enabled: Boolean)
    /** Issue #98 — Mode B toggle. See [UiSettings.catchupPause]. */
    suspend fun setCatchupPause(enabled: Boolean)
    suspend fun signIn()
    suspend fun signOut()
    /** Memory Palace daemon mutators (#79). */
    suspend fun setPalaceHost(host: String)
    suspend fun setPalaceApiKey(apiKey: String)
    suspend fun clearPalaceConfig()
    /**
     * One-shot reachability probe against the configured daemon.
     * Returns the daemon version on success, an error message on failure.
     */
    suspend fun testPalaceConnection(): PalaceProbeResult
}

/** Outcome of [`SettingsRepositoryUi.testPalaceConnection`]. */
sealed class PalaceProbeResult {
    data class Reachable(val daemonVersion: String) : PalaceProbeResult()
    data class Unreachable(val message: String) : PalaceProbeResult()
    object NotConfigured : PalaceProbeResult()
}
