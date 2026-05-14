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
    /** Issue #211 — the FictionSource this fiction came from. Drives
     *  the "Follow on Royal Road" affordance on FictionDetail; non-RR
     *  sources don't have a source-side follow concept. Defaulted to
     *  empty so existing construction sites stay compatible. */
    val sourceId: String = "",
    /** Issue #211 — true when storyvox has pushed a follow to the
     *  source's account (via [FictionRepositoryUi.setFollowedRemote])
     *  OR when the periodic /my/follows pull observed the user
     *  follows this fiction on RR. Drives the Follow-button label
     *  (Follow / Following). */
    val isFollowedRemote: Boolean = false,
    /** Issue #382 — generalized from #211's hardcoded RR check.
     *  True when the originating FictionSource exposes a follow
     *  action via `FictionSource.supportsFollow`. Drives the
     *  Follow-button *visibility* on FictionDetail; absent / false
     *  means the button is hidden regardless of sign-in state. */
    val sourceSupportsFollow: Boolean = false,
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

/**
 * Issue #246 — one curated RSS feed suggestion. Surfaces in
 * Settings → Library & Sync → RSS → Suggested feeds with a one-tap
 * Add button. Fetched from the jphein/storyvox-feeds GitHub repo at
 * runtime so categories + feeds can be added without an app rebuild.
 */
data class SuggestedFeed(
    val title: String,
    val description: String,
    val url: String,
    val category: String,
    val kind: SuggestedFeedKind,
)

enum class SuggestedFeedKind {
    /** Text-article feed — narrates well via TTS. */
    Text,

    /** Audio-podcast feed — show-notes narrate, audio enclosure
     *  doesn't (storyvox doesn't currently stream feed audio). */
    AudioPodcast,
}

/** Outcome of a source-side follow toggle (#211). */
sealed class SetFollowedRemoteResult {
    /** Push succeeded; storyvox's local copy already updated. */
    data object Success : SetFollowedRemoteResult()
    /** Source rejected without a session — caller should route the
     *  user to sign-in (Royal Road today). */
    data object AuthRequired : SetFollowedRemoteResult()
    /** Anything else — `message` is user-facing copy. */
    data class Error(val message: String) : SetFollowedRemoteResult()
}

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
    /**
     * Issue #212 — fetch the plain-text body of a downloaded chapter
     * for AI grounding. Returns null if the chapter row doesn't exist
     * or its body hasn't been downloaded yet. Suspends because the
     * underlying DAO read is suspending; callers should treat this
     * as a one-shot, not a stream.
     */
    suspend fun chapterTextById(chapterId: String): String?
    suspend fun setDownloadMode(fictionId: String, mode: DownloadMode)
    suspend fun follow(fictionId: String, follow: Boolean)
    /**
     * Issue #211 — push a follow/unfollow to the *source* (Royal Road's
     * account, not storyvox's local library). The source layer
     * handles auth: returns silently on AuthRequired so callers can
     * pre-check sign-in and route to the appropriate screen instead
     * of swallowing the no-op. Distinct from [follow], which is local
     * library Add/Remove.
     */
    suspend fun setFollowedRemote(fictionId: String, followed: Boolean): SetFollowedRemoteResult
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

    /**
     * Genres / tags / wings supported by [sourceId]. MemPalace returns
     * its top-level wing names; Royal Road returns the curated tag list;
     * GitHub returns an empty list (no genre concept). Used by
     * BrowseViewModel to populate the MemPalace wing filter chips.
     */
    suspend fun genres(sourceId: String): List<String>
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

    /**
     * MemPalace wing-scoped listing (#191). Routes through
     * `FictionRepository.browseByGenre(genre)`, which on MemPalace's
     * source resolves to `MemPalaceSource.byGenre(wing)` — top rooms
     * inside the wing by drawer count.
     */
    data class ByGenre(val genre: String) : BrowseSource

    /**
     * Auth-gated `/user/repos` listing for the signed-in GitHub user
     * (#200). Only meaningful when `BrowseSourceKey.GitHub` is selected
     * AND `UiSettings.github` is signed-in; the BrowseViewModel gates
     * the tab visibility, the adapter routes to `GitHubAuthedSource`.
     */
    data object GitHubMyRepos : BrowseSource

    /**
     * Auth-gated `/user/starred?sort=updated` listing for the signed-in
     * GitHub user (#201). Filtered to fiction-shaped repos client-side.
     * Same gating as [GitHubMyRepos] — Browse tab visibility is driven
     * by `UiSettings.github`; the adapter routes to `GitHubAuthedSource`.
     */
    data object GitHubStarred : BrowseSource

    /**
     * Gists for the signed-in GitHub user (#202). Routes through
     * `GET /gists` (authenticated) so secret gists show up alongside
     * public ones; the `:source-github` source layer handles the
     * empty/anonymous case by surfacing an empty page rather than
     * erroring out.
     *
     * The repository adapter routes this onto
     * `GitHubAuthedSource.authenticatedUserGists(page)`. There's no
     * Royal Road or MemPalace analogue — the BrowseSource contract
     * has source-specific variants on purpose (`Filtered` vs
     * `FilteredGitHub`, `GitHubMyRepos` vs `GitHubStarred` vs
     * `GitHubGists`).
     */
    data object GitHubGists : BrowseSource
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
    /** Topics to require (#205). Each maps to a `topic:X` qualifier;
     *  multiple topics AND together (GitHub's default for repeated
     *  topic qualifiers). Empty set omits the qualifier entirely. */
    val tags: Set<String> = emptySet(),
    /** Repository archive state (#205). Default `Any` omits the
     *  qualifier. `ActiveOnly` adds `archived:false`; `ArchivedOnly`
     *  adds `archived:true`. */
    val archivedStatus: GitHubArchivedStatus = GitHubArchivedStatus.Any,
    /** Repository visibility (#204). `Both` (default) omits the qualifier
     *  and matches GitHub's API default. `PublicOnly` adds `is:public`;
     *  `PrivateOnly` adds `is:private` and is only meaningful when the
     *  user has granted the `repo` OAuth scope — the GitHubFilterSheet
     *  hides the chip row otherwise so visibility stays at `Both`. */
    val visibility: GitHubVisibilityFilter = GitHubVisibilityFilter.Both,
)

/** GitHub `pushed:>YYYY-MM-DD` cutoffs. `Any` omits the qualifier. */
enum class GitHubPushedSince { Any, Last7Days, Last30Days, Last90Days, LastYear }

/** GitHub `sort=` axis. `BestMatch` is the API default and omits the
 *  `sort=` parameter; the others map directly to GitHub's values. */
enum class GitHubSort { BestMatch, Stars, Updated }

/** GitHub `archived:` qualifier (#205). `Any` omits the qualifier and
 *  returns both active and archived repos (the GitHub default). */
enum class GitHubArchivedStatus { Any, ActiveOnly, ArchivedOnly }

/** GitHub `is:public` / `is:private` qualifier (#204). `Both` omits the
 *  qualifier — matches GitHub's default behaviour for callers without
 *  the `repo` scope (only public repos are visible anyway).
 *  `PrivateOnly` only returns matches when the user has authorized
 *  `repo`; otherwise GitHub silently returns nothing. */
enum class GitHubVisibilityFilter { Both, PublicOnly, PrivateOnly }

/**
 * MemPalace-shaped filter for Browse → Palace (#191). Single dimension
 * today: which wing of the palace to scope the listing to. Null wing
 * means "all wings" — Browse falls back to Popular/NewReleases tabs as
 * before. Lives in `:feature/api` so the wing dropdown sheet and the
 * ViewModel can hold it without taking a dep on `:source-mempalace`.
 */
