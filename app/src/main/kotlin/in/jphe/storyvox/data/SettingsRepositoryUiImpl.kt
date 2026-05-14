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
import `in`.jphe.storyvox.llm.auth.GoogleOAuthTokenSource
import `in`.jphe.storyvox.llm.auth.GoogleServiceAccount
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
            // Issue #417 — seed `pref_source_radio_enabled` from the
            // legacy `pref_source_kvmr_enabled` value on the first run
            // of v0.5.32. Runs BEFORE SourcePluginsMapMigration so the
            // JSON map seed sees the renamed key. Idempotent.
            KvmrEnabledToRadioEnabledMigration,
            // Plugin-seam Phase 1 (#384) — seed the per-plugin JSON map
            // from the legacy per-source keys on first read. Idempotent
            // once the JSON key exists.
            SourcePluginsMapMigration,
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

/**
 * Issue #417 — one-shot migration from `pref_source_kvmr_enabled` to
 * `pref_source_radio_enabled` on the first run of v0.5.32 (when
 * `:source-kvmr` was generalized to `:source-radio`).
 *
 * Existing users with `sourceKvmrEnabled = true` (the v0.5.20+ default
 * for KVMR) keep their radio backend visible — the toggle they flipped
 * (or didn't flip) for KVMR continues to govern the renamed source
 * without an explicit re-opt-in. A user who explicitly turned KVMR off
 * sees the new Radio backend OFF too; their preference travels.
 *
 * Order matters: this runs BEFORE [SourcePluginsMapMigration] so the
 * JSON-map seeder finds the renamed key and lands the right state for
 * the new `SourceIds.RADIO` entry there too. The legacy
 * `pref_source_kvmr_enabled` key is kept (not deleted) so the same
 * value can also seed `SourceIds.KVMR` in the JSON map — the alias
 * id in [`SourceIds.KVMR`] is still a routable backend during the
 * one-cycle transition.
 *
 * Idempotent: once `pref_source_radio_enabled` is present (or the
 * legacy key is absent on a fresh install), the migration is a no-op.
 */
internal val KvmrEnabledToRadioEnabledMigration: DataMigration<Preferences> =
    object : DataMigration<Preferences> {
        private val legacyKvmr = booleanPreferencesKey("pref_source_kvmr_enabled")
        private val newRadio = booleanPreferencesKey("pref_source_radio_enabled")

        override suspend fun shouldMigrate(currentData: Preferences): Boolean {
            // Nothing to seed if the user already has a value (or had
            // one seeded on a prior run).
            if (currentData[newRadio] != null) return false
            // Fresh install (no legacy key either) → the JSON-map
            // seeder will pick up the SourceIds.RADIO default directly;
            // no migration needed.
            return currentData[legacyKvmr] != null
        }

        override suspend fun migrate(currentData: Preferences): Preferences {
            val mutable = currentData.toMutablePreferences()
            val legacyValue = currentData[legacyKvmr] ?: true
            mutable[newRadio] = legacyValue
            // Keep legacy key intact for one cycle so the JSON-map
            // seeder + the SourceIds.KVMR alias keep their preference
            // history. Phase-4 of the plugin seam will delete it.
            return mutable.toPreferences()
        }

        override suspend fun cleanUp() = Unit
    }

/**
 * Plugin-seam Phase 1 (#384) — one-shot migration from the per-source
 * `pref_source_xxx_enabled` boolean keys to the JSON map under
 * `pref_source_plugins_enabled_v1`.
 *
 * Runs once when the JSON key is absent. Reads every existing
 * `SOURCE_*_ENABLED` boolean and emits the equivalent
 * `{"royalroad": true, "kvmr": true, …}` blob. The per-source
 * booleans are *kept* (not deleted) for Phase 1 — the existing
 * Settings screen + BrowseViewModel still read them, so deleting
 * would regress those surfaces. Phase 2 deletes them once the
 * registry-driven UI lands and the per-source `setSourceXxxEnabled`
 * overrides also write into the map (the impl below handles that
 * dual-write).
 *
 * Idempotent: once the JSON key exists, `shouldMigrate` returns false
 * even if the per-source values change later. Subsequent
 * `setSourceXxxEnabled` calls update both the legacy key and the map
 * via the dual-write in [SettingsRepositoryUiImpl].
 *
 * Defaults for a plugin that has no persisted per-source key (e.g.
 * a brand-new install that goes straight through here) come from the
 * `SOURCE_DEFAULTS` table below — matches the fall-through defaults
 * used in `UiSettings` assembly. Keeping the defaults in one place
 * means a fresh install and a Phase-1 migration land on the same
 * starting state.
 */
