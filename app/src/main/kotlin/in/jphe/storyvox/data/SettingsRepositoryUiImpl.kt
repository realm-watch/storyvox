package `in`.jphe.storyvox.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.data.auth.SessionHydrator
import `in`.jphe.storyvox.data.repository.AuthRepository
import `in`.jphe.storyvox.data.repository.playback.AzureFallbackConfig
import `in`.jphe.storyvox.data.repository.playback.AzureFallbackState
import `in`.jphe.storyvox.data.repository.playback.ParallelSynthConfig
import `in`.jphe.storyvox.data.repository.playback.ParallelSynthState
import `in`.jphe.storyvox.data.repository.playback.PlaybackBufferConfig
import `in`.jphe.storyvox.data.repository.playback.PlaybackModeConfig
import `in`.jphe.storyvox.data.repository.playback.PlaybackResumePolicyConfig
import `in`.jphe.storyvox.data.repository.playback.VoiceTuningConfig
import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationDict
import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationDictRepository
import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationEntry
import `in`.jphe.storyvox.data.repository.pronunciation.decodePronunciationDictJson
import `in`.jphe.storyvox.data.repository.pronunciation.encodePronunciationDictJson
import `in`.jphe.storyvox.feature.api.BUFFER_DEFAULT_CHUNKS
import `in`.jphe.storyvox.feature.api.BUFFER_MAX_CHUNKS
import `in`.jphe.storyvox.feature.api.BUFFER_MIN_CHUNKS
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_DEFAULT_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_LONG_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_MAX_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_MIN_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_NORMAL_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_OFF_MULTIPLIER
import `in`.jphe.storyvox.feature.api.AzureProbeResult
import `in`.jphe.storyvox.feature.api.PalaceProbeResult
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.feature.api.UiAiSettings
import `in`.jphe.storyvox.feature.api.UiAzureConfig
import `in`.jphe.storyvox.feature.api.UiChatGrounding
import `in`.jphe.storyvox.feature.api.UiGitHubAuthState
import `in`.jphe.storyvox.feature.api.UiLlmProvider
import `in`.jphe.storyvox.feature.api.UiPalaceConfig
import `in`.jphe.storyvox.source.azure.AzureCredentials
import `in`.jphe.storyvox.source.azure.AzureError
import `in`.jphe.storyvox.source.azure.AzureRegion
import `in`.jphe.storyvox.source.azure.AzureSpeechClient
import `in`.jphe.storyvox.source.azure.AzureVoiceRoster
import `in`.jphe.storyvox.feature.api.UiSettings
import `in`.jphe.storyvox.feature.api.UiSigil
import `in`.jphe.storyvox.source.github.auth.GitHubAuthRepository
import `in`.jphe.storyvox.source.github.auth.GitHubScopePreferences
import `in`.jphe.storyvox.source.github.auth.GitHubSession
import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmConfigProvider
import `in`.jphe.storyvox.llm.auth.AnthropicTeamsAuthRepository
import `in`.jphe.storyvox.llm.auth.TeamsSession
import `in`.jphe.storyvox.llm.LlmCredentialsStore
import `in`.jphe.storyvox.llm.ProviderId
import `in`.jphe.storyvox.sigil.Sigil
import `in`.jphe.storyvox.source.mempalace.net.PalaceDaemonApi
import `in`.jphe.storyvox.source.mempalace.net.PalaceDaemonResult
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "storyvox_settings",
    produceMigrations = { _ ->
        listOf(
            PunctuationPauseEnumToMultiplierMigration,
            FirstTimeDefaultVoiceMigration,
        )
    },
)

/**
 * Issue #109 — one-shot migration from the pre-#109 enum-string key
 * `pref_punctuation_pause` ("Off"/"Normal"/"Long") to the continuous
 * Float key `pref_punctuation_pause_multiplier_v2`. Maps:
 *   Off    → 0×
 *   Normal → 1×
 *   Long   → 1.75×
 *
 * Runs once per process at first DataStore read. Idempotent — `shouldMigrate`
 * returns false once the new key is present, so a partial-migration crash
 * mid-run still completes safely on next launch. The old key is removed
 * after the new one is written.
 */
internal val PunctuationPauseEnumToMultiplierMigration: DataMigration<Preferences> =
    object : DataMigration<Preferences> {
        private val oldKey = stringPreferencesKey("pref_punctuation_pause")
        private val newKey = floatPreferencesKey("pref_punctuation_pause_multiplier_v2")

        override suspend fun shouldMigrate(currentData: Preferences): Boolean {
            // If new key is set, we're done. If old key isn't present either, also done
            // (fresh install — defaults take over). Otherwise we have an enum value to map.
            if (currentData[newKey] != null) return false
            return currentData[oldKey] != null
        }

        override suspend fun migrate(currentData: Preferences): Preferences {
            val mutable = currentData.toMutablePreferences()
            val mapped = when (currentData[oldKey]) {
                "Off" -> PUNCTUATION_PAUSE_OFF_MULTIPLIER
                "Long" -> PUNCTUATION_PAUSE_LONG_MULTIPLIER
                // "Normal" or any unrecognized legacy value falls through to the default.
                else -> PUNCTUATION_PAUSE_NORMAL_MULTIPLIER
            }
            mutable[newKey] = mapped
            mutable.remove(oldKey)
            return mutable.toPreferences()
        }

        override suspend fun cleanUp() = Unit
    }

/**
 * Issue #294 — seed `pref_default_voice_id` on a fresh install so the
 * "no voice activated yet" state doesn't leave defaultVoiceId null
 * until the user explicitly picks one (that pushes first-run friction
 * onto a picker tap).
 *
 * Updated 2026-05-13: the seed follows
 * `VoiceCatalog.featuredIds[0]` — currently `piper_lessac_en_US_low`,
 * the smallest of the three Lessac quality tiers the VoicePickerGate
 * presents. Picking the low tier as the implicit default minimizes
 * first-launch download size; users who want richer audio can pick
 * Medium or High in the picker before the gate dismisses.
 *
 * Runs once per process at first DataStore read. Idempotent —
 * `shouldMigrate` returns false the moment the key is present, so a
 * user who picks a different voice on first launch (or who upgraded
 * from a build that already has a stored voice) gets their choice
 * preserved verbatim.
 */
internal val FirstTimeDefaultVoiceMigration: DataMigration<Preferences> =
    object : DataMigration<Preferences> {
        private val voiceKey = stringPreferencesKey("pref_default_voice_id")

        override suspend fun shouldMigrate(currentData: Preferences): Boolean =
            currentData[voiceKey] == null

        override suspend fun migrate(currentData: Preferences): Preferences {
            val mutable = currentData.toMutablePreferences()
            mutable[voiceKey] = "piper_lessac_en_US_low"
            return mutable.toPreferences()
        }

        override suspend fun cleanUp() = Unit
    }

private object Keys {
    val DEFAULT_SPEED = floatPreferencesKey("pref_default_speed")
    val DEFAULT_PITCH = floatPreferencesKey("pref_default_pitch")
    val DEFAULT_VOICE_ID = stringPreferencesKey("pref_default_voice_id")

