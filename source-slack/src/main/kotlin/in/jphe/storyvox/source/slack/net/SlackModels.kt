package `in`.jphe.storyvox.source.slack.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Issue #454 — minimal Slack Web API response shapes.
 *
 * Only the fields storyvox actually reads are declared. Slack's API
 * returns dozens of fields per object (channel boost flags, member
 * counts, shared-channel metadata, etc.); declaring them all would
 * just be future-fragile noise. `Json.ignoreUnknownKeys = true`
 * drops what we don't ask for, and Slack's API guarantees additive
 * evolution within the documented stable surface so unknown new
 * fields don't break us.
 *
 * Every Slack Web API response carries an `{"ok": true, ...}` /
 * `{"ok": false, "error": "..."}` envelope. The transport layer
 * ([SlackApi]) inspects `ok` before handing the result to the
 * source layer.
 *
 * **Cursor pagination**: list-shaped endpoints (`conversations.list`,
 * `conversations.history`) return a `response_metadata.next_cursor`
 * string. When non-empty, the next page is fetched with `cursor=<…>`;
 * an empty cursor means "no more pages". Slack's cursors are opaque
 * tokens, not before/after timestamps like Discord uses.
 */

/**
 * Result of `auth.test`. Confirms the bot token + carries workspace
 * metadata used to populate the Settings "authenticated as @bot in
 * <workspace>" confirmation.
 */
@Serializable
internal data class SlackAuthTest(
    val ok: Boolean = false,
    /** Free-form error code on `ok=false` (e.g. `"invalid_auth"`,
     *  `"not_authed"`, `"token_revoked"`). */
    val error: String? = null,
    /** Workspace name as set by the workspace admin. */
    val team: String? = null,
    /** Workspace id (`T012345`). */
    @SerialName("team_id")
    val teamId: String? = null,
    /** Bot user id within the workspace (`U012345` or `B012345`). */
    @SerialName("user_id")
    val userId: String? = null,
    /** Bot user display name. */
    val user: String? = null,
    /** Workspace URL (`https://techempower.slack.com/`). Trailing
     *  slash included. */
    val url: String? = null,
)

/**
 * Response shape for `conversations.list`. Wraps a list of
 * [SlackChannel] plus a `response_metadata.next_cursor` for
 * pagination.
 */
@Serializable
internal data class SlackConversationsList(
    val ok: Boolean = false,
    val error: String? = null,
    val channels: List<SlackChannel> = emptyList(),
    @SerialName("response_metadata")
    val responseMetadata: SlackResponseMetadata? = null,
)

/**
 * One element of `conversations.list`. Each public/private channel
 * the bot has access to. v1 surfaces channels the bot is a member of
 * (`is_member == true`); channels listed but un-joined are filtered
 * out at the source layer.
 */
@Serializable
internal data class SlackChannel(
    /** Channel id, e.g. `C012345` (public) or `G012345` /
     *  `C0123ABC` (private — Slack unified the prefix to `C` in
     *  2018 but legacy `G` ids may still surface for very old
     *  private channels). */
    val id: String,
    /** Channel name (without the `#` prefix). */
    val name: String = "",
    /** True for public channels in this workspace. */
    @SerialName("is_channel")
    val isChannel: Boolean = false,
    /** True for private channels. v1 surfaces both shapes
     *  identically — the difference is only relevant to admins
     *  picking what to install the bot into. */
    @SerialName("is_private")
    val isPrivate: Boolean = false,
    /** True when the bot is a member of the channel. v1 filters
     *  to is_member=true since bots can only read channels
     *  they've been invited to. */
    @SerialName("is_member")
    val isMember: Boolean = false,
    /** True for archived channels — read-only, no new messages.
     *  v1 still surfaces them (archived channels may contain
     *  the most interesting historical content) but a follow-up
     *  could hide them by default. */
    @SerialName("is_archived")
    val isArchived: Boolean = false,
    /** Channel topic — `{value, creator, last_set}`. v1 reads
     *  only the value text. */
    val topic: SlackTextBlock? = null,
    /** Channel purpose — same shape as topic, slightly different
     *  semantic ("what is this channel for" vs "current topic").
     *  v1 surfaces topic preferentially, falling back to purpose
     *  when topic is empty. */
    val purpose: SlackTextBlock? = null,
    /** Unix-seconds timestamp of channel creation. Unused in v1
     *  but cheap to parse. */
    val created: Long = 0,
    /** Approximate member count. Unused in v1. */
    @SerialName("num_members")
    val numMembers: Int = 0,
)

/** Slack's `{value, creator, last_set}` shape used for both topic
 *  and purpose on a channel. */
@Serializable
internal data class SlackTextBlock(
    val value: String = "",
    val creator: String = "",
    @SerialName("last_set")
    val lastSet: Long = 0,
)

/**
 * Response shape for `conversations.history`. Wraps a list of
 * [SlackMessage] in newest-first order plus
 * `response_metadata.next_cursor` for pagination.
 */