data class MemPalaceFilter(
    /** Wing name as returned by `MemPalaceSource.genres()`. Null = no
     *  wing filter; Browse renders the unscoped Popular tab. */
    val wing: String? = null,
)

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
    /**
     * Issue #373 — true when the currently-loaded chapter routes
     * through Media3 / ExoPlayer (audio-stream backend: KVMR community
     * radio, future LibriVox MP3, etc.) rather than the TTS pipeline.
     * Gates the pitch slider in [AudiobookView] — Sonic pitch-shifting
     * applies to engine-rendered PCM and has no equivalent on a live
     * stream the player can't decode-re-encode in real time. UI hides
     * the slider when this flag is true.
     */
    val isLiveAudioChapter: Boolean = false,
)

/** Mirrors core-playback's SleepTimerMode without leaking the playback module to feature. */
sealed class UiSleepTimerMode {
    data class Duration(val minutes: Int) : UiSleepTimerMode()
    data object EndOfChapter : UiSleepTimerMode()
}

/** Issue #189 — feature-side mirror of core-playback's RecapPlaybackState.
 *  Idle = no recap utterance in flight. Speaking = recap audio is playing
 *  through the dedicated AudioTrack. The Reader UI's "Read aloud" button
 *  toggles between the two states. */
enum class UiRecapPlaybackState { Idle, Speaking }

interface PlaybackControllerUi {
    val state: Flow<UiPlaybackState>
    val chapterText: Flow<String>
    /** Issue #189 — recap-aloud TTS pipeline state. See [UiRecapPlaybackState]. */
    val recapPlayback: Flow<UiRecapPlaybackState>
    /**
     * Calliope (v0.5.00) — one-shot UI signals from the player.
     * Used today by the milestone celebration to detect the first
     * natural chapter completion ([`in`.jphe.storyvox.playback.PlaybackUiEvent.ChapterDone])
     * so the confetti overlay can fire once. Default emits nothing,
     * so test fakes that don't care about player events stay slim.
     *
     * This is the long-promised UI seam for the events SharedFlow on
     * the core-playback PlaybackController — pre-Calliope, the
     * `BookFinished` / `ChapterChanged` / `EngineMissing` /
     * `AzureFellBack` events still flowed inside core-playback but
     * never reached the feature layer because there was no UI-side
     * subscription path. New consumers should observe through this
     * flow; the existing debug-overlay path goes through
     * `PlaybackController.events` directly from `:app` and keeps
     * working.
     */
    val events: Flow<`in`.jphe.storyvox.playback.PlaybackUiEvent>
        get() = kotlinx.coroutines.flow.emptyFlow()
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    /** Seek by char offset into chapter text (used by tap-on-sentence). */
    fun seekToChar(charOffset: Int)
    fun skipForward()
    fun skipBack()
    /** #120 — step to the next sentence boundary. No-op at chapter end. */
    fun nextSentence()
    /** #120 — step to the previous sentence boundary. No-op at sentence 0. */
    fun previousSentence()
    fun nextChapter()
    fun previousChapter()
    fun setSpeed(speed: Float)
    fun setPitch(pitch: Float)
    /**
     * Scale the inter-sentence punctuation pause (issue #90). 0f disables
     * trailing silence entirely; 1f is the audiobook-tuned default; >1f
     * lengthens. Applied to the next sentence the producer generates —
     * the live pipeline rebuilds so the change takes effect on the next
     * sentence boundary, mirroring [setSpeed]/[setPitch].
     */
    fun setPunctuationPauseMultiplier(multiplier: Float)
    /**
     * @param autoPlay when true (default), playback starts immediately
     *   after chapter+voice load completes. When false (#90 smart-resume
     *   path), the chapter loads + navigation fires but the engine
     *   remains paused — user has to tap play. Library's Resume CTA
     *   passes `lastWasPlaying` here so an explicit pre-pause is
     *   respected on next resume.
     */
    fun startListening(
        fictionId: String,
        chapterId: String,
        charOffset: Int = 0,
        autoPlay: Boolean = true,
    )
    fun startSleepTimer(mode: UiSleepTimerMode)
    fun cancelSleepTimer()

    /** Issue #189 — synthesize and play [text] as a one-shot utterance via
     *  the active voice. The caller (ReaderViewModel.toggleRecapAloud) is
     *  responsible for pausing fiction playback before calling — see the
     *  PlaybackController interface for the full contract. */
    suspend fun speakText(text: String)

    /** Issue #189 — cancel an in-flight recap-aloud utterance. Idempotent. */
    fun stopSpeaking()

    /**
     * Issue #121 — bookmark the current playback position. No-op when
     * no chapter is loaded; otherwise persists the current char offset
     * as that chapter's single bookmark, replacing any previous bookmark.
     */
    fun bookmarkHere()

    /** Issue #121 — clear the currently-loaded chapter's bookmark. No-op
     *  when nothing's loaded or no bookmark exists. */
    fun clearBookmark()

    /**
     * Issue #121 — seek to the currently-loaded chapter's bookmark
     * position, if any. Backed by a callback rather than a Flow because
     * "is there a bookmark?" is a one-shot question the menu asks when
     * it opens; observing every change would force the player options
     * sheet to re-collect on every chapter change.
     */
    fun jumpToBookmark()
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

/** Mirror of [`in`.jphe.storyvox.llm.ProviderId] for the feature
 *  module — keeps `:feature` from depending on `:core-llm` directly.
 *  The Settings impl converts between the two. Stays in lockstep
 *  with the canonical enum (PR-1 ships with the same set + order). */
enum class UiLlmProvider {
    Claude, OpenAi, Ollama, Bedrock, Vertex, Foundry, Teams;

    /** Whether this provider has a real implementation.
     *  Matches `ProviderId.implemented`. */
    val implemented: Boolean
        get() = this == Claude || this == OpenAi || this == Ollama ||
            this == Vertex || this == Foundry || this == Bedrock