internal val SourcePluginsMapMigration: DataMigration<Preferences> =
    object : DataMigration<Preferences> {
        private val jsonKey = stringPreferencesKey("pref_source_plugins_enabled_v1")

        override suspend fun shouldMigrate(currentData: Preferences): Boolean =
            currentData[jsonKey] == null

        override suspend fun migrate(currentData: Preferences): Preferences {
            val mutable = currentData.toMutablePreferences()
            val map = LinkedHashMap<String, Boolean>()
            for ((id, legacy) in LegacySourceKeys.ALL) {
                map[id] = currentData[legacy.key] ?: legacy.defaultValue
            }
            mutable[jsonKey] = `in`.jphe.storyvox.data.source.plugin.encodeSourcePluginsEnabledJson(map)
            return mutable.toPreferences()
        }

        override suspend fun cleanUp() = Unit
    }

/**
 * Plugin-seam Phase 1 (#384) — single-source-of-truth table mapping
 * each registered plugin id to (legacy `SOURCE_*_ENABLED` key, default
 * value). Used by the [SourcePluginsMapMigration] above and by the
 * dual-write in [SettingsRepositoryUiImpl.setSourcePluginEnabled].
 *
 * When a backend migrates to a registry-only world (Phase 2), its
 * entry comes off this table and its row in `UiSettings` collapses.
 * Until then, every legacy `sourceXxxEnabled` field corresponds to
 * exactly one row here.
 */
internal object LegacySourceKeys {
    data class Spec(
        val key: androidx.datastore.preferences.core.Preferences.Key<Boolean>,
        val defaultValue: Boolean,
    )

