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
import `in`.jphe.storyvox.feature.api.UiLlmProvider
import `in`.jphe.storyvox.feature.api.UiPalaceConfig
import `in`.jphe.storyvox.feature.api.UiSettings
import `in`.jphe.storyvox.feature.api.UiSigil
import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmConfigProvider
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

    // ── AI / LLM (issue #81) ────────────────────────────────────────
    /** Active provider — stored as the [ProviderId] enum's name.
     *  Empty/missing = AI disabled. */
    val AI_PROVIDER = stringPreferencesKey("pref_ai_provider")
    val AI_CLAUDE_MODEL = stringPreferencesKey("pref_ai_claude_model")
    val AI_OPENAI_MODEL = stringPreferencesKey("pref_ai_openai_model")
    val AI_OLLAMA_BASE_URL = stringPreferencesKey("pref_ai_ollama_base_url")
    val AI_OLLAMA_MODEL = stringPreferencesKey("pref_ai_ollama_model")
    val AI_PRIVACY_ACK = booleanPreferencesKey("pref_ai_privacy_ack")
    val AI_SEND_CHAPTER_TEXT = booleanPreferencesKey("pref_ai_send_chapter_text")
}

@Singleton
class SettingsRepositoryUiImpl(
    private val store: DataStore<Preferences>,
    private val auth: AuthRepository,
    private val hydrator: SessionHydrator,
    private val palaceConfig: PalaceConfigImpl,
    private val palaceApi: PalaceDaemonApi,
    private val llmCreds: LlmCredentialsStore,
) : SettingsRepositoryUi, PlaybackBufferConfig, PlaybackModeConfig, LlmConfigProvider {

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
    ) : this(context.settingsDataStore, auth, hydrator, palaceConfig, palaceApi, llmCreds)

    override val settings: Flow<UiSettings> = combine(
        store.data,
        palaceConfig.state,
    ) { prefs, palace ->
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
            palace = UiPalaceConfig(host = palace.host, apiKey = palace.apiKey),
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
                privacyAcknowledged = prefs[Keys.AI_PRIVACY_ACK] ?: false,
                sendChapterTextEnabled = prefs[Keys.AI_SEND_CHAPTER_TEXT] ?: true,
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

    override suspend fun setSendChapterTextEnabled(enabled: Boolean) {
        store.edit { it[Keys.AI_SEND_CHAPTER_TEXT] = enabled }
    }

    override suspend fun acknowledgeAiPrivacy() {
        store.edit { it[Keys.AI_PRIVACY_ACK] = true }
    }

    override suspend fun resetAiSettings() {
        store.edit {
            it.remove(Keys.AI_PROVIDER)
            it.remove(Keys.AI_CLAUDE_MODEL)
            it.remove(Keys.AI_OPENAI_MODEL)
            it.remove(Keys.AI_OLLAMA_BASE_URL)
            it.remove(Keys.AI_OLLAMA_MODEL)
            it.remove(Keys.AI_PRIVACY_ACK)
            it.remove(Keys.AI_SEND_CHAPTER_TEXT)
        }
        llmCreds.clearAll()
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
            p[Keys.AI_PRIVACY_ACK] = config.privacyAcknowledged
            p[Keys.AI_SEND_CHAPTER_TEXT] = config.sendChapterTextEnabled
        }
    }
}