    val displayName: String
        get() = when (this) {
            Claude -> "Claude (Anthropic, BYOK)"
            OpenAi -> "OpenAI (BYOK)"
            Ollama -> "Ollama (local LAN)"
            Bedrock -> "AWS Bedrock"
            Vertex -> "Google Vertex AI"
            Foundry -> "Azure AI Foundry"
            Teams -> "Anthropic Teams (OAuth)"
        }
}

/** UI-facing AI config — a flattened bundle the Settings screen
 *  reads. Distinct from the wire-layer LlmConfig in :core-llm so
 *  `:feature` doesn't take a direct dependency on the LLM module. */
data class UiAiSettings(
    /** null = AI disabled. */
    val provider: UiLlmProvider? = null,
    val claudeModel: String = "claude-haiku-4.5",
    val claudeKeyConfigured: Boolean = false,
    val openAiModel: String = "gpt-4o-mini",
    val openAiKeyConfigured: Boolean = false,
    val ollamaBaseUrl: String = "http://10.0.0.1:11434",
    val ollamaModel: String = "llama3.3",
    val vertexModel: String = "gemini-2.5-flash",
    val vertexKeyConfigured: Boolean = false,
    /**
     * Issue #219 — alternative Vertex auth via uploaded service-account
     * JSON. Mutually exclusive with [vertexKeyConfigured] at the
     * repository level (setting either side clears the other). The UI
     * surfaces a single "Vertex auth" section with both rows visible
     * so the user can switch between modes without digging.
     *
     * Whether the SA was rejected by Google IAM (revoked, missing
     * roles, etc.) is surfaced separately via the probe path —
     * "configured" here only means a JSON blob is on disk.
     */
    val vertexServiceAccountConfigured: Boolean = false,
    /** SA's `client_email` if one is uploaded; shown read-only as a
     *  reminder of *which* SA is wired. Never persists outside the
     *  encrypted-prefs blob — this field is derived on each read. */
    val vertexServiceAccountEmail: String? = null,
    /** Azure Foundry endpoint URL the user pasted (e.g.
     *  `https://my-resource.openai.azure.com`). Empty = not set. */
    val foundryEndpoint: String = "",
    /** Deployment name (deployed mode) or catalog model id
     *  (serverless mode). The same field is reused across both modes
     *  because the URL builder reads it differently per mode. */
    val foundryDeployment: String = "",
    /** True selects Azure's serverless `/models/...` URL shape;
     *  false selects the per-model `/openai/deployments/{name}/...`
     *  shape (default — matches "Azure OpenAI Service" deployments). */
    val foundryServerless: Boolean = false,
    val foundryKeyConfigured: Boolean = false,
    /** AWS Bedrock — both keys are encrypted; UI only sees the
     *  "configured" booleans. Region + model are non-secret and round-trip
     *  through DataStore. */
    val bedrockAccessKeyConfigured: Boolean = false,
    val bedrockSecretKeyConfigured: Boolean = false,
    val bedrockRegion: String = "us-east-1",
    val bedrockModel: String = "claude-haiku-4.5",
    /**
     * Anthropic Teams (OAuth) session state (#181). The bearer token
     * itself never crosses into the UI — Settings only needs to know
     * whether the user has signed in and (optionally) which scopes
     * the workspace granted. Token refresh is handled inside the
     * provider; the UI flips to "not signed in" only when the refresh
     * token also gets revoked (mid-session expiry rotates the bearer
     * silently). */
    val teamsSignedIn: Boolean = false,
    val teamsScopes: String = "",
    val privacyAcknowledged: Boolean = false,
    val sendChapterTextEnabled: Boolean = true,
    /**
     * Issue #212 — what the chat ViewModel injects into its system
     * prompt to ground replies in what the user is currently reading.
     * The fiction title is always sent (no toggle); the four fields
     * here are the per-grounding-level opt-ins.
     *
     * Token cost ramps fast on the bottom two: "entire chapter" is
     * a few thousand tokens per turn; "entire book so far" can hit
     * 50k+ tokens on long fictions, which is fine on Claude / OpenAI
     * but blows past Ollama's default 8k context. The Settings UI
     * surfaces a per-toggle estimate so users understand the cost.
     */
    val chatGrounding: UiChatGrounding = UiChatGrounding(),
    /**
     * Issue #217 — "Carry memory across fictions" toggle. When ON,
     * each AI chat turn pulls cross-fiction memory entries matching
     * names in the user's message and appends them to the system
     * prompt under a "Cross-fiction context" section. Token budget
     * is capped at ~500 tokens (oldest entries dropped first), so
     * the worst-case cost is bounded.
     *
     * Default ON for fresh installs — JP's call in #217's decisions
     * list: more useful by default, especially for users with deep
     * libraries who'd hit duplicate-name disambiguation immediately.
     * Existing installs upgrading from pre-#217 also get ON because
     * the DataStore default kicks in for missing keys.
     */
    val carryMemoryAcrossFictions: Boolean = true,
)

/**
 * Per-toggle grounding settings for the Q&A chat ViewModel
 * ([in.jphe.storyvox.feature.chat.ChatViewModel.buildSystemPrompt]).
 * Issue #212.
 *
 * Defaults match the pre-#212 behaviour: only the chapter title is
 * included (when the user is actively listening to the same fiction);
 * everything more expensive is opt-in.
 */
data class UiChatGrounding(
    /** Pre-#212 default behaviour. Cheap (a few words). */
    val includeChapterTitle: Boolean = true,
    /** ~50 tokens. The exact sentence the listener is on right now. */
    val includeCurrentSentence: Boolean = false,
    /** ~2-5k tokens depending on chapter length. */
    val includeEntireChapter: Boolean = false,
    /** Chapter 1 → current sentence. 50k+ tokens on long fictions. */
    val includeEntireBookSoFar: Boolean = false,
)

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
    /**
     * Issue #193 — VoxSherpa-TTS v2.7.13 — Sonic pitch-interpolation
     * quality. Default true (quality=1) for smoother pitch-shifted
     * output. Costs ~20% extra CPU per pitch-shifted chunk, which is
     * fine for storyvox because chapter PCM is pre-rendered and
     * cached (post-#97). Users on slow hardware can flip this off to
     * fall back to Sonic's upstream default (quality=0, faster but
     * audibly grittier at non-neutral pitch).
     */
    val pitchInterpolationHighQuality: Boolean = true,
    val sigil: UiSigil = UiSigil.UNKNOWN,
    val ai: UiAiSettings = UiAiSettings(),
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
    /** Default flipped to false on 2026-05-09 — JP's directive
     *  "all performance & buffering toggles default off." Existing
     *  users keep their persisted preference; only fresh installs
     *  see the new default. */
    val warmupWait: Boolean = false,
    /**
     * Issue #98 — Mode B. When true (default), the streaming pipeline pauses
     * AudioTrack on mid-stream underrun (PR #77's pause-buffer-resume) and
     * surfaces a "Buffering..." spinner while the producer catches up. When
     * false, the consumer thread drains through underruns; the listener
     * hears moments of dead air instead of paused-then-resumed playback,
     * but never sees the buffering spinner.
     */
    /** Default flipped to false on 2026-05-09 then back to true the
     *  same evening — JP reported audible inter-chunk gaps with
     *  buffer=3000 immediately after the off-default landed. Mode B
     *  is what handles "buffer underrun → pause AudioTrack → wait
     *  for refill → resume" smoothing; turning it off means every
     *  generation-vs-playback miss surfaces as a gap. Keeping it
     *  default-on now; Mode A (warmupWait) stays default-off as a
     *  separate UX call. */
    val catchupPause: Boolean = true,
    /**
     * Issue #85 — Voice-Determinism preset for the VoxSherpa engine. When
     * true (default), the engine runs with VoxSherpa's calmed VITS defaults
     * (`noise_scale = 0.35`, `noise_scale_w = 0.667`); identical text
     * re-renders sound nearly identical, best for audiobook listeners
     * replaying chapters. When false, the engine runs with sherpa-onnx
     * upstream's Piper defaults (`0.667` / `0.8`); slightly more variable
     * prosody and fuller delivery, closer to vanilla Piper.
     *
     * Toggling forces a model reload — ~1-3s on Piper, ~30s on Kokoro
     * (though Kokoro ignores noise_scale and the setter is a cheap no-op
     * there). The reload is handled in `EnginePlayer` via VoxSherpa's
     * `VoiceEngine.setNoiseScale()` / `setNoiseScaleW()` setters
     * (introduced in `VoxSherpa-TTS` v2.7.4).
     */
    val voiceSteady: Boolean = true,
    /** Memory Palace daemon config (#79). Empty host = source disabled. */
    val palace: UiPalaceConfig = UiPalaceConfig(),
    /**
     * GitHub OAuth session surface (#91). Drives the Sources → GitHub
     * row in Settings. The token itself is never exposed to the UI —
     * only the login + state. See [UiGitHubAuthState].
     */
    val github: UiGitHubAuthState = UiGitHubAuthState.Anonymous,
    /**
     * Issue #203 — when true, the next GitHub Device Flow run requests
     * the `repo` scope (full repo, read/write, includes private). When
     * false (default), Device Flow uses [`GitHubAuthConfig.DEFAULT_SCOPES`]
     * (`read:user public_repo`). Toggling this on a signed-in account
     * doesn't auto-upgrade the live token — the user has to re-run
     * sign-in for the new scope to take effect; the existing session
     * keeps its original scopes until then.
     */
    val githubPrivateReposEnabled: Boolean = false,
    /**
     * Per-source on/off toggles (#221). When false, the corresponding
     * source is hidden from `BrowseSourcePicker` and any catalog/poll
     * call short-circuits. Default true — every source ships enabled.
     * Library and Follows aren't affected; the toggles only gate Browse
     * surface area.
     */
    /** Per-backend defaults flipped to RSS-only on 2026-05-09 — fresh
     *  installs land with just the RSS chip in Browse so the
     *  "anything that publishes a feed" surface is the introduction
     *  to storyvox. The other backends stay one toggle away in
     *  Settings → Library & Sync. The Royal Road / GitHub / Memory
     *  Palace defaults flipped to false here matter only for
     *  *first-launch* state; existing users keep their persisted
     *  prefs untouched. */
    val sourceRoyalRoadEnabled: Boolean = false,
    val sourceGitHubEnabled: Boolean = false,
    val sourceMemPalaceEnabled: Boolean = false,
    val sourceRssEnabled: Boolean = true,
    val sourceEpubEnabled: Boolean = false,
    /** Outline self-hosted-wiki backend (#245). Default off per the
     *  RSS-only first-launch story; user opts in from Settings →
     *  Library & Sync. */
    val sourceOutlineEnabled: Boolean = false,
    /** Project Gutenberg backend (#237). Default ON for fresh installs
     *  — PG is the most-legally-clean source in the roster (public
     *  domain by definition, no ToS surface), so it carries no risk
     *  in the first-launch picker. The 70,000+ catalog also pairs
     *  with the existing EPUB infrastructure (#235), so opting in is
     *  free in terms of new dependencies the user has to configure. */
    val sourceGutenbergEnabled: Boolean = true,
    /** Archive of Our Own backend (#381). Default OFF for fresh
     *  installs — AO3 content can be Explicit-rated and we don't
     *  want first-launch users seeing it before opting in. Users
     *  enable from Settings → Library & Sync. Same content
     *  pipeline as Gutenberg (per-work EPUB downloads parsed via
     *  `:source-epub`); discovery is via per-tag Atom feeds rather
     *  than a unified JSON catalog. */
    val sourceAo3Enabled: Boolean = false,
    /** Standard Ebooks backend (#375). Default OFF for fresh installs
     *  — opt-in surface, mirroring Outline/Epub/Gutenberg defaults
     *  policy for the curated picker. Same legal posture as Gutenberg
     *  (CC0 throughout), so users who want polished classics can flip
     *  this on in Settings → Library & Sync alongside Gutenberg. */
    val sourceStandardEbooksEnabled: Boolean = false,
    /** Wikipedia non-fiction backend (#377). Default off for fresh
     *  installs — the first non-fiction-shaped source is a deliberate
     *  opt-in so users discover it from Settings rather than getting
     *  a Browse picker pre-populated with a non-fiction surface they
     *  didn't ask for. Per the RSS-only first-launch story carried
     *  forward from the 2026-05-09 default flip. */
    val sourceWikipediaEnabled: Boolean = false,
    /** Wikipedia language code (#377) — `en`, `de`, `ja`, `simple`, etc.
     *  Selects which Wikipedia host the source talks to:
     *  `<lang>.wikipedia.org`. Default `en`. Empty falls back to the
     *  default so a malformed prefs value doesn't brick the source. */
    val wikipediaLanguageCode: String = "en",
    /** Wikisource backend (#376). Default off for fresh installs —
     *  opt-in alongside the other text backends. Wikisource is the
     *  Wikimedia project for transcribed public-domain texts; same
     *  legal posture as Project Gutenberg / Standard Ebooks (CC0 /
     *  public-domain throughout) but with a transcription pipeline
     *  that's been proofread by Wikimedia volunteers. v1 is en-only
     *  (`en.wikisource.org`); a per-language picker is a follow-up
     *  that mirrors Wikipedia's `wikipediaLanguageCode`. */
    val sourceWikisourceEnabled: Boolean = false,
    /** KVMR community radio backend (#374, closes #373 first piece).
     *  Default ON — KVMR is JP's local station, the inaugural audio-
     *  stream backend, and there's nothing controversial about
     *  community-radio content surfacing in the picker. Audio sources
     *  carry a distinct legal posture from text backends (publicly
     *  documented stream URL, explicit third-party listening
     *  intent); enabling by default makes the new pipeline
     *  discoverable without forcing every fresh-install user to opt in
     *  from Settings before they see what the audio pipeline does. */
    val sourceKvmrEnabled: Boolean = true,
    /** Notion fiction backend (#233). Default ON for fresh installs
     *  per #390 — the bundled database id points at the techempower.org
     *  content database, so a fresh install on a colleague's phone
     *  surfaces TechEmpower content immediately as narratable audio
     *  (after they paste an integration token). The pipeline is gated
     *  by token presence at runtime; defaulting the toggle ON makes
     *  the source visible in Browse so the empty-state can teach the
     *  user about the one-paste configuration step. */
    val sourceNotionEnabled: Boolean = true,
    /** Hacker News backend (#379). Default OFF — tech-news / discussion
     *  is a distinct interest from fiction backends, so the picker
     *  shouldn't surface it on every fresh install. Users opt in from
     *  Settings → Library & Sync. */
    val sourceHackerNewsEnabled: Boolean = false,
    /** arXiv non-fiction backend (#378). Default OFF for fresh
     *  installs — second non-fiction-shaped source after Wikipedia,
     *  same opt-in posture. Users discover it from Settings →
     *  Library & Sync rather than getting a Browse picker pre-
     *  populated with academic-paper content they didn't ask for. */
    val sourceArxivEnabled: Boolean = false,
    /** PLOS open-access peer-reviewed science backend (#380). Default
     *  OFF on fresh installs — academic content is an opt-in surface;
     *  not what a fresh-install user expects in the picker until they
     *  go looking for it. Same opt-in posture as Wikipedia. */
    val sourcePlosEnabled: Boolean = false,
    /** Discord backend (#403). Default OFF on fresh installs — bot-token
     *  onboarding is high-friction and Discord is a private workspace,
     *  not a public catalog. Users flip this on after creating a Discord
     *  application + inviting their bot to the target server. */
    val sourceDiscordEnabled: Boolean = false,
    /** True when a Discord bot token has been stored. The token itself
     *  is never surfaced to the UI — only this boolean. */
    val discordTokenConfigured: Boolean = false,
    /** Selected Discord guild (server) id. Empty until the user picks
     *  one from the populated server picker (which only appears after
     *  the bot token is configured and the `users/@me/guilds` lookup
     *  succeeds). */
    val discordServerId: String = "",
    /** Human-readable Discord server name, captured at picker time so
     *  empty-state copy can name the server without an extra
     *  `users/@me/guilds` round-trip. */
    val discordServerName: String = "",
    /** Issue #403 — same-author coalesce window in minutes. Within
     *  this window, consecutive messages from the same author collapse
     *  into one chapter. Default 5 min; slider range 1-30. */
    val discordCoalesceMinutes: Int = 5,
    /**
     * Plugin-seam Phase 1 (#384) — per-plugin on/off keyed by stable
     * plugin id ("kvmr", "royalroad", "notion", ...). Replaces the
     * hand-rolled `sourceXxxEnabled` flags above as backends migrate
     * to the `@SourcePlugin` annotation. Phase 1 ships with only
     * `:source-kvmr` migrated, so this map currently mirrors
     * [sourceKvmrEnabled] for the `kvmr` key; other ids are populated
     * by the one-time migration shim in the settings impl that reads
     * the legacy per-source preferences on first launch and writes
     * them into the JSON map.
     *
     * Consumers in Phase 1 should keep reading the per-backend
     * `sourceXxxEnabled` fields; this map is for Phase 2 callers that
     * iterate the registry. The two views stay in sync via the
     * settings impl's setters (each per-backend setter also updates
     * the map and vice-versa).
     */
    val sourcePluginsEnabled: Map<String, Boolean> = emptyMap(),
    /** Notion database id (#233 + #390). Defaults to the baked-in
     *  techempower.org placeholder from `NotionDefaults`; existing
     *  users with a different stored value keep it. Notion accepts
     *  both hyphenated UUID and compact 32-hex forms. Used only in
     *  [notionMode] = `OFFICIAL_PAT`. */
    val notionDatabaseId: String = "",
    /** True when a Notion Internal Integration Token has been stored.
     *  The token itself is never surfaced to the UI — only this
     *  boolean. Empty token = anonymous-mode reader (#393). */
    val notionTokenConfigured: Boolean = false,
    /** Issue #393 — read-path selector. Anonymous-mode (default for
     *  fresh installs and the token-less case) reads the public
     *  techempower.org Notion tree via `www.notion.so/api/v3` without
     *  setup; PAT-mode (set automatically when the user pastes a
     *  token) reads a private workspace database via the official
     *  Notion REST API. Carried as a string here to keep
     *  `:feature-settings` independent of `:source-notion`. Values:
     *  `"ANONYMOUS_PUBLIC"` or `"OFFICIAL_PAT"`. */
    val notionMode: String = "ANONYMOUS_PUBLIC",
    /** Issue #393 — anonymous-mode root page id. The public Notion
     *  page storyvox walks for fictions. Defaults to TechEmpower's
     *  root; users can override to any public Notion page. */
    val notionRootPageId: String = "",
    /** Issue #150 — when ON, a shake during the sleep timer's fade
     *  tail re-arms the timer. Default ON; users with bumpy commutes
     *  can disable to avoid accidental extensions. */
    val sleepShakeToExtendEnabled: Boolean = true,
    /**
     * Issue #195 — per-voice speed/pitch tweaks. Different voices have
     * different "natural pitch centers" and pacing; storing one global
     * value applies the previous voice's offset when the user switches.
     * The maps key on `voiceId` (matches [defaultVoiceId]) and override
     * the global [defaultSpeed] / [defaultPitch] when present. Voices
     * not in the map fall back to the global defaults — the migration
     * path preserves pre-#195 behavior.
     */
    val voiceSpeedOverrides: Map<String, Float> = emptyMap(),
    val voicePitchOverrides: Map<String, Float> = emptyMap(),
    /**
     * Issue #197 — per-voice lexicon override map. Keys are voice IDs
     * (same shape as [voiceSpeedOverrides]); values are absolute file
     * paths to user-provided `.lexicon` files (sherpa-onnx-format IPA /
     * X-SAMPA phoneme dictionaries). Empty / missing voiceId = the
     * engine falls back to its built-in lexicon.
     *
     * Multiple lexicons per voice are supported by comma-joining the
     * paths in a single map value (sherpa-onnx
     * `OfflineTts*ModelConfig.setLexicon()` accepts comma-separated
     * paths and merges them in order). The Settings UI today wires
     * just one picker per voice; the map shape is forward-compatible
     * for a multi-file UI later.
     *
     * Storage: encoded as `voiceId=path;voiceId=path` in DataStore,
     * same flat-string codec as [voiceSpeedOverrides]. The `=` and
     * `;` delimiters require the paths NOT to contain those bytes;
     * SAF-resolved paths from `getExternalFilesDir()` and the per-voice
     * `${filesDir}/lexicons/<voiceId>/` directory both satisfy this
     * (Android FS paths use `/` and alphanumerics).
     *
     * The engine reads at construction time via the static
     * [`in.jphe.storyvox.playback.VoiceEngineQualityBridge.applyLexicon`]
     * field write, so a flip requires the next voice load — Settings
     * forces this by re-applying on active-voice change.
     */
    val voiceLexiconOverrides: Map<String, String> = emptyMap(),
    /**
     * Issue #198 — per-voice Kokoro phonemizer language override map.
     * Keys are voice IDs; values are language codes from
     * [KOKORO_PHONEMIZER_LANGS] (`en`, `es`, `fr`, ...). Empty /
     * missing voiceId = the engine uses the voice's native language.
     *
     * Only Kokoro voices honor this override (Piper voices are
     * per-language, no phonemizer-language indirection). Settings UI
     * only surfaces the picker on Kokoro voice rows; the map can
     * contain entries for Piper voiceIds without effect.
     *
     * Storage: encoded as `voiceId=langCode;voiceId=langCode` in
     * DataStore, same codec as [voiceLexiconOverrides].
     */
    val voicePhonemizerLangOverrides: Map<String, String> = emptyMap(),
    /**
     * Azure Speech Services BYOK config (#182). Empty key = source not
     * yet configured; the picker shows Azure rows greyed-out with a
     * "Configure Azure key →" CTA until [UiAzureConfig.isConfigured]
     * flips to true.
     */
    val azure: UiAzureConfig = UiAzureConfig(),
    /**
     * PR-6 (#185) — Azure offline-fallback toggle. When ON and an
     * Azure synthesis fails with a non-auth error (network out,
     * Azure 5xx after retries, throttled-after-retries), storyvox
     * auto-swaps to the user-chosen [azureFallbackVoiceId] for the
     * remainder of the chapter rather than halting playback. The
     * playback sheet emits a one-shot toast on swap. Default OFF;
     * users with a key opt in if they want offline resilience.
     */
    val azureFallbackEnabled: Boolean = false,
    /**
     * PR-6 (#185) — voice id used when [azureFallbackEnabled] fires.
     * Null until the user picks one in Settings → Cloud voices →
     * "Fall back to local voice." If null while the toggle is on,
     * fallback is a no-op (we have no voice to swap to).
     */
    val azureFallbackVoiceId: String? = null,
    /**
     * Tier 3 (#88) — experimental parallel-synth instance count.
     * Range 1..[PARALLEL_SYNTH_MAX_INSTANCES]. Default 1 = serial
     * (no extra memory, original behavior). Higher values spin up
     * additional VoiceEngine / KokoroEngine instances; the producer
     * dispatches sentences round-robin across all instances.
     *
     * Throughput scales roughly linearly with instance count up to
     * the device's CPU core count, after which OS scheduling
     * overhead dominates.
     *
     * Memory cost scales linearly:
     * - Piper-high: ~150 MB per instance
     * - Kokoro Studio: ~325 MB per instance (multi-speaker model)
     *
     * Conservative defaults (range capped at 8) keep even the
     * heaviest configuration (8× Kokoro = ~2.6 GB) within budget on
     * 6 GB+ devices. On 3 GB devices users should stay at 2 or below.
     *
     * Azure ignores this setting — cloud synth is HTTP, no local
     * engine instances to multiply.
     *
     * Migration from pre-#88-slider boolean toggle: old `true` →
     * count 2, old `false` → count 1.
     */
    val parallelSynthInstances: Int = 1,
    /**
     * Tier 3 (#88) companion slider — sherpa-onnx numThreads passed
     * to each engine instance at loadModel time. 0 = "Auto" (use
     * VoxSherpa's getOptimalThreadCount heuristic, which today
     * returns the available core count). 1..8 = explicit override.
     *
     * Why surface this: Snapdragon 888 (Galaxy Z Flip3) throttles
     * after several minutes of sustained inference; pegging all 8
     * cores at numThreads=8 causes thermal degradation that manifests
     * as a producer slowdown after ~5 min of playback. Lowering to
     * numThreads=5 or 6 keeps the chip under the throttle line and
     * sustains realtime synthesis.
     *
     * Total compute = parallelSynthInstances × synthThreadsPerInstance.
     * Both sliders cap at 8; the practical ceiling is the device's
     * core count + thermal envelope.
     */
    val synthThreadsPerInstance: Int = 0,
    /**
     * Vesper (v0.4.97) — debug overlay master switch. When true, the
     * Reader and home shells draw [DebugOverlay] on top of their
     * normal content as a swipe-down-to-collapse card. Wires through
     * to the same DataStore key as every other Boolean toggle. Default
     * `false` — power users opt in from Settings → Developer.
     *
     * The dedicated `/debug` screen is reachable from Settings →
     * Developer regardless of this toggle; the switch only controls
     * the *overlay* surface.
     */
    val showDebugOverlay: Boolean = false,
    /**
     * Issue #383 — per-source Inbox notification toggles. When false,
     * the corresponding backend's update emitter skips writing events
     * to the cross-source Inbox feed (and skips the optional system
     * notification). Default ON across the board — the Inbox tab is
     * opt-out per-source, not opt-in. The toggle only gates the
     * Inbox/notification surface; the source itself stays visible in
     * Browse, and library updates still happen.
     *
     * Only backends that emit events today are surfaced as fields
     * here. Wikipedia / Standard Ebooks / Outline / etc. don't poll
     * for diffs yet (v1 scope) — they're filed as follow-ups; the
     * matching toggle pre-existing on the Inbox section just becomes
     * meaningful when the source starts emitting.
     */
    val inboxNotifyRoyalRoad: Boolean = true,
    val inboxNotifyKvmr: Boolean = true,
    val inboxNotifyWikipedia: Boolean = true,
) {
    /** Speed value the engine should run at right now — the active
     *  voice's override if set, otherwise the global default (#195). */
    val effectiveSpeed: Float
        get() = defaultVoiceId?.let { voiceSpeedOverrides[it] } ?: defaultSpeed

    /** Pitch value the engine should run at right now — the active
     *  voice's override if set, otherwise the global default (#195). */
    val effectivePitch: Float
        get() = defaultVoiceId?.let { voicePitchOverrides[it] } ?: defaultPitch

    /**
     * Lexicon override the engine should use right now (#197). Empty
     * string = no override (engine uses its built-in lexicon). The
     * VoxSherpa bridge reads this on the *next* engine construction,
     * so a flip requires a voice reload to take effect — Settings
     * forces this by re-applying on active-voice change.
     */
    val effectiveLexicon: String
        get() = defaultVoiceId?.let { voiceLexiconOverrides[it] }.orEmpty()

    /**
     * Kokoro phonemizer language override active right now (#198).
     * Empty string = no override (Kokoro uses the voice's native
     * language). Piper voices ignore this entirely.
     */
    val effectivePhonemizerLang: String
        get() = defaultVoiceId?.let { voicePhonemizerLangOverrides[it] }.orEmpty()
}