@Serializable
internal data class SlackConversationsHistory(
    val ok: Boolean = false,
    val error: String? = null,
    val messages: List<SlackMessage> = emptyList(),
    /** True when more messages exist beyond this page — Slack
     *  exposes this in addition to `response_metadata.next_cursor`
     *  so callers can decide whether to keep walking without
     *  inspecting the cursor string. */
    @SerialName("has_more")
    val hasMore: Boolean = false,
    @SerialName("response_metadata")
    val responseMetadata: SlackResponseMetadata? = null,
)

/**
 * One message in a channel's history. Each message becomes one
 * chapter in the channels-as-fictions mapping (v1 doesn't
 * coalesce — Slack's UX is one-thought-per-message far more often
 * than Discord's, where short bursts of replies are common).
 */
@Serializable
internal data class SlackMessage(
    /** Slack message timestamp — string-encoded float seconds since
     *  epoch (e.g. `"1747340531.123456"`). Doubles as the message id
     *  within a channel (no separate `id` field; `ts` is unique
     *  per-channel). The source layer uses `ts` for the chapter id
     *  + chronological ordering. */
    val ts: String = "",
    /** Message subtype. Empty for regular user messages. Non-empty
     *  for bot/system messages: `"channel_join"`, `"channel_leave"`,
     *  `"bot_message"`, `"pinned_item"`, `"thread_broadcast"`, etc.
     *  The source layer filters out system subtypes (keep regular
     *  user messages + bot messages with text content). */
    val subtype: String? = null,
    /** Slack user id of the message author (e.g. `U012345`). Null
     *  for some bot messages where `bot_id` is used instead. */
    val user: String? = null,
    /** Display name for bot messages — Slack populates this when
     *  the sender is a bot rather than a user. */
    val username: String? = null,
    /** Bot id when the sender is a bot. */
    @SerialName("bot_id")
    val botId: String? = null,
    /** Message text. Slack's mrkdwn flavor; may contain
     *  `<@U012345>` user mentions and `<#C012345|channel-name>`
     *  channel mentions. v1 keeps these as-is in the chapter body
     *  (the reader view + TTS pronounce them as raw tokens; a
     *  follow-up could resolve them via `users.info`). */
    val text: String = "",
    /** Optional file attachments. Slack returns full file metadata
     *  (name, mimetype, url_private, thumbnail urls). v1 surfaces
     *  the filename + title in the chapter body so TTS narrates
     *  them ("Attachment: dragon-sketch.png"). */
    val files: List<SlackFile>? = null,
    /** Thread parent ts for thread replies. Equal to [ts] for
     *  thread parents themselves. v1 treats threaded replies as
     *  flat messages within the parent channel (Discord-parity);
     *  a follow-up could surface threads as nested fictions. */
    @SerialName("thread_ts")
    val threadTs: String? = null,
)

@Serializable
internal data class SlackFile(
    val id: String = "",
    val name: String? = null,
    val title: String? = null,
    val mimetype: String? = null,
    /** File size in bytes. */
    val size: Long? = null,
)

/**
 * Response shape for `users.info`. Returns one user's full profile.
 * v1 reads only the display name fields for `<@U012345>` mention
 * resolution; the rest of the profile (real name, avatar, email,
 * timezone) is unused but `ignoreUnknownKeys = true` drops it
 * cleanly.
 */
@Serializable
internal data class SlackUserInfoResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val user: SlackUser? = null,
)

@Serializable
internal data class SlackUser(
    val id: String = "",
    val name: String = "",
    @SerialName("real_name")
    val realName: String? = null,
    val profile: SlackUserProfile? = null,
    /** True when the user is a deactivated account. */
    val deleted: Boolean = false,
    /** True when the user account is actually a bot. Bot mentions
     *  via `<@U…>` resolve through the same endpoint. */
    @SerialName("is_bot")
    val isBot: Boolean = false,
)

@Serializable
internal data class SlackUserProfile(
    @SerialName("display_name")
    val displayName: String? = null,
    @SerialName("display_name_normalized")
    val displayNameNormalized: String? = null,
    @SerialName("real_name")
    val realName: String? = null,
    @SerialName("real_name_normalized")
    val realNameNormalized: String? = null,
)

/** Cursor envelope shared across paginated list endpoints. */
@Serializable
internal data class SlackResponseMetadata(
    @SerialName("next_cursor")
    val nextCursor: String? = null,
)

/** Generic Slack error envelope for surfaces where the API
 *  doesn't return a structured body shape (`ok=false` with just
 *  `error` and optional warnings). Decoded for human-readable
 *  surfacing in the [`in`.jphe.storyvox.data.source.model.FictionResult.Failure]
 *  message field. */
@Serializable
internal data class SlackError(
    val ok: Boolean = false,
    val error: String = "",
    val warning: String? = null,
)
