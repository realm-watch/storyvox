package `in`.jphe.storyvox.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.source.discord.config.DiscordConfig
import `in`.jphe.storyvox.source.discord.config.DiscordConfigState
import `in`.jphe.storyvox.source.discord.config.DiscordDefaults
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.discordDataStore: DataStore<Preferences> by preferencesDataStore(name = "storyvox_discord")

private object DiscordKeys {
    /** Selected Discord guild (server) id — the server whose channels
     *  storyvox surfaces as fictions. User picks this in Settings after
     *  the bot token is configured + the server picker is populated. */
    val SERVER_ID = stringPreferencesKey("pref_discord_server_id")
    /** Optional human-readable server name, captured at picker time
     *  so empty-state copy can name the server without an extra
     *  `users/@me/guilds` round-trip. */
    val SERVER_NAME = stringPreferencesKey("pref_discord_server_name")
    /** Same-author coalesce window in minutes (slider range 1-30).
     *  Defaults to [DiscordDefaults.DEFAULT_COALESCE_MINUTES] when unset. */
    val COALESCE_MINUTES = intPreferencesKey("pref_discord_coalesce_minutes")

    /** Issue #517 — opt-in for TechEmpower default channel seeds. ON
     *  by default on fresh installs so the peer-support channel
     *  surfaces in Browse the moment Discord is configured. Users can
     *  flip OFF via Settings (UI surface TBD); when OFF, the source's
     *  `state.pinnedChannelIds` is emitted as an empty list so
     *  downstream call sites take the same code path as a vanilla
     *  Discord setup. */
    val TECHEMPOWER_DEFAULTS_ENABLED = booleanPreferencesKey(
        "pref_discord_techempower_default_enabled",
    )
}

/**
 * Issue #517 — bundle of plaintext-DataStore-persisted Discord fields,
 * scoped to this file. Kotlin destructuring caps out at `Triple`, and
 * we now have four plaintext fields (server id, server name, coalesce
 * window, TechEmpower-defaults toggle) — a tiny private data class is
 * cleaner than nested pairs.
 */
private data class DiscordPersistedFields(
    val serverId: String,
    val serverName: String,
    val coalesce: Int,
    val techempowerDefaultsEnabled: Boolean,
)

/** EncryptedSharedPreferences key for the Discord bot token. Lives
 *  next to the Notion / Outline / palace tokens in `storyvox.secrets`.
 *  The literal key string is the spec-provided one from issue #403. */
internal const val DISCORD_BOT_TOKEN_PREF = "pref_source_discord_token"

/**
 * Issue #403 — production [DiscordConfig]. Server id + coalesce
 * window in plaintext DataStore (no secrets), bot token in
 * EncryptedSharedPreferences alongside the other source tokens.
 *
 * Mirrors [NotionConfigImpl] / [OutlineConfigImpl] one-to-one — the
 * parallel structure keeps the secrets store one consistent surface
 * across `:source-mempalace`, `:source-outline`, `:source-notion`,
 * and `:source-discord`.
 *
 * Defaults: empty token + empty server id (no baked-in default —
 * any default would imply storyvox auto-knows your server, which
 * doesn't fit the ToS posture). Coalesce window defaults to
 * [DiscordDefaults.DEFAULT_COALESCE_MINUTES] (5 minutes).
 */
