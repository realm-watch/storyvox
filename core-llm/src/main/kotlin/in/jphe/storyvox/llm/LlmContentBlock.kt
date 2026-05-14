package `in`.jphe.storyvox.llm

/**
 * Issue #215 — multi-modal content blocks for a chat message.
 *
 * For v1 we model only **text + image** because that's what the
 * implemented providers' multi-modal endpoints actually accept
 * (Anthropic Messages, OpenAI Chat Completions on gpt-4o+). Audio /
 * video blocks would slot in as new sealed-class variants when their
 * provider support is wired.
 *
 * The block list is the source of truth for what gets serialized to
 * the wire. [LlmMessage] keeps its plain-text [LlmMessage.content]
 * field for Room storage + legacy callers — when [LlmMessage.parts] is
 * non-null, providers serialize from the block list instead of the
 * string content.
 *
 * Image bytes are carried as base64 (rather than URIs / file paths)
 * because that's the format every provider's API actually wants on
 * the wire, and because the bytes need to survive a process hop
 * between the Compose-side picker and the OkHttp call inside the
 * provider. We resize + JPEG-encode before base64 to keep payload
 * under each provider's per-request size limit (~5 MB on Anthropic,
 * 20 MB on OpenAI, but we target ~500 KB as a kindness to slower
 * uplinks — see `ImageResizer`).
 */
sealed class LlmContentBlock {
    /** Plain text. The text-only fallback path for providers that
     *  don't support images wraps the message's [LlmMessage.content]
     *  in a single [Text] block. */
    data class Text(val content: String) : LlmContentBlock()

    /** Inline image, base64-encoded. [mimeType] must match the
     *  encoded bytes (e.g. `image/jpeg`, `image/png`, `image/webp`).
     *  Provider serializers branch on this — Anthropic wraps as
     *  `{type:"image", source:{type:"base64", media_type, data}}`,
     *  OpenAI as `{type:"image_url", image_url:{url:"data:<mt>;base64,<…>"}}`. */
    data class Image(
        val base64: String,
        val mimeType: String,
    ) : LlmContentBlock()
}
