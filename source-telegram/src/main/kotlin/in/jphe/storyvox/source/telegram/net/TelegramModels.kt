package `in`.jphe.storyvox.source.telegram.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Issue #462 — minimal Telegram Bot API response shapes.
 *
 * Only the fields storyvox actually reads are declared. The Bot
 * API returns dozens of optional fields per object (forward
 * metadata, reaction counts, sticker sets, etc.); declaring them
 * all would just be future-fragile noise. `Json.ignoreUnknownKeys
 * = true` drops what we don't ask for, and Telegram's API
 * guarantees additive evolution within a major version so unknown
 * new fields don't break us.
 *
 * Every Bot API response is wrapped in a `{"ok": true, "result":
 * ...}` envelope (or `{"ok": false, "description": "...",
 * "error_code": N}` on failure). The transport layer
 * ([TelegramApi]) unwraps `result` before handing the inner value
 * to the source layer.
 */

/**
 * The outer Bot API envelope. Generic over the inner payload type
 * because every method returns `{ok, result, error_code?,
 * description?}` with `result` shaped per-method. Generic
 * decoding is awkward in kotlinx.serialization without `reified`
 * machinery, so we decode the inner payload separately per
 * call site — this type carries the success / error metadata
 * fields only.
 */
@Serializable
internal data class TelegramEnvelope(
    val ok: Boolean = false,
    /** Bot API error code on `ok=false`. Documented set is small:
     *  400 (Bad Request), 401 (Unauthorized), 403 (Forbidden), 404
     *  (Not Found), 409 (Conflict), 429 (Too Many Requests). */
    @SerialName("error_code")
    val errorCode: Int? = null,
    /** Human-readable error description on `ok=false`. */
    val description: String? = null,
    /** Optional rate-limit metadata block (only present on 429). */
    val parameters: TelegramResponseParameters? = null,
    /** The method-specific result payload, kept as raw JSON so
     *  callers can decode it with their own target type. Null on
     *  error responses. */
    val result: JsonElement? = null,
)

/** Rate-limit hint Telegram supplies on 429 responses. The
 *  `retry_after` value is documented in seconds. */
@Serializable
internal data class TelegramResponseParameters(
    @SerialName("retry_after")
    val retryAfter: Int? = null,
)

/**
 * Result of `getMe`. Confirms the bot identity + carries the
 * bot's display name so the Settings card can show "Authenticated
 * as @storyvox_bot" after a token save.
 */
@Serializable
internal data class TelegramBotUser(
    val id: Long,
    @SerialName("is_bot")
    val isBot: Boolean = true,
    @SerialName("first_name")
    val firstName: String = "",
    val username: String? = null,
)

/**
 * One element of `getUpdates`. Each update reflects a recent
 * event for the bot — new channel posts (drives channel
 * discovery), edited posts, etc. v1 only consumes `channel_post`
 * + `edited_channel_post` for channel discovery; other event
 * types are ignored.
 */
@Serializable
internal data class TelegramUpdate(
    @SerialName("update_id")
    val updateId: Long,
    /** New channel post (broadcast-only channels emit these
     *  whenever the admin posts; channels the bot is a member of
     *  are the ones storyvox cares about). */
    @SerialName("channel_post")
    val channelPost: TelegramMessage? = null,
    /** Edited channel post — same payload shape as channel_post.
     *  Currently ignored at the source layer (we re-fetch chapter
     *  bodies on read so edits are picked up implicitly). */
    @SerialName("edited_channel_post")
    val editedChannelPost: TelegramMessage? = null,
)

/**
 * One element of `getChat`. Channel metadata used for the
 * fiction summary.
 */
@Serializable
internal data class TelegramChat(
    /** Telegram chat id. Channels use negative ids (e.g.
     *  -1001234567890); private chats / groups use positive ids.
     *  Storyvox v1 only surfaces channels (type == "channel"). */
    val id: Long,
    /** Chat type. "channel" for broadcast channels, "supergroup"
     *  for groups, "group" for legacy groups, "private" for DMs.
     *  v1 filters to "channel" — broadcast-style content is the
     *  audiobook fit. */
    val type: String = "",
    /** Channel title as the admin set it. Becomes the fiction
     *  title in storyvox. */
    val title: String = "",
    /** @-handle for public channels (e.g. "storyvox_official").
     *  Null for private channels even when the bot is a member. */
    val username: String? = null,
    /** Channel description / "About" text. Becomes the fiction
     *  description. May be null when the admin hasn't set one. */
    val description: String? = null,
)

/**
 * One element of `getUpdates` → `channel_post`. Each channel
 * post becomes one chapter in the channels-as-fictions mapping.
 */
@Serializable
internal data class TelegramMessage(
    @SerialName("message_id")
    val messageId: Long,
    /** Unix timestamp (seconds) the message was posted. */
    val date: Long,
    /** The chat (channel) the post belongs to. */
    val chat: TelegramChat,
    /** Plain-text message content. May be empty for media-only
     *  posts; `caption` is the text-with-media variant. */
    val text: String? = null,
    /** Caption text on media-bearing posts (photo/video/document
     *  + a textual caption). Surfaced as the chapter body when
     *  `text` is absent. */
    val caption: String? = null,
    /** Optional document attachment (the most generic media
     *  attachment type — photos use `photo`, videos use `video`,
     *  PDFs use `document`). v1 surfaces filenames in the chapter
     *  body so TTS narrates them. */
    val document: TelegramDocument? = null,
    /** Optional photo attachment. Telegram's API returns the same
     *  photo at multiple resolutions; we use only the file_id of
     *  the largest variant. */
    val photo: List<TelegramPhotoSize>? = null,
    /** Optional video attachment. */
    val video: TelegramVideo? = null,
    /** Optional audio attachment (music files; voice notes use
     *  `voice` which v1 ignores). */
    val audio: TelegramAudio? = null,
)

@Serializable
internal data class TelegramDocument(
    @SerialName("file_id")
    val fileId: String,
    @SerialName("file_name")
    val fileName: String? = null,
    @SerialName("mime_type")
    val mimeType: String? = null,
    @SerialName("file_size")
    val fileSize: Long? = null,
)

@Serializable
internal data class TelegramPhotoSize(
    @SerialName("file_id")
    val fileId: String,
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("file_size")
    val fileSize: Long? = null,
)

@Serializable
internal data class TelegramVideo(
    @SerialName("file_id")
    val fileId: String,
    @SerialName("file_name")
    val fileName: String? = null,
    val duration: Int = 0,
    @SerialName("mime_type")
    val mimeType: String? = null,
)

@Serializable
internal data class TelegramAudio(
    @SerialName("file_id")
    val fileId: String,
    val title: String? = null,
    val performer: String? = null,
    val duration: Int = 0,
)