@Singleton
class DiscordConfigImpl(
    private val store: DataStore<Preferences>,
    private val secrets: SharedPreferences,
) : DiscordConfig {

    @Inject constructor(
        @ApplicationContext context: Context,
        secrets: SharedPreferences,
    ) : this(context.discordDataStore, secrets)

    /**
     * Tick bumped whenever [setApiToken] runs so the [state] flow
     * re-emits with the fresh token value. SharedPreferences doesn't
     * expose a Flow on its own — same pattern as `OutlineConfigImpl`
     * and `NotionConfigImpl` use for their token legs.
     */
    private val secretsTick = MutableStateFlow(0L)

    override val state: Flow<DiscordConfigState> = combine(
        store.data.map { prefs ->
            DiscordPersistedFields(
                serverId = prefs[DiscordKeys.SERVER_ID].orEmpty(),
                serverName = prefs[DiscordKeys.SERVER_NAME].orEmpty(),
                coalesce = prefs[DiscordKeys.COALESCE_MINUTES] ?: DiscordDefaults.DEFAULT_COALESCE_MINUTES,
                techempowerDefaultsEnabled = prefs[DiscordKeys.TECHEMPOWER_DEFAULTS_ENABLED]
                    ?: DiscordDefaults.DEFAULT_TECHEMPOWER_DEFAULTS_ENABLED,
            )
        }.distinctUntilChanged(),
        secretsTick,
    ) { fields, _ ->
        val token = secrets.getString(DISCORD_BOT_TOKEN_PREF, "") ?: ""
        DiscordConfigState(
            apiToken = token,
            serverId = fields.serverId,
            serverName = fields.serverName,
            coalesceMinutes = fields.coalesce.coerceIn(
                DiscordDefaults.MIN_COALESCE_MINUTES,
                DiscordDefaults.MAX_COALESCE_MINUTES,
            ),
            pinnedChannelIds = if (fields.techempowerDefaultsEnabled) {
                DiscordDefaults.DEFAULT_PINNED_CHANNEL_IDS
            } else {
                emptyList()
            },
            techempowerDefaultsEnabled = fields.techempowerDefaultsEnabled,
        )
    }.distinctUntilChanged()

    override suspend fun current(): DiscordConfigState {
        val prefs = store.data.first()
        val token = secrets.getString(DISCORD_BOT_TOKEN_PREF, "") ?: ""
        val coalesce = (prefs[DiscordKeys.COALESCE_MINUTES] ?: DiscordDefaults.DEFAULT_COALESCE_MINUTES)
            .coerceIn(DiscordDefaults.MIN_COALESCE_MINUTES, DiscordDefaults.MAX_COALESCE_MINUTES)
        val techempowerDefaultsEnabled = prefs[DiscordKeys.TECHEMPOWER_DEFAULTS_ENABLED]
            ?: DiscordDefaults.DEFAULT_TECHEMPOWER_DEFAULTS_ENABLED
        return DiscordConfigState(
            apiToken = token,
            serverId = prefs[DiscordKeys.SERVER_ID].orEmpty(),
            serverName = prefs[DiscordKeys.SERVER_NAME].orEmpty(),
            coalesceMinutes = coalesce,
            pinnedChannelIds = if (techempowerDefaultsEnabled) {
                DiscordDefaults.DEFAULT_PINNED_CHANNEL_IDS
            } else {
                emptyList()
            },
            techempowerDefaultsEnabled = techempowerDefaultsEnabled,
        )
    }

    /**
     * Persist the bot token. Null/blank clears the store entry so
     * the source returns AuthRequired on subsequent calls. Bumps
     * [secretsTick] so the state flow re-emits without an explicit
     * Settings re-fetch.
     */
    fun setApiToken(token: String?) {
        if (token.isNullOrBlank()) {
            secrets.edit().remove(DISCORD_BOT_TOKEN_PREF).apply()
        } else {
            secrets.edit().putString(DISCORD_BOT_TOKEN_PREF, token.trim()).apply()
        }
        secretsTick.value = secretsTick.value + 1
    }

    /** True when a non-empty bot token is stored. Drives the UI's
     *  `discordTokenConfigured: Boolean` projection. */
    fun isTokenConfigured(): Boolean =
        !secrets.getString(DISCORD_BOT_TOKEN_PREF, "").isNullOrBlank()

    /**
     * Persist the selected server id + human name. Empty input wipes
     * the stored values (server picker goes back to "no server
     * selected" state).
     */
    suspend fun setServer(serverId: String, serverName: String) {
        val trimmedId = serverId.trim()
        val trimmedName = serverName.trim()
        store.edit { prefs ->
            if (trimmedId.isBlank()) {
                prefs.remove(DiscordKeys.SERVER_ID)
                prefs.remove(DiscordKeys.SERVER_NAME)
            } else {
                prefs[DiscordKeys.SERVER_ID] = trimmedId
                prefs[DiscordKeys.SERVER_NAME] = trimmedName
            }
        }
    }

    /** Persist the same-author coalesce window (minutes). Clamps to
     *  the documented slider bounds before storing. */
    suspend fun setCoalesceMinutes(minutes: Int) {
        val safe = minutes.coerceIn(
            DiscordDefaults.MIN_COALESCE_MINUTES,
            DiscordDefaults.MAX_COALESCE_MINUTES,
        )
        store.edit { it[DiscordKeys.COALESCE_MINUTES] = safe }
    }

    /**
     * Issue #517 — persist the TechEmpower-defaults toggle. When ON
     * (the default), the seed channel surfaces in Browse + on the
     * TechEmpower Home Featured guides strip (source-iterator
     * follow-up). When OFF, [DiscordConfigState.pinnedChannelIds] is
     * emitted as an empty list so callers don't need a second toggle
     * check.
     */
    suspend fun setTechEmpowerDefaultsEnabled(enabled: Boolean) {
        store.edit { it[DiscordKeys.TECHEMPOWER_DEFAULTS_ENABLED] = enabled }
    }

    /** Wipe server id, server name, coalesce override, and token —
     *  Settings "Forget Discord" path (no UI affordance yet; available
     *  for diagnostics + tests). After this call the source falls
     *  back to AuthRequired on every fetch. */
    suspend fun clear() {
        store.edit { prefs ->
            prefs.remove(DiscordKeys.SERVER_ID)
            prefs.remove(DiscordKeys.SERVER_NAME)
            prefs.remove(DiscordKeys.COALESCE_MINUTES)
            // Issue #517 — explicitly remove the TechEmpower-defaults
            // toggle on clear() so a re-init falls back to the bundled
            // default (ON). Same shape as the other plaintext fields.
            prefs.remove(DiscordKeys.TECHEMPOWER_DEFAULTS_ENABLED)
        }
        secrets.edit().remove(DISCORD_BOT_TOKEN_PREF).apply()
        secretsTick.value = secretsTick.value + 1
    }
}