/**
 * UI projection of the GitHub OAuth session (#91). The Settings row
 * needs to know "are you signed in, who as, do you need to re-auth"
 * — never the token string itself, which stays inside :source-github's
 * `GitHubAuthRepository`.
 */
sealed class UiGitHubAuthState {
    object Anonymous : UiGitHubAuthState()
    data class SignedIn(val login: String?, val scopes: String) : UiGitHubAuthState()
    /**
     * Token at github.com is gone (revoked, rotated). Disk copy intact;
     * settings row shows "Session expired — sign in again" + the same
     * sign-in CTA as [Anonymous].
     */
    object Expired : UiGitHubAuthState()
}

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

/**
 * UI projection of the Azure Speech Services BYOK config (#182).
 * Settings → Cloud Voices → Azure shows three fields: masked API key,
 * region dropdown, and a "Test connection" button. Mirrors
 * [UiPalaceConfig]'s read-back-the-secret shape — the user typed the
 * key, they can read it back to verify it's right.
 *
 * The plaintext copy here lives only in the UI projection; the
 * persisted copy is in `EncryptedSharedPreferences` via
 * `AzureCredentials`. Same trust shape as the GitHub PAT and Memory
 * Palace key.
 */
data class UiAzureConfig(
    val key: String = "",
    /** Persisted region id (e.g. `eastus`). Used verbatim in the
     *  endpoint URL, so it stays as a String — supports the
     *  user-pasted "Other" region beyond the curated dropdown. */
    val regionId: String = "eastus",
    /** Display label for the dropdown, derived from [regionId]. Falls
     *  back to the raw id when no curated entry matches (the "Other"
     *  affordance). */
    val regionDisplayName: String = "US East",
) {
    val isConfigured: Boolean get() = key.isNotBlank()
}

