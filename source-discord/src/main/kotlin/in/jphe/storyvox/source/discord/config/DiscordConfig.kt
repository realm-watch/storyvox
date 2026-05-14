package `in`.jphe.storyvox.source.discord.config

import kotlinx.coroutines.flow.Flow

/**
 * Issue #403 — abstraction over the Discord source's persistent
 * config. Mirrors the leaf-source pattern from
 * [`in`.jphe.storyvox.source.notion.config.NotionConfig]: the source
 * module declares the interface + state shape; the host app supplies
 * the DataStore + EncryptedSharedPreferences-backed implementation.
 *
 * Auth model: **bot token only** (PAT-style). The user creates a
 * Discord application at discord.com/developers, generates a bot
 * token, invites their bot to the target server with the
 * `READ_MESSAGE_HISTORY` scope, and pastes the token into Settings →
 * Library & Sync → Discord. There is no anonymous mode (Discord
 * provides no public REST surface for guild content), and no
 * selfbot/user-token path (banned by Discord ToS).
 *
 * The token is stored in `storyvox.secrets` (Tink-backed
 * EncryptedSharedPreferences) under the `pref_source_discord_token`
 * key. Plaintext DataStore holds the non-secret bits: selected
 * server id + coalesce window.
 */
interface DiscordConfig {
    /** Hot stream of the current config state. */
    val state: Flow<DiscordConfigState>

    /** Synchronous snapshot for code paths that can't suspend. */
    suspend fun current(): DiscordConfigState
}

/**
 * One Discord config state. All three fields are user-controlled;
 * none have meaningful defaults that storyvox can ship out of the
 * box (no baked-in bot token, no default server id — that'd be a
 * ToS violation against any server-owner who didn't opt in).
 */
data class DiscordConfigState(
    /** Bot token from discord.com/developers. Empty means the source
     *  returns AuthRequired on every call. Stored encrypted; never
     *  exposed to the UI as a readable string (the UiSettings
     *  projection only carries a `tokenConfigured: Boolean`). */
    val apiToken: String = "",

    /** Selected Discord guild (server) id. The source treats this
     *  server's channels as the Browse catalog. Empty means the
     *  picker hasn't been used yet — Browse renders an empty state
     *  with a CTA to the Settings server picker. Discord guild ids
     *  are 18-19 digit snowflake integers stringified. */
    val serverId: String = "",

    /** Optional human-readable server name, captured at the moment
     *  the user picked the server, so the empty-state CTA can say
     *  "(Browsing TechEmpower)" rather than "Browsing 0123456789".
     *  Re-derived lazily on the next `users/@me/guilds` refresh. */
    val serverName: String = "",

    /** Coalesce window for message → chapter grouping (minutes).
     *  Consecutive messages from the same author within this window
     *  collapse into one chapter. Defaults to
     *  [DiscordDefaults.DEFAULT_COALESCE_MINUTES] (5 min). Slider
     *  range in Settings is 1-30. */
    val coalesceMinutes: Int = DiscordDefaults.DEFAULT_COALESCE_MINUTES,

    /** Discord REST API base URL. Defaults to discord.com; overridable
     *  for test fakes. */
    val baseUrl: String = DiscordDefaults.BASE_URL,

    /** API version segment (`v10` etc.). Pinned in the source rather
     *  than the user config so storyvox + Discord never drift apart
     *  silently across releases. */
    val apiVersion: String = DiscordDefaults.API_VERSION,
) {
    /** True when the source can make API calls. Requires both a
     *  configured bot token AND a selected server — without the
     *  server id we'd have nothing to list. */
    val isConfigured: Boolean
        get() = apiToken.isNotBlank() && serverId.isNotBlank()
}
