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
 *
 * **Issue #517 — TechEmpower seed channel bot-token UX**: storyvox
 * does NOT ship a TechEmpower-owned bot token (it'd be a credential-
 * leak risk and a ToS-posture problem — see commit history for the
 * security rationale). For the TechEmpower peer-support channel
 * (id [DiscordDefaults.TECHEMPOWER_PEER_SUPPORT_CHANNEL_ID]) to
 * render messages, the user must:
 *
 *   1. Create their own Discord application at
 *      discord.com/developers → Applications → New Application.
 *   2. Add a Bot to it (Bot tab → Add Bot) and copy the token.
 *   3. Invite the bot to TechEmpower's Discord server with the
 *      `READ_MESSAGE_HISTORY` scope on the peer-support channel.
 *      (TechEmpower's server admins maintain a published "storyvox
 *      reader bot" invite link for users who'd rather not run their
 *      own bot — see TechEmpower Home → Peer Support card copy.)
 *   4. Paste the bot token into Settings → Library & Sync → Discord.
 *
 * The seed channel surfaces in Browse + on TechEmpower Home as soon
 * as the token is in. Without a bot invited to the channel, the
 * chapter list comes back empty (Discord returns []) — storyvox
 * renders an empty-state with a CTA pointing at TechEmpower Home's
 * Peer Support card for the documented invite link.
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

    /**
     * Issue #517 — channel ids that storyvox surfaces as fictions in
     * the Discord Browse list regardless of which server the user has
     * picked. Today's seed is the TechEmpower peer-support channel
     * (see [DiscordDefaults.TECHEMPOWER_PEER_SUPPORT_CHANNEL_ID]); the
     * list shape lets us extend the seed without a state migration.
     *
     * Empty when [techempowerDefaultsEnabled] is false (the user opted
     * out via Settings) so callers can take an unconditional
     * `state.pinnedChannelIds` walk without needing a second toggle
     * check.
     */
    val pinnedChannelIds: List<String> = DiscordDefaults.DEFAULT_PINNED_CHANNEL_IDS,

    /**
     * Issue #517 — opt-in for the TechEmpower default-channel seeds.
     * Defaults to true on fresh installs (so the peer-support channel
     * is discoverable the moment Discord is configured); users can
     * disable via Settings → Library & Sync → Discord.
     *
     * When false, [pinnedChannelIds] is emitted as an empty list by
     * [DiscordConfigImpl] so downstream `popular()` calls don't need
     * to special-case the toggle.
     */
    val techempowerDefaultsEnabled: Boolean = DiscordDefaults.DEFAULT_TECHEMPOWER_DEFAULTS_ENABLED,
) {
    /** True when the source can make API calls. Requires both a
     *  configured bot token AND a selected server — without the
     *  server id we'd have nothing to list. */
    val isConfigured: Boolean
        get() = apiToken.isNotBlank() && serverId.isNotBlank()
}