/** Default queue depth.
 *
 *  Issue #294 — bumped from 8 → 12. The original 8 was the bare minimum
 *  from #84's exploratory probe era; ~12 hides voice startup spikes on
 *  Flip3-class hardware without entering the amber zone (recommended
 *  max is 64). Per Lyra's first-time-defaults audit. */
const val BUFFER_DEFAULT_CHUNKS: Int = 12

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
 * the heap survives is JP's experimental question. 3000 chunks ≈ 330 MB of
 * PCM at 22050 Hz mono — way past the worst-case LMK guess for a 3 GB Helio
 * P22T. JP wants this exposed so listeners can probe the kill threshold; the
 * danger-zone color shifts (amber → red past the recommended max) plus the
 * intensified copy past the recommended tick are the user's brake, not the
 * slider's mechanical max.
 */
const val BUFFER_MAX_CHUNKS: Int = 3000

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
/** Issue #294 — bumped from 1.0× → 0.85×. Web fiction has more dialogue
 *  tags and short paragraphs than audiobooks; 1.00× felt stilted on a
 *  Royal Road / RSS reading session. 0.85× still respects terminators
 *  but lets the cadence flow with the source. Per Lyra's first-time-
 *  defaults audit. */
const val PUNCTUATION_PAUSE_DEFAULT_MULTIPLIER: Float = 0.85f

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
    /** Issue #193 — toggle high-quality Sonic pitch interpolation
     *  (quality=1 vs upstream default 0). See
     *  [UiSettings.pitchInterpolationHighQuality]. */
    suspend fun setPitchInterpolationHighQuality(enabled: Boolean)

    /**
     * Issue #197 — set or clear the lexicon override for a specific
     * voice. [path] is an absolute file path (or comma-separated paths)
     * to a `.lexicon` file; null or empty clears the override and the
     * engine falls back to its built-in lexicon.
     *
     * The override takes effect on the next voice load. If [voiceId]
     * matches the currently active voice the impl re-applies the
     * static bridge field immediately so the next chapter render uses
     * the new path; the active engine instance keeps its old lexicon
     * until the next loadModel().
     */
    suspend fun setVoiceLexicon(voiceId: String, path: String?)

    /**
     * Issue #198 — set or clear the Kokoro phonemizer language
     * override for a specific voice. [langCode] should be one of
     * [`KOKORO_PHONEMIZER_LANGS`]; null or empty clears the override
     * and the engine uses the voice's native language.
     *
     * No-op on Piper voices at the engine layer (the static field
     * exists only on KokoroEngine), but the map persists per-voice
     * regardless so the UI can surface and clear values uniformly.
     * Caller is responsible for only writing for Kokoro voices —
     * the Settings UI hides the picker on non-Kokoro rows.
     */
    suspend fun setVoicePhonemizerLang(voiceId: String, langCode: String?)
    suspend fun setPlaybackBufferChunks(chunks: Int)
    /** Issue #98 — Mode A toggle. See [UiSettings.warmupWait]. */
    suspend fun setWarmupWait(enabled: Boolean)
    /** Issue #98 — Mode B toggle. See [UiSettings.catchupPause]. */
    suspend fun setCatchupPause(enabled: Boolean)
    /** Issue #85 — Voice-Determinism preset. See [UiSettings.voiceSteady]. */
    suspend fun setVoiceSteady(enabled: Boolean)
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

    // ── AI settings (issue #81) ────────────────────────────────────
    /** null disables AI. Picking a spec-only provider has no effect
     *  at runtime (the providers throw NotConfigured) but the
     *  Settings UI surfaces them as "coming soon" rows that are
     *  visible but not actually selectable. */
    suspend fun setAiProvider(provider: UiLlmProvider?)
    suspend fun setClaudeApiKey(key: String?)   // null = clear
    suspend fun setClaudeModel(model: String)
    suspend fun setOpenAiApiKey(key: String?)
    suspend fun setOpenAiModel(model: String)
    suspend fun setOllamaBaseUrl(url: String)
    suspend fun setOllamaModel(model: String)
    suspend fun setVertexApiKey(key: String?)   // null = clear
    suspend fun setVertexModel(model: String)
    /**
     * Issue #219 — install a Google service-account JSON for Vertex
     * auth. The argument is the raw JSON text; the repo validates +
     * encrypts-at-rest. Pass `null` to clear. Setting a non-null JSON
     * also clears any existing API key (the two modes are mutually
     * exclusive). Throws [IllegalArgumentException] from the parse
     * path if the JSON is malformed or not a service-account key —
     * the UI catches and toasts the message.
     */
    suspend fun setVertexServiceAccountJson(json: String?)
    /** Azure Foundry mutators. [setFoundryApiKey] with `null` clears
     *  the encrypted key. [setFoundryServerless] flips the URL template
     *  + body shape — see `AzureFoundryProvider.buildUrl`. */
    suspend fun setFoundryApiKey(key: String?)
    suspend fun setFoundryEndpoint(url: String)
    suspend fun setFoundryDeployment(deployment: String)
    suspend fun setFoundryServerless(serverless: Boolean)
    /** AWS Bedrock BYOK creds. Pass null to clear individual keys.
     *  Both must be set for the provider to be usable; we let the
     *  user save them one at a time so the UI can paste / show / save
     *  flow stays per-field. */
    suspend fun setBedrockAccessKey(key: String?)
    suspend fun setBedrockSecretKey(key: String?)
    suspend fun setBedrockRegion(region: String)
    suspend fun setBedrockModel(model: String)
    suspend fun setSendChapterTextEnabled(enabled: Boolean)
    /** Issue #212 — chat grounding-level toggles. See [UiChatGrounding]. */
    suspend fun setChatGroundChapterTitle(enabled: Boolean)
    suspend fun setChatGroundCurrentSentence(enabled: Boolean)
    suspend fun setChatGroundEntireChapter(enabled: Boolean)
    suspend fun setChatGroundEntireBookSoFar(enabled: Boolean)
    /** Issue #217 — "Carry memory across fictions" toggle. See
     *  [UiAiSettings.carryMemoryAcrossFictions]. */
    suspend fun setCarryMemoryAcrossFictions(enabled: Boolean)
    suspend fun acknowledgeAiPrivacy()
    /**
     * Anthropic Teams (OAuth) — local sign-out. Wipes the bearer +
     * refresh + scope cache. Remote revoke at console.anthropic.com
     * requires a client_secret we don't have (public-client posture);
     * Settings UI deep-links the user to manage sessions there. (#181)
     */
    suspend fun signOutTeams()
    /** Wipe all AI configuration — provider/keys/URLs. */
    suspend fun resetAiSettings()

    // ── GitHub OAuth (#91) ─────────────────────────────────────────
    /**
     * Local sign-out from GitHub. Clears the encrypted token + identity
     * metadata. Remote revoke at github.com requires the client_secret
     * we don't have — Settings UI deep-links the user to
     * `github.com/settings/applications` if they want to revoke fully.
     */
    suspend fun signOutGitHub()

    /**
     * Issue #203 — toggle "Enable private repos" preference. ON makes the
     * next Device Flow request the `repo` scope; OFF goes back to
     * `public_repo`. The currently-signed-in token is unaffected —
     * existing sessions keep the scopes they were granted with until the
     * user re-signs-in.
     */
    suspend fun setGitHubPrivateReposEnabled(enabled: Boolean)

    /** Per-source on/off toggles (#221). */
    suspend fun setSourceRoyalRoadEnabled(enabled: Boolean)
    suspend fun setSourceGitHubEnabled(enabled: Boolean)
    suspend fun setSourceMemPalaceEnabled(enabled: Boolean)

    /** Issue #236 — RSS / Atom backend on/off. */
    suspend fun setSourceRssEnabled(enabled: Boolean)

    /** Issue #245 — Outline self-hosted-wiki backend on/off + config. */
    suspend fun setSourceOutlineEnabled(enabled: Boolean)
    val outlineHost: Flow<String>
    suspend fun setOutlineHost(host: String)
    suspend fun setOutlineApiKey(apiKey: String)
    suspend fun clearOutlineConfig()

    /** Issue #237 — Project Gutenberg backend on/off. */
    suspend fun setSourceGutenbergEnabled(enabled: Boolean)

    /** Issue #381 — Archive of Our Own backend on/off. */
    suspend fun setSourceAo3Enabled(enabled: Boolean)
    /** Issue #375 — Standard Ebooks backend on/off. */
    suspend fun setSourceStandardEbooksEnabled(enabled: Boolean)
    /** Issue #377 — Wikipedia backend on/off + per-language host. */
    suspend fun setSourceWikipediaEnabled(enabled: Boolean)
    /** Issue #377 — set the Wikipedia language code (`en`, `de`,
     *  `ja`, `simple`, ...). Trimmed + lowercased before persistence;
     *  empty falls back to the default. */
    suspend fun setWikipediaLanguageCode(code: String)
    /** Issue #376 — Wikisource (transcribed public-domain texts)
     *  backend on/off. */
    suspend fun setSourceWikisourceEnabled(enabled: Boolean)
    /** Issue #374 — KVMR community radio backend on/off. */
    suspend fun setSourceKvmrEnabled(enabled: Boolean)

    /**
     * Plugin-seam Phase 1 (#384) — toggle a `@SourcePlugin`-registered
     * backend by its stable id. Updates the JSON-serialised
     * `sourcePluginsEnabled` map AND, for ids that still have a
     * matching legacy `setSourceXxxEnabled` setter, the corresponding
     * legacy preference so the existing UI keeps observing the change.
     *
     * Unknown ids (no plugin registered) write to the map regardless,
     * letting Phase 2 backends pre-populate their toggle state before
     * their `@SourcePlugin` annotation lands.
     */
    suspend fun setSourcePluginEnabled(id: String, enabled: Boolean)

    /** Issue #233 — Notion fiction backend on/off + config. */
    suspend fun setSourceNotionEnabled(enabled: Boolean)
    /** Issue #379 — Hacker News backend on/off. */
    suspend fun setSourceHackerNewsEnabled(enabled: Boolean)
    /** Issue #378 — arXiv backend on/off. */
    suspend fun setSourceArxivEnabled(enabled: Boolean)
    /** Issue #380 — PLOS open-access peer-reviewed science backend
     *  on/off. */
    suspend fun setSourcePlosEnabled(enabled: Boolean)
    /** Issue #233 — set the Notion database id the source queries.
     *  Both hyphenated UUID and compact 32-hex forms accepted; the
     *  impl normalizes whitespace. Empty falls back to the baked-in
     *  techempower.org default (#390). */
    suspend fun setNotionDatabaseId(id: String)
    /** Issue #233 — persist or clear the Notion integration token.
     *  Pass null or empty to clear. Stored encrypted alongside the
     *  Outline / palace tokens in `storyvox.secrets`. */
    suspend fun setNotionApiToken(token: String?)

    /** Issue #403 — Discord backend on/off. Default OFF on fresh
     *  installs — bot-token onboarding is high-friction. */
    suspend fun setSourceDiscordEnabled(enabled: Boolean)
    /** Issue #403 — persist or clear the Discord bot token. Pass
     *  null or empty to clear. Stored encrypted under
     *  `pref_source_discord_token` in `storyvox.secrets`. */
    suspend fun setDiscordApiToken(token: String?)
    /** Issue #403 — persist the selected Discord server id +
     *  human-readable name. Both captured at the moment the user
     *  picks the server from the populated picker. Pass empty
     *  strings to clear the selection. */
    suspend fun setDiscordServer(serverId: String, serverName: String)
    /** Issue #403 — same-author message coalesce window (minutes).
     *  Slider range 1-30; impl clamps. */
    suspend fun setDiscordCoalesceMinutes(minutes: Int)
    /**
     * Issue #403 — fetch the list of guilds (servers) the configured
     * bot has been invited to. Drives the Settings server picker
     * dropdown. Returns an empty list when no token is configured or
     * the call fails — the UI handles both as "nothing to pick from"
     * (with a hint that the token is missing for the first case).
     */
    suspend fun fetchDiscordGuilds(): List<Pair<String, String>>

    /** Issue #236 — manage subscribed feed URLs. */
    suspend fun addRssFeed(url: String)
    suspend fun removeRssFeed(fictionId: String)
    suspend fun removeRssFeedByUrl(url: String)
    val rssSubscriptions: Flow<List<String>>

    /** Issue #235 — local EPUB backend on/off. */
    suspend fun setSourceEpubEnabled(enabled: Boolean)

    /** Issue #235 — currently-picked SAF folder URI (or null). */
    val epubFolderUri: Flow<String?>
    suspend fun setEpubFolderUri(uri: String)
    suspend fun clearEpubFolder()

    /** Issue #246 — curated suggested feeds, fetched from the
     *  jphein/storyvox-feeds GitHub repo on first observation,
     *  cached for the app session, falling back to a baked-in list
     *  on parse failure / first-launch-offline. */
    val suggestedRssFeeds: Flow<List<SuggestedFeed>>

    /** Issue #150 — sleep timer shake-to-extend on/off. */
    suspend fun setSleepShakeToExtendEnabled(enabled: Boolean)

    // ── Azure Speech Services BYOK (#182) ──────────────────────────
    /** Persist the user's Azure subscription key. `null` clears it. */
    suspend fun setAzureKey(key: String?)
    /** Persist the Azure resource region id (e.g. `eastus`). The id is
     *  used verbatim in the endpoint URL; pass a raw region id to
     *  support the "Other" affordance. */
    suspend fun setAzureRegion(regionId: String)
    /** Wipe both key and region — Settings "Forget key" button. */
    suspend fun clearAzureCredentials()
    /**
     * One-shot reachability probe against the configured Azure region
     * + key. Hits the `voices/list` endpoint, which is a cheap GET
     * that exercises auth + DNS + TLS but doesn't bill any synthesis
     * characters. Returns the voice count on success, an error
     * message on failure.
     */
    suspend fun testAzureConnection(): AzureProbeResult

    /** PR-6 (#185) — Azure offline-fallback toggle. */
    suspend fun setAzureFallbackEnabled(enabled: Boolean)
    /** PR-6 (#185) — voice id used when fallback fires. Pass null to
     *  clear so the toggle becomes a no-op until a voice is picked. */
    suspend fun setAzureFallbackVoiceId(voiceId: String?)

    /** Tier 3 (#88) — experimental parallel-synth instance count
     *  (range 1..[PARALLEL_SYNTH_MAX_INSTANCES]). 1 = serial, higher
     *  values fan out across N engines. */
    suspend fun setParallelSynthInstances(count: Int)

    /** Tier 3 companion (#88) — numThreads override per engine.
     *  0 = Auto (VoxSherpa's getOptimalThreadCount heuristic).
     *  1..[PARALLEL_SYNTH_MAX_INSTANCES] = explicit value passed to
     *  sherpa-onnx. */
    suspend fun setSynthThreadsPerInstance(count: Int)

    /**
     * Vesper (v0.4.97) — toggle the debug overlay master switch
     * ([UiSettings.showDebugOverlay]). Default implementation no-ops so
     * existing fakes in the feature test suite don't need to be touched
     * — only the real DataStore impl persists the value. Test fakes
     * that *do* care about the overlay's persistence can override.
     */
    suspend fun setShowDebugOverlay(enabled: Boolean) {
        // default no-op for test fakes; SettingsRepositoryUiImpl overrides.
    }

    // ── v0.5.00 milestone celebration (Calliope) ───────────────────
    /**
     * Calliope (v0.5.00) — one-time celebration state for the
     * graduation milestone. Drives the brass "thank-you" dialog and
     * the chapter-complete confetti easter-egg. See [MilestoneState]
     * for the field semantics. Default emits an inert state so test
     * fakes neither flicker the dialog nor the confetti — the only
     * caller that needs real data is the production app's Settings
     * repo, which reads from DataStore + BuildConfig.
     */
    val milestoneState: Flow<MilestoneState>
        get() = kotlinx.coroutines.flow.flowOf(MilestoneState())

    /** Flip the "saw the milestone dialog" flag to true so it never
     *  shows again on this install. Default no-op for fakes; the
     *  DataStore impl persists. */
    suspend fun markMilestoneDialogSeen() {}

    /** Flip the "saw the confetti easter-egg" flag to true so it
     *  never fires again on this install. Default no-op for fakes;
     *  the DataStore impl persists. */
    suspend fun markMilestoneConfettiShown() {}

    // ── Issue #383 — Inbox per-source mute toggles ─────────────────
    /**
     * Per-source Inbox notification toggles. When OFF, the backend's
     * update emitter (poller, watcher) does NOT write to the
     * `inbox_event` table for that source. Default ON across the
     * board; default impls here let test fakes that don't care about
     * the Inbox surface ignore the calls.
     */
    suspend fun setInboxNotifyRoyalRoad(enabled: Boolean) {}
    suspend fun setInboxNotifyKvmr(enabled: Boolean) {}
    suspend fun setInboxNotifyWikipedia(enabled: Boolean) {}
}

