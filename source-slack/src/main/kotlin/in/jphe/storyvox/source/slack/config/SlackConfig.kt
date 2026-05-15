package `in`.jphe.storyvox.source.slack.config

import kotlinx.coroutines.flow.Flow

/**
 * Issue #454 — abstraction over the Slack source's persistent
 * config. Mirrors the leaf-source pattern from `:source-discord` /
 * `:source-telegram` / `:source-notion` / `:source-outline`: this
 * module declares the interface + state shape; the host app supplies
 * the EncryptedSharedPreferences-backed implementation.
 *
 * Auth model: **Bot Token only** (Slack OAuth `xoxb-…`). The user
 * creates a Slack app at api.slack.com, installs it to a workspace
 * they're a member of (or admin of, for shared deployments), and
 * pastes the **Bot User OAuth Token** into Settings → Library &
 * Sync → Slack. Read scopes the bot needs: `channels:read`,
 * `channels:history`, `groups:read`, `groups:history`, `users:read`.
 *
 * One token = one workspace. Slack's API (unlike Discord's
 * `users/@me/guilds`) doesn't expose a "list of workspaces this token
 * spans"; the token IS the workspace selector. The optional
 * `workspaceName` / `workspaceUrl` legs cache the human-readable
 * workspace label so the empty-state copy can name the workspace
 * without an extra `auth.test` round-trip.
 *
 * No bundled default token, no anonymous mode (Slack provides no
 * public REST surface for workspace content), and no legacy
 * user-token / xoxc cookie path (banned by Slack's API ToS).
 *
 * The token is stored in `storyvox.secrets` (Tink-backed
 * EncryptedSharedPreferences) under the `pref_source_slack_token`
 * key. There are no plaintext prefs that affect the source's wire
 * calls in v1 — the channel list comes from `conversations.list`,
 * not user-configured.
 */
interface SlackConfig {
    /** Hot stream of the current config state. */
    val state: Flow<SlackConfigState>

    /** Synchronous snapshot for code paths that can't suspend. */
    suspend fun current(): SlackConfigState
}

/**
 * One Slack config state. Bot token is user-controlled; workspace
 * name/url are derived from `auth.test` and cached for empty-state
 * copy. Base URL is overridable for test fakes.
 */
data class SlackConfigState(
    /** Slack Bot User OAuth Token. Format: `xoxb-<digits>-<digits>-<random>`
     *  (e.g. `xoxb-1234567890-1234567890123-AbCdEfGhIjKl`). Empty
     *  means the source returns AuthRequired on every call. Stored
     *  encrypted; never exposed to the UI as a readable string
     *  (the UiSettings projection only carries a
     *  `tokenConfigured: Boolean`). */
    val apiToken: String = "",

    /** Optional human-readable workspace name, captured at
     *  authentication time from `auth.test`'s `team` field, so the
     *  empty-state CTA can say "Browsing TechEmpower" rather than
     *  blank. Re-derived lazily on the next `auth.test` refresh. */
    val workspaceName: String = "",

    /** Optional workspace URL (e.g. `https://techempower.slack.com/`)
     *  captured at authentication time from `auth.test`'s `url`
     *  field. Used both for empty-state copy and as the canonical
     *  host pattern for [`in`.jphe.storyvox.source.slack.SlackSource.matchUrl]. */
    val workspaceUrl: String = "",

    /** Slack Web API base URL. Defaults to slack.com; overridable
     *  for test fakes. */
    val baseUrl: String = `in`.jphe.storyvox.source.slack.config.SlackDefaults.BASE_URL,
) {
    /** True when the source can make API calls. Only requirement is
     *  a configured bot token — channel discovery is automatic via
     *  `conversations.list`, no server picker like Discord. */
    val isConfigured: Boolean
        get() = apiToken.isNotBlank()
}