    // Issue #195 — per-voice speed/pitch override maps. Stored as a
    // simple `voiceId=value;voiceId=value` string to avoid pulling in
    // kotlinx-serialization for one tiny map (and to keep DataStore's
    // type-safe key API). Empty when no overrides are present, which
    // is the migration default for pre-#195 installs.
    val VOICE_SPEED_OVERRIDES = stringPreferencesKey("pref_voice_speed_overrides")
    val VOICE_PITCH_OVERRIDES = stringPreferencesKey("pref_voice_pitch_overrides")
    val THEME_OVERRIDE = stringPreferencesKey("pref_theme_override")
    val DOWNLOAD_WIFI_ONLY = booleanPreferencesKey("pref_download_wifi_only")
    val POLL_INTERVAL_HOURS = intPreferencesKey("pref_poll_interval_hours")
    val SIGNED_IN = booleanPreferencesKey("pref_signed_in")
    /** Issue #109 — continuous inter-sentence pause multiplier (Float).
     *  Replaces the pre-#109 enum-string key `pref_punctuation_pause`; the
     *  one-shot [PunctuationPauseEnumToMultiplierMigration] in this file
     *  maps existing installs forward. Default = 1× preserves the
     *  audiobook-tuned baseline on fresh installs. */
    val PUNCTUATION_PAUSE_MULTIPLIER = floatPreferencesKey("pref_punctuation_pause_multiplier_v2")
    /** Pre-synth queue depth (sentence-chunks). Issue #84 — the slider is an
     *  exploratory probe for where Android's LMK kills the app on slow
     *  devices, so the persisted value is intentionally NOT clamped at a
     *  conservative ceiling; only the absolute mechanical bounds apply. */
    val PLAYBACK_BUFFER_CHUNKS = intPreferencesKey("pref_playback_buffer_chunks_v1")
    /** Issue #98 — Mode A. Default true preserves the v0.4.30 spinner-on-warmup
     *  behavior; OFF skips the warmup-wait UI and accepts silence at chapter
     *  start. _v1 suffix matches PLAYBACK_BUFFER_CHUNKS' versioning so we can
     *  rev defaults later without colliding with persisted v1 values. */
    val WARMUP_WAIT = booleanPreferencesKey("pref_warmup_wait_v1")
    /** Issue #98 — Mode B. Default true preserves PR #77's pause-buffer-resume
     *  behavior on mid-stream underrun; OFF lets the consumer drain through
     *  underruns without raising the "Buffering…" UI. */
    val CATCHUP_PAUSE = booleanPreferencesKey("pref_catchup_pause_v1")
    /** Issue #85 — Voice-Determinism preset. Default true = Steady, which
     *  preserves the pre-#85 calmed VITS defaults (0.35 / 0.667). false =
     *  Expressive (sherpa-onnx upstream Piper defaults 0.667 / 0.8 — more
     *  variable prosody but less reproducible). _v1 suffix matches the
     *  versioning convention used by PLAYBACK_BUFFER_CHUNKS / WARMUP_WAIT
     *  so we can rev defaults later without colliding with persisted v1
     *  values. */
    val VOICE_STEADY = booleanPreferencesKey("pref_voice_steady_v1")

    // ── AI / LLM (issue #81) ────────────────────────────────────────
    /** Active provider — stored as the [ProviderId] enum's name.
     *  Empty/missing = AI disabled. */
    val AI_PROVIDER = stringPreferencesKey("pref_ai_provider")
    val AI_CLAUDE_MODEL = stringPreferencesKey("pref_ai_claude_model")
    val AI_OPENAI_MODEL = stringPreferencesKey("pref_ai_openai_model")
    val AI_OLLAMA_BASE_URL = stringPreferencesKey("pref_ai_ollama_base_url")
    val AI_OLLAMA_MODEL = stringPreferencesKey("pref_ai_ollama_model")
    val AI_VERTEX_MODEL = stringPreferencesKey("pref_ai_vertex_model")
    /** Azure Foundry — endpoint URL the user pasted (e.g.
     *  `https://my-resource.openai.azure.com`). Empty/missing = not
     *  configured. */
    val AI_FOUNDRY_ENDPOINT = stringPreferencesKey("pref_ai_foundry_endpoint")
    /** Azure Foundry — deployment name (deployed mode) or catalog
     *  model id (serverless mode). */
    val AI_FOUNDRY_DEPLOYMENT = stringPreferencesKey("pref_ai_foundry_deployment")
    /** Azure Foundry — true selects the serverless URL shape;
     *  default (false) selects per-model deployed URLs. */
    val AI_FOUNDRY_SERVERLESS = booleanPreferencesKey("pref_ai_foundry_serverless")
    val AI_PRIVACY_ACK = booleanPreferencesKey("pref_ai_privacy_ack")
    val AI_SEND_CHAPTER_TEXT = booleanPreferencesKey("pref_ai_send_chapter_text")
    val AI_BEDROCK_REGION = stringPreferencesKey("pref_ai_bedrock_region")
    val AI_BEDROCK_MODEL = stringPreferencesKey("pref_ai_bedrock_model")

    /** Issue #203 — "Enable private repos" toggle. Default false keeps
     *  the least-privilege public-only baseline; ON makes the next
     *  Device Flow request the `repo` scope. _v1 suffix matches the
     *  versioning convention used by other v1-tagged keys here. */
    val GITHUB_PRIVATE_REPOS_ENABLED = booleanPreferencesKey("pref_github_private_repos_enabled_v1")

    // ── Per-source on/off (issue #221) ─────────────────────────────
    val SOURCE_ROYALROAD_ENABLED = booleanPreferencesKey("pref_source_royalroad_enabled")
    val SOURCE_GITHUB_ENABLED = booleanPreferencesKey("pref_source_github_enabled")
    val SOURCE_MEMPALACE_ENABLED = booleanPreferencesKey("pref_source_mempalace_enabled")
    /** Issue #236 — RSS backend on/off. */
    val SOURCE_RSS_ENABLED = booleanPreferencesKey("pref_source_rss_enabled")
    /** Issue #235 — local EPUB backend on/off. */
    val SOURCE_EPUB_ENABLED = booleanPreferencesKey("pref_source_epub_enabled")
    /** Issue #245 — Outline backend on/off. */
    val SOURCE_OUTLINE_ENABLED = booleanPreferencesKey("pref_source_outline_enabled")
    /** Issue #237 — Project Gutenberg backend on/off. Default true
     *  for fresh installs; an explicit prefs entry overrides. */
    val SOURCE_GUTENBERG_ENABLED = booleanPreferencesKey("pref_source_gutenberg_enabled")
    val SOURCE_AO3_ENABLED = booleanPreferencesKey("pref_source_ao3_enabled")

    // ── Sleep timer shake-to-extend (issue #150) ───────────────────
    val SLEEP_SHAKE_TO_EXTEND_ENABLED = booleanPreferencesKey("pref_sleep_shake_to_extend_enabled")

    // ── Debug overlay (Vesper, v0.4.97) ────────────────────────────
    /** Master switch for the on-Reader debug overlay. Default false;
     *  power users opt in from Settings → Developer. The dedicated
     *  /debug screen is reachable regardless of this toggle. */
    val SHOW_DEBUG_OVERLAY = booleanPreferencesKey("pref_show_debug_overlay")

    // ── Azure offline-fallback (issue #185, PR-6) ──────────────────
    val AZURE_FALLBACK_ENABLED = booleanPreferencesKey("pref_azure_fallback_enabled")
    val AZURE_FALLBACK_VOICE_ID = stringPreferencesKey("pref_azure_fallback_voice_id")

    // ── Tier 3 parallel synth (issue #88) ──────────────────────────
    /** Pre-slider Boolean key (kept for read-side migration). When the
     *  Int key below is unset and this is true, treat as count = 2. */
    val EXPERIMENTAL_PARALLEL_SYNTH = booleanPreferencesKey("pref_experimental_parallel_synth")
    /** Slider-era Int key — 1..8 instance count. */
    val PARALLEL_SYNTH_INSTANCES = intPreferencesKey("pref_parallel_synth_instances")
    /** Slider-era Int key — 0 = Auto, 1..8 = numThreads override per engine. */
    val SYNTH_THREADS_PER_INSTANCE = intPreferencesKey("pref_synth_threads_per_instance")

    // ── Resume policy (issue #90) ──────────────────────────────────
    /** Tracks the user's last play/pause intent. true = was playing,
     *  false = explicitly paused. Library's Resume CTA reads this. */
    val LAST_WAS_PLAYING = booleanPreferencesKey("pref_last_was_playing")

    // ── Chat grounding (issue #212) ────────────────────────────────
    /** Defaults match pre-#212 ChatViewModel behaviour: chapter title
     *  on, every more-expensive level off. */
    val AI_CHAT_GROUND_CHAPTER_TITLE = booleanPreferencesKey("pref_ai_chat_ground_chapter_title")
    val AI_CHAT_GROUND_CURRENT_SENTENCE = booleanPreferencesKey("pref_ai_chat_ground_current_sentence")
    val AI_CHAT_GROUND_ENTIRE_CHAPTER = booleanPreferencesKey("pref_ai_chat_ground_entire_chapter")
    val AI_CHAT_GROUND_ENTIRE_BOOK = booleanPreferencesKey("pref_ai_chat_ground_entire_book")