/**
 * v0.5.00 milestone state. All three fields are independent gates:
 *
 *  - [qualifies] is true when the build's version is v0.5.00 or
 *    later. On lower builds nothing in this struct matters — both
 *    surfaces stay dark. Computed from [BuildConfig.VERSION_NAME]
 *    in the production repo; always false in tests.
 *  - [dialogSeen] flips to true after the user dismisses the
 *    one-time dialog. The dialog renders only when `qualifies &&
 *    !dialogSeen`.
 *  - [confettiShown] flips to true after the first natural
 *    chapter-completion drops the celebration overlay. Confetti
 *    renders only when `qualifies && !confettiShown` AND a
 *    PlaybackUiEvent.ChapterDone arrives. The two are deliberately
 *    independent — the user might dismiss the dialog before
 *    listening, or never open the dialog and just finish a chapter.
 *
 * Default instance is the "inert" state safe for previews + test
 * fakes — nothing fires.
 */
data class MilestoneState(
    val qualifies: Boolean = false,
    val dialogSeen: Boolean = false,
    val confettiShown: Boolean = false,
)

/** Tier 3 (#88) slider bounds. Min 1 (serial), max 8 (the Snapdragon
 *  888 / Helio P22T core count ceiling — beyond 8 the OS scheduler
 *  dominates and instance memory cost becomes pathological). */
