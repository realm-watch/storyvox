package `in`.jphe.storyvox.source.telegram.config

import kotlinx.coroutines.flow.Flow

/**
 * Issue #462 — abstraction over the Telegram source's persistent
 * config. Mirrors the leaf-source pattern from `:source-discord` /
 * `:source-notion` / `:source-outline`: this module declares the
 * interface + state shape; the host app supplies the DataStore +
 * EncryptedSharedPreferences-backed implementation.
 *
 * Auth model: **bot token only** (Bot API). The user creates a bot
 * via @BotFather inside the Telegram app, receives a token like
 * `123456:ABC-DEF...`, pastes the token in Settings → Library &
 * Sync → Telegram. The bot then has to be added as a member to
 * each public channel the user wants storyvox to read (admin
 * invite, just like a normal channel member). No bundled default
 * token, no anonymous mode (Bot API requires auth on every call).
 *
 * The token is stored in `storyvox.secrets` (Tink-backed
 * EncryptedSharedPreferences) under the `pref_source_telegram_token`
 * key. There are no additional plaintext prefs in v1 — the
 * channel-list isn't user-configured (it's derived from observed
 * `getUpdates` activity).
 */
interface TelegramConfig {
    /** Hot stream of the current config state. */
    val state: Flow<TelegramConfigState>

    /** Synchronous snapshot for code paths that can't suspend. */
    suspend fun current(): TelegramConfigState
}

/**
 * One Telegram config state. The token field is user-controlled.
 * Base URL and user-agent come from [TelegramDefaults] but live in
 * the state shape so test fakes can override them without a separate
 * config injection plane.
 */
data class TelegramConfigState(
    /** Bot token from @BotFather. Format is `<bot_id>:<random>`
     *  (e.g. "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11"). Empty
     *  means the source returns AuthRequired on every call.
     *  Stored encrypted; never exposed to the UI as a readable
     *  string (the UiSettings projection only carries a
     *  `tokenConfigured: Boolean`). */
    val apiToken: String = "",

    /** Telegram Bot API base URL. Defaults to api.telegram.org;
     *  overridable for test fakes. */
    val baseUrl: String = TelegramDefaults.BASE_URL,
) {
    /** True when the source can make API calls. Only requirement
     *  is a configured token — channel discovery is driven by
     *  observed `getUpdates` so there's no separate "channel
     *  selected" state like Discord's server picker. */
    val isConfigured: Boolean
        get() = apiToken.isNotBlank()
}