    // Keep in sync with the per-source defaults inlined in
    // SettingsRepositoryUiImpl.settings (the `prefs[...] ?: <bool>`
    // expressions). Drift here is a fresh-install behavior bug.
    val ALL: Map<String, Spec> = linkedMapOf(
        `in`.jphe.storyvox.data.source.SourceIds.ROYAL_ROAD to
            Spec(booleanPreferencesKey("pref_source_royalroad_enabled"), defaultValue = true),
        // #436 — fresh-install discoverability: all 17 backends default
        // ON in the JSON map so the Browse chip strip lists every
        // backend. Per-source migration tables (`KvmrEnabledToRadioEnabled`,
        // legacy boolean keys carried forward from old installs) still
        // honor the user's previous explicit choices on upgrade; this
        // table is only consulted when the legacy boolean key was never
        // written (i.e. fresh install). Keep these defaults in lockstep
        // with each backend's `@SourcePlugin(defaultEnabled = …)` —
        // `SourcePluginContractTest` asserts the parity.
        `in`.jphe.storyvox.data.source.SourceIds.GITHUB to
            Spec(booleanPreferencesKey("pref_source_github_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.MEMPALACE to
            Spec(booleanPreferencesKey("pref_source_mempalace_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.RSS to
            Spec(booleanPreferencesKey("pref_source_rss_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.EPUB to
            Spec(booleanPreferencesKey("pref_source_epub_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.OUTLINE to
            Spec(booleanPreferencesKey("pref_source_outline_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.GUTENBERG to
            Spec(booleanPreferencesKey("pref_source_gutenberg_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.AO3 to
            Spec(booleanPreferencesKey("pref_source_ao3_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.STANDARD_EBOOKS to
            Spec(booleanPreferencesKey("pref_source_standard_ebooks_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.WIKIPEDIA to
            Spec(booleanPreferencesKey("pref_source_wikipedia_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.WIKISOURCE to
            Spec(booleanPreferencesKey("pref_source_wikisource_enabled"), defaultValue = true),
        // Issue #417 — :source-kvmr generalized to :source-radio. The
        // canonical entry is RADIO with its own pref_source_radio_enabled
        // key (seeded from the legacy pref_source_kvmr_enabled value
        // via KvmrEnabledToRadioEnabledMigration). The KVMR row is kept
        // as a one-cycle alias so the JSON map continues to surface a
        // value under the legacy id for any consumer still routing
        // through SourceIds.KVMR; the actual FictionSource binding for
        // both ids points at the same RadioSource instance.
        `in`.jphe.storyvox.data.source.SourceIds.RADIO to
            Spec(booleanPreferencesKey("pref_source_radio_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.KVMR to
            Spec(booleanPreferencesKey("pref_source_kvmr_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.NOTION to
            Spec(booleanPreferencesKey("pref_source_notion_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.HACKERNEWS to
            Spec(booleanPreferencesKey("pref_source_hackernews_enabled"), defaultValue = true),
        // #378 — arXiv. Fresh-install discoverability (#436) flipped
        // this from off → on; the user can disable via Settings → Plugins.
        `in`.jphe.storyvox.data.source.SourceIds.ARXIV to
            Spec(booleanPreferencesKey("pref_source_arxiv_enabled"), defaultValue = true),
        // #380 — PLOS open-access peer-reviewed science. Fresh-install
        // discoverability (#436) flipped this from off → on.
        `in`.jphe.storyvox.data.source.SourceIds.PLOS to
            Spec(booleanPreferencesKey("pref_source_plos_enabled"), defaultValue = true),
        // #403 — Discord backend. Fresh-install discoverability (#436)
        // flipped this on; chip is visible but the backend stays inert
        // until the user enters a bot token in Settings.
        `in`.jphe.storyvox.data.source.SourceIds.DISCORD to
            Spec(booleanPreferencesKey("pref_source_discord_enabled"), defaultValue = true),
    )
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
    /** Issue #197 — per-voice lexicon override map. Same flat
     *  `voiceId=path;voiceId=path` codec as the speed/pitch maps.
     *  Empty for fresh installs; engine falls back to its built-in
     *  lexicon. */
    val VOICE_LEXICON_OVERRIDES = stringPreferencesKey("pref_voice_lexicon_overrides")
    /** Issue #198 — per-voice Kokoro phonemizer language override map.
     *  `voiceId=lang;voiceId=lang` (e.g. `kokoro_af_bella=es`). Only
     *  honored by KokoroEngine; Piper entries are inert at the engine
     *  layer but persisted so the UI surface stays consistent. */
    val VOICE_PHONEMIZER_LANG_OVERRIDES = stringPreferencesKey("pref_voice_phonemizer_lang_overrides")
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
    /** Issue #193 — Sonic pitch-interpolation quality toggle. true = quality=1,
     *  false = quality=0 (Sonic's upstream default). Defaults to true on
     *  fresh installs. */
    val PITCH_INTERP_HIGH_QUALITY = booleanPreferencesKey("pref_pitch_interp_high_quality")
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
    /** Issue #375 — Standard Ebooks backend on/off. Default false for
     *  fresh installs; opt-in surface like Outline/Epub. */
    val SOURCE_STANDARD_EBOOKS_ENABLED = booleanPreferencesKey("pref_source_standard_ebooks_enabled")
    /** Issue #377 — Wikipedia backend on/off. Default false for fresh
     *  installs; first non-fiction-shaped source is opt-in. */
    val SOURCE_WIKIPEDIA_ENABLED = booleanPreferencesKey("pref_source_wikipedia_enabled")
    /** Issue #376 — Wikisource backend on/off. Default false for fresh
     *  installs; opt-in surface like other text backends. Wikisource is
     *  the Wikimedia transcribed-public-domain-texts project (CC0/PD
     *  posture, no third-party-ToS surface). */
    val SOURCE_WIKISOURCE_ENABLED = booleanPreferencesKey("pref_source_wikisource_enabled")
    /** Issue #417 — generalized :source-kvmr → :source-radio. Default
     *  TRUE on fresh installs (the audio-stream pipeline + the curated
     *  KVMR/KQED/KCSB/KXPR/SomaFM seed list should be discoverable
     *  without an opt-in step). Seeded from the legacy
     *  pref_source_kvmr_enabled value for upgrading users via
     *  KvmrEnabledToRadioEnabledMigration. */
    val SOURCE_RADIO_ENABLED = booleanPreferencesKey("pref_source_radio_enabled")

    /** Issue #374 — legacy KVMR backend on/off, kept as a one-cycle
     *  alias of [SOURCE_RADIO_ENABLED] (#417). Default TRUE on fresh
     *  installs. A follow-up release will drop this key once one full
     *  release cycle has elapsed with the renamed entry live. */
    val SOURCE_KVMR_ENABLED = booleanPreferencesKey("pref_source_kvmr_enabled")
    /** Issue #233 — Notion backend on/off. Default TRUE on fresh
     *  installs per #390 — the bundled techempower.org database id
     *  needs the toggle ON to be visible in Browse. Source returns
     *  AuthRequired on every call until the user pastes an integration
     *  token via Settings → Library & Sync → Notion. */
    val SOURCE_NOTION_ENABLED = booleanPreferencesKey("pref_source_notion_enabled")
    /** Issue #379 — Hacker News backend on/off. Default false: this
     *  is a tech-news / discussion backend, not a fiction source in
     *  the classic sense, so it should be an explicit opt-in. */
    val SOURCE_HACKERNEWS_ENABLED = booleanPreferencesKey("pref_source_hackernews_enabled")
    /** Issue #378 — arXiv backend on/off. Default false for fresh
     *  installs; second non-fiction-shaped source after Wikipedia
     *  follows the same opt-in posture. */
    val SOURCE_ARXIV_ENABLED = booleanPreferencesKey("pref_source_arxiv_enabled")
    /** Issue #380 — PLOS open-access peer-reviewed science backend
     *  on/off. Default false for fresh installs; opt-in surface like
     *  Wikipedia. */
    val SOURCE_PLOS_ENABLED = booleanPreferencesKey("pref_source_plos_enabled")
    /** Issue #403 — Discord backend on/off. Default false for fresh
     *  installs — bot-token onboarding is high-friction and Discord
     *  is a private workspace, not a public catalog. */
    val SOURCE_DISCORD_ENABLED = booleanPreferencesKey("pref_source_discord_enabled")

    // ── Plugin-seam Phase 1 (#384) ────────────────────────────────
    /**
     * JSON-serialized `Map<String, Boolean>` keyed by `@SourcePlugin`
     * id. Replaces the per-source `SOURCE_*_ENABLED` keys above as
     * backends migrate to the registry-driven shape. The two views
     * coexist during the phased rollout: setting the legacy key also
     * writes the corresponding map entry, and vice-versa, so existing
     * UI observers keep working.
     *
     * The _v1 suffix lets us rev the schema later (e.g. richer
     * per-plugin state than a bare boolean) without a destructive
     * migration; an unparseable v1 blob falls back to a
     * defaults-from-registry map and the per-plugin migration shim
     * (SourcePluginsMapMigration) re-seeds from the legacy keys.
     */
    val SOURCE_PLUGINS_ENABLED_JSON = stringPreferencesKey("pref_source_plugins_enabled_v1")

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
    /** Issue #217 — cross-fiction memory toggle. Default ON; the
     *  Settings → AI screen lets the user opt out. Absent key reads
     *  as true so fresh installs (and pre-#217 installs upgrading)
     *  both get the more-useful default. */
    val AI_CARRY_MEMORY_ACROSS_FICTIONS = booleanPreferencesKey("pref_ai_carry_memory_across_fictions")
    /** Issue #216 — "Allow the AI to take actions" toggle. Default
     *  ON across fresh + upgrading installs; the Settings → AI
     *  screen lets the user opt out. Same write-once-default-on
     *  pattern as the cross-fiction memory key. */
    val AI_ACTIONS_ENABLED = booleanPreferencesKey("pref_ai_actions_enabled")

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

    // ── Inbox per-source mute toggles (issue #383) ─────────────────
    // Added at the END of Keys to minimize merge conflicts with the
    // other agents touching this file (Vertex SA, plugin seam).
    // Defaults are ON across the board — the Inbox is opt-out
    // per-source; flipping a toggle OFF stops the backend's update
    // emitter from writing to the inbox_event table.
    val INBOX_NOTIFY_ROYALROAD = booleanPreferencesKey("pref_inbox_notify_royalroad")
    val INBOX_NOTIFY_KVMR = booleanPreferencesKey("pref_inbox_notify_kvmr")
    val INBOX_NOTIFY_WIKIPEDIA = booleanPreferencesKey("pref_inbox_notify_wikipedia")
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

/** Issue #197 / #198 — same flat-string codec as
 *  [encodeVoiceFloatMap] but for string values (lexicon paths,
 *  language codes). Voice IDs from the catalog are alphanumeric +
 *  underscores so neither `=` nor `;` collide. Values:
 *   - lexicon paths: Android FS paths use `/` + alphanumerics; SAF
 *     content:// URIs use `://` and `%2F` but not raw `;` (RFC 3986
 *     reserves it, and SAF percent-encodes). Documented expectation
 *     is alphanumerics + `/`, `.`, `_`, `-`, `:`, `%` — no codec
 *     collisions in practice.
 *   - language codes: 2-letter ISO codes from
 *     [KOKORO_PHONEMIZER_LANGS], no special chars.
 *  Bad / corrupt entries are dropped silently — these maps are
 *  non-critical state and the engine falls back cleanly. */
private fun encodeVoiceStringMap(map: Map<String, String>): String =
    map.entries.joinToString(";") { (k, v) -> "$k=$v" }

private fun decodeVoiceStringMap(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(';').mapNotNull { entry ->
        val eq = entry.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val k = entry.substring(0, eq)
        val v = entry.substring(eq + 1)
        if (v.isEmpty()) return@mapNotNull null
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
    private val wikipediaConfig: WikipediaConfigImpl,
    private val notionConfig: NotionConfigImpl,
    private val discordConfig: DiscordConfigImpl,
    private val discordGuildDirectory: `in`.jphe.storyvox.source.discord.DiscordGuildDirectory,
    private val suggestedFeedsRegistry: SuggestedFeedsRegistry,
    private val azureCreds: AzureCredentials,
    private val azureClient: AzureSpeechClient,
    private val azureRoster: AzureVoiceRoster,
    /** Issue #219 — injected so clearing/replacing the SA JSON also
     *  evicts the in-process access-token cache. Without this, a user
     *  who swaps their SA in Settings would keep using the old token
     *  until process restart. */
    private val googleTokenSource: GoogleOAuthTokenSource,
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
        wikipediaConfig: WikipediaConfigImpl,
        notionConfig: NotionConfigImpl,
        discordConfig: DiscordConfigImpl,
        discordGuildDirectory: `in`.jphe.storyvox.source.discord.DiscordGuildDirectory,
        suggestedFeedsRegistry: SuggestedFeedsRegistry,
        azureCreds: AzureCredentials,
        azureClient: AzureSpeechClient,
        azureRoster: AzureVoiceRoster,
        googleTokenSource: GoogleOAuthTokenSource,
    ) : this(
        context.settingsDataStore, auth, hydrator,
        palaceConfig, palaceApi, llmCreds, githubAuth, teamsAuth, rssConfig, epubConfig,
        outlineConfig, wikipediaConfig, notionConfig, discordConfig, discordGuildDirectory,
        suggestedFeedsRegistry,
        azureCreds, azureClient, azureRoster,
        googleTokenSource,
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

    /** Issue #377 + #233 + #403 — non-prefs source configs bundled
     *  into a single combine so the outer combine stays inside the
     *  5-arg overload. Palace + Wikipedia + Notion + Discord ride
     *  together; each re-emits independently when its respective
     *  store changes. */
    private data class NonPrefsConfigs(
        val palace: `in`.jphe.storyvox.source.mempalace.config.PalaceConfigState,
        val wikipedia: `in`.jphe.storyvox.source.wikipedia.config.WikipediaConfigState,
        val notion: `in`.jphe.storyvox.source.notion.config.NotionConfigState,
        val discord: `in`.jphe.storyvox.source.discord.config.DiscordConfigState,
    )

    private val nonPrefsConfigs: Flow<NonPrefsConfigs> =
        combine(
            palaceConfig.state,
            wikipediaConfig.state,
            notionConfig.state,
            discordConfig.state,
        ) { palace, wiki, notion, discord ->
            NonPrefsConfigs(palace, wiki, notion, discord)
        }

    override val settings: Flow<UiSettings> = combine(
        store.data,
        nonPrefsConfigs,
        githubAuth.sessionState,
        teamsAuth.sessionState,
        azureTick,
    ) { prefs, configs, githubSession, teamsSession, _ ->
        val palace = configs.palace
        val wikipedia = configs.wikipedia
        val notion = configs.notion
        val discord = configs.discord
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
            voiceLexiconOverrides = decodeVoiceStringMap(prefs[Keys.VOICE_LEXICON_OVERRIDES]),
            voicePhonemizerLangOverrides =
                decodeVoiceStringMap(prefs[Keys.VOICE_PHONEMIZER_LANG_OVERRIDES]),
            themeOverride = prefs[Keys.THEME_OVERRIDE]?.let { runCatching { ThemeOverride.valueOf(it) }.getOrNull() }
                ?: ThemeOverride.System,
            downloadOnWifiOnly = prefs[Keys.DOWNLOAD_WIFI_ONLY] ?: true,
            pollIntervalHours = prefs[Keys.POLL_INTERVAL_HOURS] ?: 6,
            isSignedIn = prefs[Keys.SIGNED_IN] ?: false,
            pitchInterpolationHighQuality = prefs[Keys.PITCH_INTERP_HIGH_QUALITY] ?: true,
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
            wikipediaLanguageCode = wikipedia.languageCode,
            discordTokenConfigured = discord.apiToken.isNotBlank(),
            discordServerId = discord.serverId,
            discordServerName = discord.serverName,
            discordCoalesceMinutes = discord.coalesceMinutes,
            // Plugin-seam Phase 1 (#384) — derive the per-plugin map
            // from the JSON blob seeded by SourcePluginsMapMigration.
            // Empty map (parse error / missing key in a race) falls
            // through to a defaults-from-LegacySourceKeys snapshot so
            // observers always see a coherent state.
            sourcePluginsEnabled = run {
                val parsed = `in`.jphe.storyvox.data.source.plugin.decodeSourcePluginsEnabledJson(
                    prefs[Keys.SOURCE_PLUGINS_ENABLED_JSON],
                )
                if (parsed.isNotEmpty()) {
                    parsed
                } else {
                    LegacySourceKeys.ALL.mapValues { (_, spec) ->
                        prefs[spec.key] ?: spec.defaultValue
                    }
                }
            },
            notionDatabaseId = notion.databaseId,
            notionTokenConfigured = notion.apiToken.isNotBlank(),
            // Issue #393 — surface the anonymous-mode posture so the
            // Settings UI can render a "Reading TechEmpower content
            // anonymously" affordance and the user understands the PAT
            // field is opt-in for private workspaces.
            notionMode = notion.mode.name,
            notionRootPageId = notion.rootPageId,
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
                vertexServiceAccountConfigured = llmCreds.hasVertexServiceAccount,
                vertexServiceAccountEmail = llmCreds.vertexServiceAccountJson()
                    ?.let { runCatching { GoogleServiceAccount.parse(it).clientEmail }.getOrNull() },
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
                // Issue #217 — default ON for fresh installs; pre-#217
                // upgrades also pick up the default because the key
                // doesn't exist yet (DataStore returns null → ?: true).
                carryMemoryAcrossFictions = prefs[Keys.AI_CARRY_MEMORY_ACROSS_FICTIONS] ?: true,
                // Issue #216 — default ON for fresh installs (matches
                // [LlmConfig.aiActionsEnabled]). The chat surface only
                // exposes actions when the active provider supports
                // tool use; this toggle is the user's "I don't want
                // the AI doing things" kill switch.
                actionsEnabled = prefs[Keys.AI_ACTIONS_ENABLED] ?: true,
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
            // Issue #383 — Inbox per-source notification toggles.
            // Default ON across the board on fresh installs; user opts
            // out from Settings → Library & Sync → Inbox notifications.
            inboxNotifyRoyalRoad = prefs[Keys.INBOX_NOTIFY_ROYALROAD] ?: true,
            inboxNotifyKvmr = prefs[Keys.INBOX_NOTIFY_KVMR] ?: true,
            inboxNotifyWikipedia = prefs[Keys.INBOX_NOTIFY_WIKIPEDIA] ?: true,
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
        // #197 + #198 — switching voices means the per-voice lexicon
        // and (Kokoro) phonemizer-lang overrides change too. Read the
        // new voice's stored values inside the same edit() block and
        // push them to the bridge BEFORE returning so VoiceManager's
        // reload picks up the right knobs. If voiceId is null (user
        // cleared the default) we wipe the bridge fields back to
        // empty so any later engine instantiation uses defaults.
        var lexicon = ""
        var lang = ""
        store.edit { prefs ->
            if (voiceId == null) {
                prefs.remove(Keys.DEFAULT_VOICE_ID)
            } else {
                prefs[Keys.DEFAULT_VOICE_ID] = voiceId
                lexicon = decodeVoiceStringMap(prefs[Keys.VOICE_LEXICON_OVERRIDES])[voiceId]
                    .orEmpty()
                lang = decodeVoiceStringMap(prefs[Keys.VOICE_PHONEMIZER_LANG_OVERRIDES])[voiceId]
                    .orEmpty()
            }
        }
        `in`.jphe.storyvox.playback.VoiceEngineQualityBridge.applyLexicon(lexicon)
        `in`.jphe.storyvox.playback.VoiceEngineQualityBridge.applyPhonemizerLang(lang)
    }

    override suspend fun setVoiceLexicon(voiceId: String, path: String?) {
        // #197 — write the per-voice path map. null / blank clears
        // the entry entirely so the engine falls back to its
        // built-in lexicon on next load. We push to the bridge
        // immediately when voiceId matches the active voice so the
        // *next* chapter pre-render reads the new path; the
        // in-flight engine instance keeps its old lexicon until the
        // next loadModel().
        var isActive = false
        store.edit { prefs ->
            val map = decodeVoiceStringMap(prefs[Keys.VOICE_LEXICON_OVERRIDES]).toMutableMap()
            if (path.isNullOrBlank()) map.remove(voiceId)
            else map[voiceId] = path
            prefs[Keys.VOICE_LEXICON_OVERRIDES] = encodeVoiceStringMap(map)
            isActive = prefs[Keys.DEFAULT_VOICE_ID] == voiceId
        }
        if (isActive) {
            `in`.jphe.storyvox.playback.VoiceEngineQualityBridge
                .applyLexicon(path.orEmpty())
        }
    }

    override suspend fun setVoicePhonemizerLang(voiceId: String, langCode: String?) {
        // #198 — write the per-voice Kokoro phonemizer-lang map.
        // null / blank clears the entry. We don't validate against
        // KOKORO_PHONEMIZER_LANGS here — the UI dropdown only offers
        // recognized codes, and a stray unrecognized value falls back
        // cleanly at the engine layer (Kokoro uses the voice's
        // native language for unknown codes).
        var isActive = false
        store.edit { prefs ->
            val map = decodeVoiceStringMap(
                prefs[Keys.VOICE_PHONEMIZER_LANG_OVERRIDES]
            ).toMutableMap()
            if (langCode.isNullOrBlank()) map.remove(voiceId)
            else map[voiceId] = langCode
            prefs[Keys.VOICE_PHONEMIZER_LANG_OVERRIDES] = encodeVoiceStringMap(map)
            isActive = prefs[Keys.DEFAULT_VOICE_ID] == voiceId
        }
        if (isActive) {
            `in`.jphe.storyvox.playback.VoiceEngineQualityBridge
                .applyPhonemizerLang(langCode.orEmpty())
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

    override suspend fun setPitchInterpolationHighQuality(enabled: Boolean) {
        store.edit { it[Keys.PITCH_INTERP_HIGH_QUALITY] = enabled }
        // Push to both engine fields immediately so the next
        // chapter pre-render uses the new setting without waiting
        // for a process restart. Sonic instances are created fresh
        // inside each generateAudioPCM call, so the volatile read
        // there picks up the new value.
        `in`.jphe.storyvox.playback.VoiceEngineQualityBridge.applyPitchQuality(enabled)
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
        if (key == null) {
            llmCreds.clearVertexApiKey()
        } else {
            // Issue #219 — the API-key and SA-JSON modes are mutually
            // exclusive at the storage layer; setting one drops the
            // other so VertexProvider's dispatch never sees ambiguous
            // state. The SA-side invalidation also flushes the OAuth
            // token cache so an old token can't keep being used.
            llmCreds.setVertexApiKey(key)
            if (llmCreds.hasVertexServiceAccount) {
                llmCreds.clearVertexServiceAccountJson()
                googleTokenSource.invalidate()
            }
        }
    }

    override suspend fun setVertexModel(model: String) {
        store.edit { it[Keys.AI_VERTEX_MODEL] = model }
    }

    override suspend fun setVertexServiceAccountJson(json: String?) {
        // Validate-then-persist. Parsing throws IllegalArgumentException
        // with a human-readable cause on bad input; we let it propagate
        // so the SAF-picker callback can toast the message rather than
        // silently writing garbage to the encrypted prefs.
        if (json == null) {
            llmCreds.clearVertexServiceAccountJson()
            googleTokenSource.invalidate()
        } else {
            GoogleServiceAccount.parse(json)
            llmCreds.setVertexServiceAccountJson(json)
            // Mutual-exclusion: if an API key was set, drop it. Same
            // rationale as setVertexApiKey above.
            if (llmCreds.hasVertexKey) {
                llmCreds.clearVertexApiKey()
            }
            // New SA installed → old cached token is stale (matches a
            // different SA identity). Belt-and-braces; the cache also
            // self-invalidates on identity mismatch.
            googleTokenSource.invalidate()
        }
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

    override suspend fun setCarryMemoryAcrossFictions(enabled: Boolean) {
        store.edit { it[Keys.AI_CARRY_MEMORY_ACROSS_FICTIONS] = enabled }
    }

    override suspend fun setAiActionsEnabled(enabled: Boolean) {
        store.edit { it[Keys.AI_ACTIONS_ENABLED] = enabled }
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
        // Same logic for the Google OAuth access-token cache (#219) —
        // the SA JSON it was minted from no longer exists on disk.
        googleTokenSource.invalidate()
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

    override val epubFolderUri: kotlinx.coroutines.flow.Flow<String?> = epubConfig.folderUriString
    override suspend fun setEpubFolderUri(uri: String) = epubConfig.setFolder(uri)
    override suspend fun clearEpubFolder() = epubConfig.clearFolder()

    override val outlineHost: kotlinx.coroutines.flow.Flow<String> =
        outlineConfig.state.map { it.host }
    override suspend fun setOutlineHost(host: String) = outlineConfig.setHost(host)
    override suspend fun setOutlineApiKey(apiKey: String) = outlineConfig.setApiKey(apiKey)
    override suspend fun clearOutlineConfig() = outlineConfig.clear()

    override suspend fun setWikipediaLanguageCode(code: String) =
        wikipediaConfig.setLanguageCode(code)

    override suspend fun setDiscordApiToken(token: String?) {
        discordConfig.setApiToken(token)
    }

    override suspend fun setDiscordServer(serverId: String, serverName: String) {
        discordConfig.setServer(serverId, serverName)
    }

    override suspend fun setDiscordCoalesceMinutes(minutes: Int) {
        discordConfig.setCoalesceMinutes(minutes)
    }

    override suspend fun fetchDiscordGuilds(): List<Pair<String, String>> =
        // Delegates to the DiscordGuildDirectory in :source-discord;
        // that thin wrapper exposes the internal DiscordApi's
        // listGuilds() through a public surface so :app doesn't reach
        // into the source's internal wire types directly. On any
        // failure (no token, network, 401/403) the directory returns
        // an empty list — the UI handles "empty picker" as the
        // unified empty-state across the three failure modes.
        discordGuildDirectory.listGuilds()

    /**
     * Plugin-seam Phase 3 (#384) — registry-driven entry point and
     * single source of truth as of v0.5.31. Writes the per-plugin map;
     * the legacy `pref_source_xxx_enabled` boolean keys are kept on
     * disk for the one-shot [SourcePluginsMapMigration] seed but no
     * longer dual-written here. The Phase 1/2 dual-write that mirrored
     * each toggle into the legacy boolean is gone — there's only one
     * shape now.
     */
    override suspend fun setSourcePluginEnabled(id: String, enabled: Boolean) {
        store.edit { prefs ->
            val current = `in`.jphe.storyvox.data.source.plugin.decodeSourcePluginsEnabledJson(
                prefs[Keys.SOURCE_PLUGINS_ENABLED_JSON],
            ).toMutableMap()
            current[id] = enabled
            prefs[Keys.SOURCE_PLUGINS_ENABLED_JSON] =
                `in`.jphe.storyvox.data.source.plugin.encodeSourcePluginsEnabledJson(current)
        }
    }
    override suspend fun setNotionDatabaseId(id: String) {
        notionConfig.setDatabaseId(id)
    }
    override suspend fun setNotionApiToken(token: String?) {
        notionConfig.setApiToken(token)
    }

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
            // Issue #216 — default ON, matches the UiAiSettings
            // mirror above.
            aiActionsEnabled = prefs[Keys.AI_ACTIONS_ENABLED] ?: true,
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
            p[Keys.AI_ACTIONS_ENABLED] = config.aiActionsEnabled
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

    // ── Issue #383 — Inbox per-source mute toggles ─────────────────
    // Added at the END of the class body so the diff stays away from
    // other agents touching this file (Vertex SA, plugin seam).
    override suspend fun setInboxNotifyRoyalRoad(enabled: Boolean) {
        store.edit { it[Keys.INBOX_NOTIFY_ROYALROAD] = enabled }
    }
    override suspend fun setInboxNotifyKvmr(enabled: Boolean) {
        store.edit { it[Keys.INBOX_NOTIFY_KVMR] = enabled }
    }
    override suspend fun setInboxNotifyWikipedia(enabled: Boolean) {
        store.edit { it[Keys.INBOX_NOTIFY_WIKIPEDIA] = enabled }
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