const val PARALLEL_SYNTH_MIN_INSTANCES: Int = 1
const val PARALLEL_SYNTH_MAX_INSTANCES: Int = 8

/** Outcome of [`SettingsRepositoryUi.testPalaceConnection`]. */
sealed class PalaceProbeResult {
    data class Reachable(val daemonVersion: String) : PalaceProbeResult()
    data class Unreachable(val message: String) : PalaceProbeResult()
    object NotConfigured : PalaceProbeResult()
}

/**
 * Outcome of [`SettingsRepositoryUi.testAzureConnection`] (#182).
 * Distinct cases for `AuthFailed` and `Unreachable` so the Settings
 * UI can render different copy ("re-paste your key" vs. "check your
 * connection") without string-matching on error messages.
 */
sealed class AzureProbeResult {
    /** voices/list returned 200 — key + region are good. */
    data class Reachable(val voiceCount: Int) : AzureProbeResult()
    /** 401 / 403 — key rejected. UX: prompt to re-paste. */
    data class AuthFailed(val message: String) : AzureProbeResult()
    /** Network failure, 5xx, or any other transport-level problem. */
    data class Unreachable(val message: String) : AzureProbeResult()
    /** No key configured — Test button still fires this for clarity. */
    object NotConfigured : AzureProbeResult()
}