    /** Issue #135 — JSON-serialized [PronunciationDict] (list of
     *  pattern/replacement/matchType entries). _v1 suffix lets us
     *  rev the schema later without a destructive migration; an
     *  unparseable v1 blob falls back to [PronunciationDict.EMPTY]
     *  and the user can re-enter their entries.
     *
     *  Stored as a flat JSON string rather than DataStore-Proto
     *  because the rest of the file uses preferencesDataStore and
     *  we want one store, one migration surface. The Json instance
     *  in this file matches Converters.kt's settings
     *  (`ignoreUnknownKeys = true; encodeDefaults = true`). */
    val PRONUNCIATION_DICT = stringPreferencesKey("pref_pronunciation_dict_v1")

    // ── Calliope (v0.5.00) milestone celebration ──────────────────
    /** One-time gate for the brass "thank-you" dialog. Flips to true
     *  after the user taps Continue (or taps outside the card). Pre-
     *  flip, the dialog renders on every fresh launch of a v0.5.00+
     *  build. Post-flip, the dialog never reappears for the life of
     *  this install. Cleared by uninstall / app-data-clear like every
     *  other pref. */
    val V0500_MILESTONE_SEEN = booleanPreferencesKey("pref_v0500_milestone_seen")
    /** One-time gate for the chapter-complete confetti easter-egg.
     *  Flips to true after the overlay fades. Independent from
     *  [V0500_MILESTONE_SEEN] — the user might dismiss the dialog
     *  before listening, or listen first and only later open the
     *  dialog. */
    val V0500_CONFETTI_SHOWN = booleanPreferencesKey("pref_v0500_confetti_shown")
}

/** Issue #195 — flat string codec for `Map<voiceId, Float>` overrides.
 *  Format: `voiceId=value;voiceId=value`. Voice IDs from the catalog
 *  are alphanumeric + underscores (`piper_amy_x_low`, `kokoro_af_bella`)
 *  so neither `=` nor `;` collide with valid IDs. Empty / null input
 *  parses to an empty map. Bad entries are dropped silently — the
 *  override map is non-critical state. */
private fun encodeVoiceFloatMap(map: Map<String, Float>): String =
    map.entries.joinToString(";") { (k, v) -> "$k=$v" }

private fun decodeVoiceFloatMap(raw: String?): Map<String, Float> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(';').mapNotNull { entry ->
        val eq = entry.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val k = entry.substring(0, eq)
        val v = entry.substring(eq + 1).toFloatOrNull() ?: return@mapNotNull null
        k to v
    }.toMap()
}


