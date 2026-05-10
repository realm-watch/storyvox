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
import `in`.jphe.storyvox.data.repository.playback.PlaybackBufferConfig
import `in`.jphe.storyvox.data.repository.playback.PlaybackModeConfig
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
import `in`.jphe.storyvox.feature.api.PalaceProbeResult
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.feature.api.UiAiSettings
import `in`.jphe.storyvox.feature.api.UiChatGrounding
import `in`.jphe.storyvox.feature.api.UiGitHubAuthState
import `in`.jphe.storyvox.feature.api.UiLlmProvider
import `in`.jphe.storyvox.feature.api.UiPalaceConfig
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
    produceMigrations = { _ -> listOf(PunctuationPauseEnumToMultiplierMigration) },
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

private object Keys {
    val DEFAULT_SPEED = floatPreferencesKey("pref_default_speed")
    val DEFAULT_PITCH = floatPreferencesKey("pref_default_pitch")
    val DEFAULT_VOICE_ID = stringPreferencesKey("pref_default_voice_id")
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

    // ── Sleep timer shake-to-extend (issue #150) ───────────────────
    val SLEEP_SHAKE_TO_EXTEND_ENABLED = booleanPreferencesKey("pref_sleep_shake_to_extend_enabled")

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
) : SettingsRepositoryUi,
    PlaybackBufferConfig,
    PlaybackModeConfig,
    VoiceTuningConfig,
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
    ) : this(
        context.settingsDataStore, auth, hydrator,
        palaceConfig, palaceApi, llmCreds, githubAuth, teamsAuth,
    )

    override val settings: Flow<UiSettings> = combine(
        store.data,
        palaceConfig.state,
        githubAuth.sessionState,
        teamsAuth.sessionState,
    ) { prefs, palace, githubSession, teamsSession ->
        UiSettings(
            ttsEngine = "VoxSherpa",
            defaultVoiceId = prefs[Keys.DEFAULT_VOICE_ID],
            defaultSpeed = prefs[Keys.DEFAULT_SPEED] ?: 1.0f,
            defaultPitch = prefs[Keys.DEFAULT_PITCH] ?: 1.0f,
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
            warmupWait = prefs[Keys.WARMUP_WAIT] ?: true,
            catchupPause = prefs[Keys.CATCHUP_PAUSE] ?: true,
            voiceSteady = prefs[Keys.VOICE_STEADY] ?: true,
            palace = UiPalaceConfig(host = palace.host, apiKey = palace.apiKey),
            github = githubSession.toUi(),
            githubPrivateReposEnabled = prefs[Keys.GITHUB_PRIVATE_REPOS_ENABLED] ?: false,
            sourceRoyalRoadEnabled = prefs[Keys.SOURCE_ROYALROAD_ENABLED] ?: true,
            sourceGitHubEnabled = prefs[Keys.SOURCE_GITHUB_ENABLED] ?: true,
            sourceMemPalaceEnabled = prefs[Keys.SOURCE_MEMPALACE_ENABLED] ?: true,
            sleepShakeToExtendEnabled = prefs[Keys.SLEEP_SHAKE_TO_EXTEND_ENABLED] ?: true,
            ai = UiAiSettings(
                provider = prefs[Keys.AI_PROVIDER]
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { UiLlmProvider.valueOf(it) }.getOrNull() },
                claudeModel = prefs[Keys.AI_CLAUDE_MODEL] ?: "claude-haiku-4.5",
                claudeKeyConfigured = llmCreds.hasClaudeKey,
                openAiModel = prefs[Keys.AI_OPENAI_MODEL] ?: "gpt-4o-mini",
                openAiKeyConfigured = llmCreds.hasOpenAiKey,
                ollamaBaseUrl = prefs[Keys.AI_OLLAMA_BASE_URL] ?: "http://10.0.0.1:11434",
                ollamaModel = prefs[Keys.AI_OLLAMA_MODEL] ?: "llama3.3",
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
                sendChapterTextEnabled = prefs[Keys.AI_SEND_CHAPTER_TEXT] ?: true,
                chatGrounding = UiChatGrounding(
                    includeChapterTitle = prefs[Keys.AI_CHAT_GROUND_CHAPTER_TITLE] ?: true,
                    includeCurrentSentence = prefs[Keys.AI_CHAT_GROUND_CURRENT_SENTENCE] ?: false,
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
        store.edit { it[Keys.DEFAULT_SPEED] = speed.coerceIn(0.5f, 3.0f) }
    }

    override suspend fun setDefaultPitch(pitch: Float) {
        // Persistence band matches the UI sliders (SettingsScreen +
        // AudiobookView). Tightened from 0.5..2.0 → 0.6..1.4 in Thalia's
        // VoxSherpa P0 #1 (2026-05-08): below ~0.7 Sonic introduces audible
        // artifacts on Piper-medium voices, and above 1.4 the chipmunk
        // territory is unlistenable. Stale prefs from before the widen
        // (which covered 0.85..1.15) all sit comfortably inside the new band.
        store.edit { it[Keys.DEFAULT_PITCH] = pitch.coerceIn(0.6f, 1.4f) }
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

    override val warmupWait: Flow<Boolean> = store.data.map { it[Keys.WARMUP_WAIT] ?: true }
    override val catchupPause: Flow<Boolean> = store.data.map { it[Keys.CATCHUP_PAUSE] ?: true }
    override suspend fun currentWarmupWait(): Boolean = warmupWait.first()
    override suspend fun currentCatchupPause(): Boolean = catchupPause.first()

    // --- VoiceTuningConfig (issue #85, consumed by core-playback's EnginePlayer) ---

    override val voiceSteady: Flow<Boolean> = store.data.map { it[Keys.VOICE_STEADY] ?: true }
    override suspend fun currentVoiceSteady(): Boolean = voiceSteady.first()

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

    override suspend fun setSleepShakeToExtendEnabled(enabled: Boolean) {
        store.edit { it[Keys.SLEEP_SHAKE_TO_EXTEND_ENABLED] = enabled }
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
            ollamaBaseUrl = prefs[Keys.AI_OLLAMA_BASE_URL] ?: "http://10.0.0.1:11434",
            ollamaModel = prefs[Keys.AI_OLLAMA_MODEL] ?: "llama3.3",
            vertexModel = prefs[Keys.AI_VERTEX_MODEL] ?: "gemini-2.5-flash",
            foundryEndpoint = prefs[Keys.AI_FOUNDRY_ENDPOINT] ?: "",
            foundryDeployment = prefs[Keys.AI_FOUNDRY_DEPLOYMENT] ?: "",
            foundryServerless = prefs[Keys.AI_FOUNDRY_SERVERLESS] ?: false,
            bedrockRegion = prefs[Keys.AI_BEDROCK_REGION] ?: "us-east-1",
            bedrockModel = prefs[Keys.AI_BEDROCK_MODEL] ?: "claude-haiku-4.5",
            privacyAcknowledged = prefs[Keys.AI_PRIVACY_ACK] ?: false,
            sendChapterTextEnabled = prefs[Keys.AI_SEND_CHAPTER_TEXT] ?: true,
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