@Singleton
class SettingsRepositoryUiImpl(
    private val store: DataStore<Preferences>,
    private val auth: AuthRepository,
    private val hydrator: SessionHydrator,
    private val palaceConfig: PalaceConfigImpl,
    private val palaceApi: PalaceDaemonApi,
    private val llmCreds: LlmCredentialsStore,
    private val githubAuth: GitHubAuthRepository,
    private val teamsAuth: AnthropicTeamsAuthRepository,
    private val rssConfig: RssConfigImpl,
    private val epubConfig: EpubConfigImpl,
    private val outlineConfig: OutlineConfigImpl,
    private val suggestedFeedsRegistry: SuggestedFeedsRegistry,
    private val azureCreds: AzureCredentials,
    private val azureClient: AzureSpeechClient,
    private val azureRoster: AzureVoiceRoster,
) : SettingsRepositoryUi,
    PlaybackBufferConfig,
    PlaybackModeConfig,
    VoiceTuningConfig,
    AzureFallbackConfig,
    ParallelSynthConfig,
    PlaybackResumePolicyConfig,
    PronunciationDictRepository,
    LlmConfigProvider,
    GitHubScopePreferences {

    /** Hilt entry point — pulls the production DataStore from the app context.
     *  The primary constructor takes the store directly so tests can swap in
     *  a `PreferenceDataStoreFactory.create(file)`-backed instance against a
     *  `TemporaryFolder`. Mirrors the seam used in [VoiceFavorites.forTesting]. */
    @Inject constructor(
        @ApplicationContext context: Context,
        auth: AuthRepository,
        hydrator: SessionHydrator,
        palaceConfig: PalaceConfigImpl,
        palaceApi: PalaceDaemonApi,
        llmCreds: LlmCredentialsStore,
        githubAuth: GitHubAuthRepository,
        teamsAuth: AnthropicTeamsAuthRepository,
        rssConfig: RssConfigImpl,
        epubConfig: EpubConfigImpl,
        outlineConfig: OutlineConfigImpl,
        suggestedFeedsRegistry: SuggestedFeedsRegistry,
        azureCreds: AzureCredentials,
        azureClient: AzureSpeechClient,
        azureRoster: AzureVoiceRoster,
    ) : this(
        context.settingsDataStore, auth, hydrator,
        palaceConfig, palaceApi, llmCreds, githubAuth, teamsAuth, rssConfig, epubConfig,
        outlineConfig, suggestedFeedsRegistry, azureCreds, azureClient, azureRoster,
    )

    /**
     * Tick that bumps on every Azure setter so the [settings] flow
     * re-emits with the fresh [AzureCredentials] snapshot. The
     * encrypted-prefs store doesn't expose a Flow of its own (it's a
     * bare [SharedPreferences] for cross-language compatibility with
     * [LlmCredentialsStore]), so this internal tick fills the gap —
     * same shape as `MutableStateFlow<Long>(0)` used elsewhere in the
     * app for non-flow-aware deps.
     */
    private val azureTick = kotlinx.coroutines.flow.MutableStateFlow(0L)

    override val settings: Flow<UiSettings> = combine(
        store.data,
        palaceConfig.state,
        githubAuth.sessionState,
        teamsAuth.sessionState,
        azureTick,
    ) { prefs, palace, githubSession, teamsSession, _ ->
        UiSettings(
            ttsEngine = "VoxSherpa",
            defaultVoiceId = prefs[Keys.DEFAULT_VOICE_ID],
            // Issue #294 — web-fiction listeners consistently prefer 1.10×;
            // 1.00× is audiobook tempo, too slow for serial fiction.
            // Existing users keep their stored value via the prefs lookup;
            // only the fallback for a fresh install changed.
            defaultSpeed = prefs[Keys.DEFAULT_SPEED] ?: 1.1f,
            defaultPitch = prefs[Keys.DEFAULT_PITCH] ?: 1.0f,
            voiceSpeedOverrides = decodeVoiceFloatMap(prefs[Keys.VOICE_SPEED_OVERRIDES]),
            voicePitchOverrides = decodeVoiceFloatMap(prefs[Keys.VOICE_PITCH_OVERRIDES]),
            themeOverride = prefs[Keys.THEME_OVERRIDE]?.let { runCatching { ThemeOverride.valueOf(it) }.getOrNull() }
                ?: ThemeOverride.System,
            downloadOnWifiOnly = prefs[Keys.DOWNLOAD_WIFI_ONLY] ?: true,
            pollIntervalHours = prefs[Keys.POLL_INTERVAL_HOURS] ?: 6,
            isSignedIn = prefs[Keys.SIGNED_IN] ?: false,
            punctuationPauseMultiplier = (prefs[Keys.PUNCTUATION_PAUSE_MULTIPLIER]
                ?: PUNCTUATION_PAUSE_DEFAULT_MULTIPLIER)
                .coerceIn(PUNCTUATION_PAUSE_MIN_MULTIPLIER, PUNCTUATION_PAUSE_MAX_MULTIPLIER),
            playbackBufferChunks = (prefs[Keys.PLAYBACK_BUFFER_CHUNKS] ?: BUFFER_DEFAULT_CHUNKS)
                .coerceIn(BUFFER_MIN_CHUNKS, BUFFER_MAX_CHUNKS),
            // 2026-05-09: warmupWait default off (UX), catchupPause
            // default back on (perf — see UiSettings.catchupPause kdoc).
            warmupWait = prefs[Keys.WARMUP_WAIT] ?: false,
            catchupPause = prefs[Keys.CATCHUP_PAUSE] ?: true,
            voiceSteady = prefs[Keys.VOICE_STEADY] ?: true,
            palace = UiPalaceConfig(host = palace.host, apiKey = palace.apiKey),
            github = githubSession.toUi(),
            githubPrivateReposEnabled = prefs[Keys.GITHUB_PRIVATE_REPOS_ENABLED] ?: false,
            // Issue #294 — Royal Road is the de-facto primary source
            // storyvox was built around; defaulting OFF meant a new user
            // opened Browse and saw the empty-picker state. Flip to ON
            // for fresh installs alongside RSS. Existing users keep their
            // stored toggle via the prefs lookup; only the fallback
            // changed.
            //
            // The playstore build flavor (#240, not yet landed) is
            // expected to override this to FALSE at the BuildConfig level
            // to satisfy the Play Store's anti-scraping posture. Until
            // that lands, this default is ON for all builds.
            sourceRoyalRoadEnabled = prefs[Keys.SOURCE_ROYALROAD_ENABLED] ?: true,
            sourceGitHubEnabled = prefs[Keys.SOURCE_GITHUB_ENABLED] ?: false,
            sourceMemPalaceEnabled = prefs[Keys.SOURCE_MEMPALACE_ENABLED] ?: false,
            sourceRssEnabled = prefs[Keys.SOURCE_RSS_ENABLED] ?: true,
            sourceEpubEnabled = prefs[Keys.SOURCE_EPUB_ENABLED] ?: false,
            sourceOutlineEnabled = prefs[Keys.SOURCE_OUTLINE_ENABLED] ?: false,
            sourceGutenbergEnabled = prefs[Keys.SOURCE_GUTENBERG_ENABLED] ?: true,
            // #381 — AO3 defaults OFF on fresh installs (Explicit-
            // content gate; opt-in from Settings → Library & Sync).
            sourceAo3Enabled = prefs[Keys.SOURCE_AO3_ENABLED] ?: false,
            sleepShakeToExtendEnabled = prefs[Keys.SLEEP_SHAKE_TO_EXTEND_ENABLED] ?: true,
            showDebugOverlay = prefs[Keys.SHOW_DEBUG_OVERLAY] ?: false,
            azure = run {
                // #182 — read the encrypted snapshot imperatively each
                // emission. The azureTick flow above guarantees a fresh
                // re-emission after every setter; combining the bare
                // SharedPreferences-backed creds in directly would need
                // a Flow we don't currently expose from :source-azure.
                val regionId = azureCreds.regionId()
                UiAzureConfig(
                    key = azureCreds.key().orEmpty(),
                    regionId = regionId,
                    regionDisplayName = AzureRegion.byId(regionId)?.displayName ?: regionId,
                )
            },
            // PR-6 (#185) — Azure offline-fallback toggle + voice id.
            // Read from the unencrypted prefs store (no secret here —
            // just user UX preference + a public voice id). The
            // AzureVoiceEngine's lastError observer in EnginePlayer
            // reads these at the moment of failure; no need for a
            // dedicated tick flow.
            azureFallbackEnabled = prefs[Keys.AZURE_FALLBACK_ENABLED] ?: false,
            azureFallbackVoiceId = prefs[Keys.AZURE_FALLBACK_VOICE_ID],
            parallelSynthInstances = readParallelSynthInstances(prefs),
            synthThreadsPerInstance = (prefs[Keys.SYNTH_THREADS_PER_INSTANCE] ?: 0)
                .coerceIn(0, 8),
            ai = UiAiSettings(
                provider = prefs[Keys.AI_PROVIDER]
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { UiLlmProvider.valueOf(it) }.getOrNull() },
                claudeModel = prefs[Keys.AI_CLAUDE_MODEL] ?: "claude-haiku-4.5",
                claudeKeyConfigured = llmCreds.hasClaudeKey,
                openAiModel = prefs[Keys.AI_OPENAI_MODEL] ?: "gpt-4o-mini",
                openAiKeyConfigured = llmCreds.hasOpenAiKey,
                // Issue #294 — JP's LAN address shouldn't be baked into
                // every fresh install. Default empty; the UI surfaces a
                // placeholder.
                ollamaBaseUrl = prefs[Keys.AI_OLLAMA_BASE_URL] ?: "",
                // Issue #294 — llama3.2:3b actually fits on phone-class
                // hardware; 3.3 (70B) doesn't run locally for most users
                // even when they have ollama on a LAN host.
                ollamaModel = prefs[Keys.AI_OLLAMA_MODEL] ?: "llama3.2:3b",
                vertexModel = prefs[Keys.AI_VERTEX_MODEL] ?: "gemini-2.5-flash",
                vertexKeyConfigured = llmCreds.hasVertexKey,
                foundryEndpoint = prefs[Keys.AI_FOUNDRY_ENDPOINT] ?: "",
                foundryDeployment = prefs[Keys.AI_FOUNDRY_DEPLOYMENT] ?: "",
                foundryServerless = prefs[Keys.AI_FOUNDRY_SERVERLESS] ?: false,
                foundryKeyConfigured = llmCreds.hasFoundryKey,
                bedrockAccessKeyConfigured = !llmCreds.bedrockAccessKey().isNullOrBlank(),
                bedrockSecretKeyConfigured = !llmCreds.bedrockSecretKey().isNullOrBlank(),
                bedrockRegion = prefs[Keys.AI_BEDROCK_REGION] ?: "us-east-1",
                bedrockModel = prefs[Keys.AI_BEDROCK_MODEL] ?: "claude-haiku-4.5",
                teamsSignedIn = teamsSession is TeamsSession.SignedIn,
                teamsScopes = (teamsSession as? TeamsSession.SignedIn)?.scopes
                    ?: llmCreds.teamsScopes().orEmpty(),
                privacyAcknowledged = prefs[Keys.AI_PRIVACY_ACK] ?: false,
                // Issue #294 — privacy-first: chapter text is the user's
                // content, and AI grounding is opt-in by default. Recap
                // and chat fall back to title + current-sentence
                // grounding (still useful) until the user opts in. This
                // is a behavioural change worth flagging in release
                // notes.
                sendChapterTextEnabled = prefs[Keys.AI_SEND_CHAPTER_TEXT] ?: false,
                chatGrounding = UiChatGrounding(
                    includeChapterTitle = prefs[Keys.AI_CHAT_GROUND_CHAPTER_TITLE] ?: true,
                    // Issue #294 — current-sentence grounding is ~50
                    // tokens; tiny cost for outsized context gain. Ship
                    // ON by default so first-launch chats are useful.
                    includeCurrentSentence = prefs[Keys.AI_CHAT_GROUND_CURRENT_SENTENCE] ?: true,
                    includeEntireChapter = prefs[Keys.AI_CHAT_GROUND_ENTIRE_CHAPTER] ?: false,
                    includeEntireBookSoFar = prefs[Keys.AI_CHAT_GROUND_ENTIRE_BOOK] ?: false,
                ),
            ),
            sigil = Sigil.current.let {
                UiSigil(
                    name = it.name,
                    realm = it.realm,
                    hash = it.hash,
                    branch = it.branch,
                    dirty = it.dirty,
                    built = it.built,
                    repo = it.repo,
                    versionName = it.versionName,
                )
            },
        )
    }

    override suspend fun setTheme(override: ThemeOverride) {
        store.edit { it[Keys.THEME_OVERRIDE] = override.name }
    }

    override suspend fun setDefaultSpeed(speed: Float) {
        // #195 — per-voice override when a voice is active; otherwise
        // fall back to the global default for fresh installs that
        // haven't picked a voice yet. Pre-#195 callers never wrote to
        // the global key when a voice was selected, so behavior of
        // existing voices migrates cleanly: their previous global
        // value is the implicit fallback until the user tweaks the
        // slider with that voice active.
        val coerced = speed.coerceIn(0.5f, 3.0f)
        store.edit { prefs ->
            val voiceId = prefs[Keys.DEFAULT_VOICE_ID]
            if (voiceId != null) {
                val map = decodeVoiceFloatMap(prefs[Keys.VOICE_SPEED_OVERRIDES]).toMutableMap()
                map[voiceId] = coerced
                prefs[Keys.VOICE_SPEED_OVERRIDES] = encodeVoiceFloatMap(map)
            } else {
                prefs[Keys.DEFAULT_SPEED] = coerced
            }
        }
    }

    override suspend fun setDefaultPitch(pitch: Float) {
        // #195 — same dual-write story as setDefaultSpeed, with the
        // tightened pitch band from Thalia's VoxSherpa P0 #1
        // (2026-05-08): below ~0.7 Sonic introduces audible artifacts
        // on Piper-medium voices, and above 1.4 is unlistenable.
        val coerced = pitch.coerceIn(0.6f, 1.4f)
        store.edit { prefs ->
            val voiceId = prefs[Keys.DEFAULT_VOICE_ID]
            if (voiceId != null) {
                val map = decodeVoiceFloatMap(prefs[Keys.VOICE_PITCH_OVERRIDES]).toMutableMap()
                map[voiceId] = coerced
                prefs[Keys.VOICE_PITCH_OVERRIDES] = encodeVoiceFloatMap(map)
            } else {
                prefs[Keys.DEFAULT_PITCH] = coerced
            }
        }
    }

    override suspend fun setDefaultVoice(voiceId: String?) {
        store.edit {
            if (voiceId == null) it.remove(Keys.DEFAULT_VOICE_ID)
            else it[Keys.DEFAULT_VOICE_ID] = voiceId
        }
    }

    override suspend fun setDownloadOnWifiOnly(enabled: Boolean) {
        store.edit { it[Keys.DOWNLOAD_WIFI_ONLY] = enabled }
    }

    override suspend fun setPollIntervalHours(hours: Int) {
        store.edit { it[Keys.POLL_INTERVAL_HOURS] = hours.coerceIn(1, 24) }
    }

    override suspend fun setPunctuationPauseMultiplier(multiplier: Float) {
        store.edit {
            it[Keys.PUNCTUATION_PAUSE_MULTIPLIER] = multiplier
                .coerceIn(PUNCTUATION_PAUSE_MIN_MULTIPLIER, PUNCTUATION_PAUSE_MAX_MULTIPLIER)
        }
    }

    override suspend fun setPlaybackBufferChunks(chunks: Int) {
        store.edit {
            it[Keys.PLAYBACK_BUFFER_CHUNKS] = chunks.coerceIn(BUFFER_MIN_CHUNKS, BUFFER_MAX_CHUNKS)
        }
    }

    override suspend fun setWarmupWait(enabled: Boolean) {
        store.edit { it[Keys.WARMUP_WAIT] = enabled }
    }

    override suspend fun setCatchupPause(enabled: Boolean) {
        store.edit { it[Keys.CATCHUP_PAUSE] = enabled }
    }

    override suspend fun setVoiceSteady(enabled: Boolean) {
        store.edit { it[Keys.VOICE_STEADY] = enabled }
    }

    // --- PlaybackBufferConfig (consumed by core-playback's EnginePlayer) ---

    override val playbackBufferChunks: Flow<Int> = store.data.map { prefs ->
        (prefs[Keys.PLAYBACK_BUFFER_CHUNKS] ?: BUFFER_DEFAULT_CHUNKS)
            .coerceIn(BUFFER_MIN_CHUNKS, BUFFER_MAX_CHUNKS)
    }

    override suspend fun currentBufferChunks(): Int = playbackBufferChunks.first()

    // --- PlaybackModeConfig (issue #98) ---

    override val warmupWait: Flow<Boolean> = store.data.map { it[Keys.WARMUP_WAIT] ?: false }
    override val catchupPause: Flow<Boolean> = store.data.map { it[Keys.CATCHUP_PAUSE] ?: true }
    override suspend fun currentWarmupWait(): Boolean = warmupWait.first()
    override suspend fun currentCatchupPause(): Boolean = catchupPause.first()

    // --- VoiceTuningConfig (issue #85, consumed by core-playback's EnginePlayer) ---

    override val voiceSteady: Flow<Boolean> = store.data.map { it[Keys.VOICE_STEADY] ?: true }
    override suspend fun currentVoiceSteady(): Boolean = voiceSteady.first()

    // --- AzureFallbackConfig (#185, PR-6) ---
    // Surfaced through the same DataStore-backed flow as the other
    // playback config interfaces; EnginePlayer collects it in
    // observeAzureFallbackConfig() and snapshots into a volatile pair
    // so the synth-failure path can read without suspending.
    override val state: Flow<AzureFallbackState> = store.data.map { prefs ->
        AzureFallbackState(
            enabled = prefs[Keys.AZURE_FALLBACK_ENABLED] ?: false,
            fallbackVoiceId = prefs[Keys.AZURE_FALLBACK_VOICE_ID],
        )
    }

    override suspend fun currentAzureFallback(): AzureFallbackState = state.first()

    // --- ParallelSynthConfig (#88, Tier 3) ---
    /**
     * Read the parallel-synth instance count, migrating the pre-slider
     * Boolean key to the new Int key on the fly. The Int key wins when
     * present; the Boolean key is the legacy fallback (true→2, false→1).
     * Coerced to the supported [1..8] range so a corrupt persist or a
     * future-format value doesn't blow up the engine wiring.
     */
    private fun readParallelSynthInstances(prefs: Preferences): Int {
        val explicit = prefs[Keys.PARALLEL_SYNTH_INSTANCES]
        if (explicit != null) return explicit.coerceIn(1, 8)
        // Pre-slider migration path. Once the user touches the slider
        // we'll persist the Int key; the boolean key stays around as
        // a no-op (untouched on disk) until the next clear-data event.
        val legacy = prefs[Keys.EXPERIMENTAL_PARALLEL_SYNTH] ?: false
        return if (legacy) 2 else 1
    }

    override val parallelSynthState: Flow<ParallelSynthState> = store.data.map { prefs ->
        ParallelSynthState(
            instances = readParallelSynthInstances(prefs),
            threadsPerInstance = (prefs[Keys.SYNTH_THREADS_PER_INSTANCE] ?: 0)
                .coerceIn(0, 8),
        )
    }

    override suspend fun currentParallelSynthState(): ParallelSynthState =
        parallelSynthState.first()

    // --- PlaybackResumePolicyConfig (#90) ---
    override val lastWasPlaying: Flow<Boolean> = store.data.map { prefs ->
        prefs[Keys.LAST_WAS_PLAYING] ?: true
    }

    override suspend fun currentLastWasPlaying(): Boolean = lastWasPlaying.first()

    override suspend fun setLastWasPlaying(playing: Boolean) {
        store.edit { it[Keys.LAST_WAS_PLAYING] = playing }
    }

    override suspend fun signIn() {
        // Just flips the persisted UI flag; the cookie capture is owned by
        // AuthViewModel (it writes to AuthRepository + SessionHydrator and
        // calls signIn() here last). Keeping signIn idempotent so callers
        // can flip it without side effects on the auth store.
        store.edit { it[Keys.SIGNED_IN] = true }
    }

    override suspend fun signOut() {
        // Tear down all three stores: the encrypted cookie header in
        // AuthRepository, the live OkHttp jar via SessionHydrator, and the
        // DataStore flag that drives the Settings UI.
        auth.clearSession()
        hydrator.clear()
        store.edit { it[Keys.SIGNED_IN] = false }
    }

    // --- Memory Palace (#79) ---

    override suspend fun setPalaceHost(host: String) {
        palaceConfig.setHost(host)
    }

    override suspend fun setPalaceApiKey(apiKey: String) {
        palaceConfig.setApiKey(apiKey)
    }

    override suspend fun clearPalaceConfig() {
        palaceConfig.clear()
    }

    override suspend fun testPalaceConnection(): PalaceProbeResult {
        if (!palaceConfig.current().isConfigured) return PalaceProbeResult.NotConfigured
        return when (val r = palaceApi.health()) {
            is PalaceDaemonResult.Success -> PalaceProbeResult.Reachable(
                daemonVersion = r.value.version ?: "unknown",
            )
            is PalaceDaemonResult.HostRejected -> PalaceProbeResult.Unreachable(
                "Host '${r.host}' is not on the home network.",
            )
            is PalaceDaemonResult.Unauthorized -> PalaceProbeResult.Unreachable(
                "API key rejected by daemon.",
            )
            is PalaceDaemonResult.NotReachable -> PalaceProbeResult.Unreachable(
                r.cause.message ?: "Could not reach daemon.",
            )
            is PalaceDaemonResult.Degraded -> PalaceProbeResult.Unreachable(
                "Palace is rebuilding — try again shortly.",
            )
            is PalaceDaemonResult.HttpError -> PalaceProbeResult.Unreachable(
                "Daemon returned ${r.code}: ${r.message}",
            )
            is PalaceDaemonResult.NotFound -> PalaceProbeResult.Unreachable(
                "Daemon /health endpoint not found — is this palace-daemon?",
            )
            is PalaceDaemonResult.ParseError -> PalaceProbeResult.Unreachable(
                "Malformed health response — is this palace-daemon?",
            )
        }
    }

    // ── AI settings (issue #81) ────────────────────────────────────

    override suspend fun setAiProvider(provider: UiLlmProvider?) {
        store.edit {
            if (provider == null) it.remove(Keys.AI_PROVIDER)
            else it[Keys.AI_PROVIDER] = provider.name
        }
    }

    override suspend fun setClaudeApiKey(key: String?) {
        if (key == null) llmCreds.clearClaudeApiKey()
        else llmCreds.setClaudeApiKey(key)
    }

    override suspend fun setClaudeModel(model: String) {
        store.edit { it[Keys.AI_CLAUDE_MODEL] = model }
    }

    override suspend fun setOpenAiApiKey(key: String?) {
        if (key == null) llmCreds.clearOpenAiApiKey()
        else llmCreds.setOpenAiApiKey(key)
    }

    override suspend fun setOpenAiModel(model: String) {
        store.edit { it[Keys.AI_OPENAI_MODEL] = model }
    }

    override suspend fun setOllamaBaseUrl(url: String) {
        store.edit { it[Keys.AI_OLLAMA_BASE_URL] = url }
    }

    override suspend fun setOllamaModel(model: String) {
        store.edit { it[Keys.AI_OLLAMA_MODEL] = model }
    }

    override suspend fun setVertexApiKey(key: String?) {
        if (key == null) llmCreds.clearVertexApiKey()
        else llmCreds.setVertexApiKey(key)
    }

    override suspend fun setVertexModel(model: String) {
        store.edit { it[Keys.AI_VERTEX_MODEL] = model }
    }

    override suspend fun setFoundryApiKey(key: String?) {
        if (key == null) llmCreds.clearFoundryApiKey()
        else llmCreds.setFoundryApiKey(key)
    }

    override suspend fun setFoundryEndpoint(url: String) {
        store.edit { it[Keys.AI_FOUNDRY_ENDPOINT] = url }
    }

    override suspend fun setFoundryDeployment(deployment: String) {
        store.edit { it[Keys.AI_FOUNDRY_DEPLOYMENT] = deployment }
    }

    override suspend fun setFoundryServerless(serverless: Boolean) {
        store.edit { it[Keys.AI_FOUNDRY_SERVERLESS] = serverless }
    }

    override suspend fun setBedrockAccessKey(key: String?) {
        if (key.isNullOrBlank()) {
            // Clear access only — leave secret in place. The "Forget all"
            // path goes through resetAiSettings → llmCreds.clearAll().
            llmCreds.setBedrockKeys(
                access = "",
                secret = llmCreds.bedrockSecretKey() ?: "",
            )
            // Empty access = treated as not-configured by the UI, but we
            // need a single "remove just access" path; fall back to
            // clearing both if secret is also empty.
            if (llmCreds.bedrockSecretKey().isNullOrBlank()) {
                llmCreds.clearBedrockKeys()
            }
        } else {
            llmCreds.setBedrockKeys(
                access = key,
                secret = llmCreds.bedrockSecretKey() ?: "",
            )
        }
    }

    override suspend fun setBedrockSecretKey(key: String?) {
        if (key.isNullOrBlank()) {
            llmCreds.setBedrockKeys(
                access = llmCreds.bedrockAccessKey() ?: "",
                secret = "",
            )
            if (llmCreds.bedrockAccessKey().isNullOrBlank()) {
                llmCreds.clearBedrockKeys()
            }
        } else {
            llmCreds.setBedrockKeys(
                access = llmCreds.bedrockAccessKey() ?: "",
                secret = key,
            )
        }
    }

    override suspend fun setBedrockRegion(region: String) {
        store.edit { it[Keys.AI_BEDROCK_REGION] = region }
    }

    override suspend fun setBedrockModel(model: String) {
        store.edit { it[Keys.AI_BEDROCK_MODEL] = model }
    }

    override suspend fun setSendChapterTextEnabled(enabled: Boolean) {
        store.edit { it[Keys.AI_SEND_CHAPTER_TEXT] = enabled }
    }

    // ── Chat grounding (#212) ──────────────────────────────────────

    override suspend fun setChatGroundChapterTitle(enabled: Boolean) {
        store.edit { it[Keys.AI_CHAT_GROUND_CHAPTER_TITLE] = enabled }
    }

    override suspend fun setChatGroundCurrentSentence(enabled: Boolean) {
        store.edit { it[Keys.AI_CHAT_GROUND_CURRENT_SENTENCE] = enabled }
    }

    override suspend fun setChatGroundEntireChapter(enabled: Boolean) {
        store.edit { it[Keys.AI_CHAT_GROUND_ENTIRE_CHAPTER] = enabled }
    }

    override suspend fun setChatGroundEntireBookSoFar(enabled: Boolean) {
        store.edit { it[Keys.AI_CHAT_GROUND_ENTIRE_BOOK] = enabled }
    }

    override suspend fun acknowledgeAiPrivacy() {
        store.edit { it[Keys.AI_PRIVACY_ACK] = true }
    }

    override suspend fun signOutTeams() {
        // Local sign-out — wipes the bearer + refresh + scope cache.
        // Remote revoke at console.anthropic.com requires the
        // client_secret we don't have (public-client posture). The
        // Settings UI surfaces a deep-link to claude.ai if the user
        // wants to fully revoke the session there. Going through the
        // repo (not llmCreds directly) keeps the in-memory StateFlow
        // in sync, which is what the Settings UI subscribes to.
        teamsAuth.clearSession()
    }

    override suspend fun resetAiSettings() {
        store.edit {
            it.remove(Keys.AI_PROVIDER)
            it.remove(Keys.AI_CLAUDE_MODEL)
            it.remove(Keys.AI_OPENAI_MODEL)
            it.remove(Keys.AI_OLLAMA_BASE_URL)
            it.remove(Keys.AI_OLLAMA_MODEL)
            it.remove(Keys.AI_VERTEX_MODEL)
            it.remove(Keys.AI_FOUNDRY_ENDPOINT)
            it.remove(Keys.AI_FOUNDRY_DEPLOYMENT)
            it.remove(Keys.AI_FOUNDRY_SERVERLESS)
            it.remove(Keys.AI_BEDROCK_REGION)
            it.remove(Keys.AI_BEDROCK_MODEL)
            it.remove(Keys.AI_PRIVACY_ACK)
            it.remove(Keys.AI_SEND_CHAPTER_TEXT)
            it.remove(Keys.AI_CHAT_GROUND_CHAPTER_TITLE)
            it.remove(Keys.AI_CHAT_GROUND_CURRENT_SENTENCE)
            it.remove(Keys.AI_CHAT_GROUND_ENTIRE_CHAPTER)
            it.remove(Keys.AI_CHAT_GROUND_ENTIRE_BOOK)
        }
        llmCreds.clearAll()
        // llmCreds.clearAll() wipes the Teams encrypted-prefs entries;
        // refresh the in-memory session flow so the UI flips to
        // SignedOut without waiting for a separate emission.
        teamsAuth.refreshFromStore()
    }

    // ── GitHub OAuth (#91) ─────────────────────────────────────────

    override suspend fun signOutGitHub() {
        // Remote revoke at github.com requires the client_secret we don't
        // have (public client model — see GitHubAuthConfig kdoc). Local
        // sign-out always works and clears the encrypted token + login +
        // scopes from prefs. The Settings UI surfaces the deep-link to
        // github.com/settings/applications for users who want full revoke.
        githubAuth.clearSession()
    }

    override suspend fun setGitHubPrivateReposEnabled(enabled: Boolean) {
        store.edit { it[Keys.GITHUB_PRIVATE_REPOS_ENABLED] = enabled }
    }

    override suspend fun setSourceRoyalRoadEnabled(enabled: Boolean) {
        store.edit { it[Keys.SOURCE_ROYALROAD_ENABLED] = enabled }
    }

    override suspend fun setSourceGitHubEnabled(enabled: Boolean) {
        store.edit { it[Keys.SOURCE_GITHUB_ENABLED] = enabled }
    }

    override suspend fun setSourceMemPalaceEnabled(enabled: Boolean) {
        store.edit { it[Keys.SOURCE_MEMPALACE_ENABLED] = enabled }
    }

    override suspend fun setSourceRssEnabled(enabled: Boolean) {
        store.edit { it[Keys.SOURCE_RSS_ENABLED] = enabled }
    }

    override suspend fun addRssFeed(url: String) = rssConfig.addFeed(url)
    override suspend fun removeRssFeed(fictionId: String) = rssConfig.removeFeed(fictionId)
    override suspend fun removeRssFeedByUrl(url: String) {
        // Look up the fictionId for the URL via the canonical hash and
        // delete by id. Keeps the UI free of source-rss internals.
        rssConfig.removeFeed(
            `in`.jphe.storyvox.source.rss.config.fictionIdForFeedUrl(url),
        )
    }
    override val rssSubscriptions: kotlinx.coroutines.flow.Flow<List<String>> =
        rssConfig.subscriptions.map { subs -> subs.map { it.url } }

    override suspend fun setSourceEpubEnabled(enabled: Boolean) {
        store.edit { it[Keys.SOURCE_EPUB_ENABLED] = enabled }
    }
    override val epubFolderUri: kotlinx.coroutines.flow.Flow<String?> = epubConfig.folderUriString
    override suspend fun setEpubFolderUri(uri: String) = epubConfig.setFolder(uri)
    override suspend fun clearEpubFolder() = epubConfig.clearFolder()

    override suspend fun setSourceOutlineEnabled(enabled: Boolean) {
        store.edit { it[Keys.SOURCE_OUTLINE_ENABLED] = enabled }
    }
    override suspend fun setSourceGutenbergEnabled(enabled: Boolean) {
        store.edit { it[Keys.SOURCE_GUTENBERG_ENABLED] = enabled }
    }
    override suspend fun setSourceAo3Enabled(enabled: Boolean) {
        store.edit { it[Keys.SOURCE_AO3_ENABLED] = enabled }
    }
    override val outlineHost: kotlinx.coroutines.flow.Flow<String> =
        outlineConfig.state.map { it.host }
    override suspend fun setOutlineHost(host: String) = outlineConfig.setHost(host)
    override suspend fun setOutlineApiKey(apiKey: String) = outlineConfig.setApiKey(apiKey)
    override suspend fun clearOutlineConfig() = outlineConfig.clear()

    /** #246 — bridge to SuggestedFeedsRegistry. The fallback list
     *  passed in is the baked-in seed; the registry emits it
     *  immediately, then re-emits with the remote list once the
     *  fetch resolves. */
    override val suggestedRssFeeds: kotlinx.coroutines.flow.Flow<List<`in`.jphe.storyvox.feature.api.SuggestedFeed>> =
        suggestedFeedsRegistry.observe(
            // Seed list lives in feature/settings module; the impl
            // reaches across to read it. If the feature dependency
            // direction ever inverts, the seed moves to :app instead.
            fallback = `in`.jphe.storyvox.feature.settings.BAKED_IN_SUGGESTED_FEEDS,
        )

    override suspend fun setSleepShakeToExtendEnabled(enabled: Boolean) {
        store.edit { it[Keys.SLEEP_SHAKE_TO_EXTEND_ENABLED] = enabled }
    }

    override suspend fun setShowDebugOverlay(enabled: Boolean) {
        store.edit { it[Keys.SHOW_DEBUG_OVERLAY] = enabled }
    }

    // ── Azure Speech Services BYOK (#182, PR-3) ────────────────────

    override suspend fun setAzureKey(key: String?) {
        // Don't also wipe the region on a key-only clear — the user may
        // want to re-paste the same region's key. clearAzureCredentials()
        // is the explicit wipe-both surface. The bug fixed in v0.4.84:
        // pre-fix this called azureCreds.clear() (which wiped both),
        // contradicting the comment and silently bouncing region back
        // to the default mid-paste, so a CTRL+A+DELETE during a UI
        // re-key flow lost the user's region selection.
        if (key.isNullOrBlank()) azureCreds.clearKey() else azureCreds.setKey(key.trim())
        azureTick.value = azureTick.value + 1
        // Live roster needs a refresh: a new key may unlock a different
        // set of voices than the previous one (different Azure resource,
        // different SKU, different regional rollout). Async so the
        // settings setter returns immediately for snappy UI feedback.
        azureRoster.refreshAsync()
    }

    override suspend fun setAzureRegion(regionId: String) {
        azureCreds.setRegion(regionId)
        azureTick.value = azureTick.value + 1
        // Region change → new roster. eastus carries Dragon HD, westus
        // doesn't, etc. Async refresh keeps the picker live.
        azureRoster.refreshAsync()
    }

    override suspend fun clearAzureCredentials() {
        azureCreds.clear()
        azureTick.value = azureTick.value + 1
        // Force the roster to re-evaluate — refresh() will see no key
        // and clear the cached voice list, collapsing the picker's
        // Azure section back to "Configure key" empty state.
        azureRoster.refreshAsync()
    }

    override suspend fun setAzureFallbackEnabled(enabled: Boolean) {
        store.edit { it[Keys.AZURE_FALLBACK_ENABLED] = enabled }
    }

    override suspend fun setAzureFallbackVoiceId(voiceId: String?) {
        store.edit {
            if (voiceId == null) it.remove(Keys.AZURE_FALLBACK_VOICE_ID)
            else it[Keys.AZURE_FALLBACK_VOICE_ID] = voiceId
        }
    }

    override suspend fun setParallelSynthInstances(count: Int) {
        val coerced = count.coerceIn(1, 8)
        store.edit {
            it[Keys.PARALLEL_SYNTH_INSTANCES] = coerced
            // Legacy boolean key — keep it in sync so a user who
            // downgrades to a pre-slider build sees a sensible value.
            // (true if count >= 2, false otherwise.)
            it[Keys.EXPERIMENTAL_PARALLEL_SYNTH] = coerced >= 2
        }
    }

    override suspend fun setSynthThreadsPerInstance(count: Int) {
        store.edit { it[Keys.SYNTH_THREADS_PER_INSTANCE] = count.coerceIn(0, 8) }
    }

    override suspend fun testAzureConnection(): AzureProbeResult {
        if (!azureCreds.isConfigured) return AzureProbeResult.NotConfigured
        // voicesList() is blocking OkHttp work — push to IO so the
        // suspend-fun signature isn't a lie. Settings VM already
        // launches this on viewModelScope, but a blocking JVM thread
        // off the main dispatcher is the wrong shape for a "suspend"
        // contract — withContext(IO) makes it cancellable + correct.
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val count = azureClient.voicesList()
                AzureProbeResult.Reachable(voiceCount = count)
            } catch (e: AzureError.AuthFailed) {
                AzureProbeResult.AuthFailed(
                    e.message ?: "Azure rejected the subscription key.",
                )
            } catch (e: AzureError.NetworkError) {
                AzureProbeResult.Unreachable(
                    e.message ?: "Network error reaching Azure.",
                )
            } catch (e: AzureError) {
                AzureProbeResult.Unreachable(
                    e.message ?: "Azure returned an unexpected error.",
                )
            }
        }
    }

    /**
     * Issue #203 — [GitHubScopePreferences] surface for the
     * `GitHubSignInViewModel`. Read at the moment Device Flow starts so
     * a freshly-flipped toggle takes effect on the next sign-in
     * attempt. Defaults false to match the least-privilege baseline.
     */
    override suspend fun privateReposEnabled(): Boolean =
        store.data.map { it[Keys.GITHUB_PRIVATE_REPOS_ENABLED] ?: false }.first()

    // ── PronunciationDictRepository (issue #135) ───────────────────

    /**
     * Live flow of the user's pronunciation dictionary. Decodes the
     * stored JSON on every emission; an empty / missing / unparseable
     * value falls back to [PronunciationDict.EMPTY] so the engine
     * pipeline always has a usable substitution lambda.
     *
     * Decode failures are silent on purpose — a corrupted blob (e.g.
     * a future-format payload an older binary can't read) shouldn't
     * crash the player; the user sees their dictionary "reset" and
     * can re-enter it. The corrupt blob stays on disk until the next
     * write (we don't actively wipe it on read), which preserves the
     * forward-roll case where the user downgrades, sees the dict
     * empty, then upgrades and gets it back.
     */
    override val dict: Flow<PronunciationDict> = store.data.map { prefs ->
        decodePronunciationDictJson(prefs[Keys.PRONUNCIATION_DICT])
    }

    override suspend fun current(): PronunciationDict = dict.first()

    override suspend fun add(entry: PronunciationEntry) {
        store.edit { prefs ->
            val current = decodePronunciationDictJson(prefs[Keys.PRONUNCIATION_DICT])
            val next = current.copy(entries = current.entries + entry)
            prefs[Keys.PRONUNCIATION_DICT] = encodePronunciationDictJson(next)
        }
    }

    override suspend fun update(index: Int, entry: PronunciationEntry) {
        store.edit { prefs ->
            val current = decodePronunciationDictJson(prefs[Keys.PRONUNCIATION_DICT])
            if (index !in current.entries.indices) return@edit
            val newList = current.entries.toMutableList().apply { this[index] = entry }
            prefs[Keys.PRONUNCIATION_DICT] = encodePronunciationDictJson(current.copy(entries = newList))
        }
    }

    override suspend fun delete(index: Int) {
        store.edit { prefs ->
            val current = decodePronunciationDictJson(prefs[Keys.PRONUNCIATION_DICT])
            if (index !in current.entries.indices) return@edit
            val newList = current.entries.toMutableList().apply { removeAt(index) }
            prefs[Keys.PRONUNCIATION_DICT] = encodePronunciationDictJson(current.copy(entries = newList))
        }
    }

    override suspend fun replaceAll(dict: PronunciationDict) {
        store.edit { it[Keys.PRONUNCIATION_DICT] = encodePronunciationDictJson(dict) }
    }

    // ── LlmConfigProvider — bridge to :core-llm ────────────────────

    override val config: Flow<LlmConfig> = store.data.map { prefs ->
        LlmConfig(
            provider = prefs[Keys.AI_PROVIDER]
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { ProviderId.valueOf(it) }.getOrNull() },
            claudeModel = prefs[Keys.AI_CLAUDE_MODEL] ?: "claude-haiku-4.5",
            openAiModel = prefs[Keys.AI_OPENAI_MODEL] ?: "gpt-4o-mini",
            // Issue #294 — mirror the UiSettings fallbacks at the
            // LlmConfig export site so both surfaces report the same
            // first-time defaults.
            ollamaBaseUrl = prefs[Keys.AI_OLLAMA_BASE_URL] ?: "",
            ollamaModel = prefs[Keys.AI_OLLAMA_MODEL] ?: "llama3.2:3b",
            vertexModel = prefs[Keys.AI_VERTEX_MODEL] ?: "gemini-2.5-flash",
            foundryEndpoint = prefs[Keys.AI_FOUNDRY_ENDPOINT] ?: "",
            foundryDeployment = prefs[Keys.AI_FOUNDRY_DEPLOYMENT] ?: "",
            foundryServerless = prefs[Keys.AI_FOUNDRY_SERVERLESS] ?: false,
            bedrockRegion = prefs[Keys.AI_BEDROCK_REGION] ?: "us-east-1",
            bedrockModel = prefs[Keys.AI_BEDROCK_MODEL] ?: "claude-haiku-4.5",
            privacyAcknowledged = prefs[Keys.AI_PRIVACY_ACK] ?: false,
            sendChapterTextEnabled = prefs[Keys.AI_SEND_CHAPTER_TEXT] ?: false,
        )
    }

    override suspend fun setConfig(config: LlmConfig) {
        // Bulk write — used by tests / one-shot reset paths. The
        // narrow setters above are the day-to-day path.
        val providerName: String? = config.provider?.name
        store.edit { p ->
            if (providerName == null) p.remove(Keys.AI_PROVIDER)
            else p[Keys.AI_PROVIDER] = providerName
            p[Keys.AI_CLAUDE_MODEL] = config.claudeModel
            p[Keys.AI_OPENAI_MODEL] = config.openAiModel
            p[Keys.AI_OLLAMA_BASE_URL] = config.ollamaBaseUrl
            p[Keys.AI_OLLAMA_MODEL] = config.ollamaModel
            p[Keys.AI_VERTEX_MODEL] = config.vertexModel
            p[Keys.AI_FOUNDRY_ENDPOINT] = config.foundryEndpoint
            p[Keys.AI_FOUNDRY_DEPLOYMENT] = config.foundryDeployment
            p[Keys.AI_FOUNDRY_SERVERLESS] = config.foundryServerless
            p[Keys.AI_BEDROCK_REGION] = config.bedrockRegion
            p[Keys.AI_BEDROCK_MODEL] = config.bedrockModel
            p[Keys.AI_PRIVACY_ACK] = config.privacyAcknowledged
            p[Keys.AI_SEND_CHAPTER_TEXT] = config.sendChapterTextEnabled
        }
    }

    // ── Calliope (v0.5.00) milestone celebration ──────────────────
    /** [Milestone.isV0500OrLater] is a process-lifetime constant —
     *  it's pinned to the build's VERSION_NAME, which doesn't change
     *  while the app is running. We still emit through a Flow so the
     *  UI's collect cadence matches the persisted-flag stream and the
     *  dialog's gating is a single combine source. */
    override val milestoneState: Flow<`in`.jphe.storyvox.feature.api.MilestoneState> =
        store.data.map { prefs ->
            `in`.jphe.storyvox.feature.api.MilestoneState(
                qualifies = `in`.jphe.storyvox.sigil.Milestone.isV0500OrLater,
                dialogSeen = prefs[Keys.V0500_MILESTONE_SEEN] ?: false,
                confettiShown = prefs[Keys.V0500_CONFETTI_SHOWN] ?: false,
            )
        }

    override suspend fun markMilestoneDialogSeen() {
        store.edit { it[Keys.V0500_MILESTONE_SEEN] = true }
    }

    override suspend fun markMilestoneConfettiShown() {
        store.edit { it[Keys.V0500_CONFETTI_SHOWN] = true }
    }
}

/**
 * Source-internal [GitHubSession] → public [UiGitHubAuthState] projection.
 * Strips the token before crossing into the feature/UI layer — Settings
 * never needs the secret, only "are you signed in, who as." Issue #91.
 */
private fun GitHubSession.toUi(): UiGitHubAuthState = when (this) {
    is GitHubSession.Anonymous -> UiGitHubAuthState.Anonymous
    is GitHubSession.Authenticated -> UiGitHubAuthState.SignedIn(
        login = login,
        scopes = scopes,
    )
    is GitHubSession.Expired -> UiGitHubAuthState.Expired
}
