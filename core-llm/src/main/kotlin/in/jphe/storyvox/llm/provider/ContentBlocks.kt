package `in`.jphe.storyvox.llm.provider

import `in`.jphe.storyvox.llm.LlmContentBlock
import `in`.jphe.storyvox.llm.LlmMessage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Issue #215 — provider-side serializers for the multi-modal content
 * blocks introduced on [LlmMessage.parts]. Each provider has a slightly
 * different "what does the content array look like" shape, so this
 * file collects the per-provider transforms in one place.
 *
 * Two providers (Anthropic family + OpenAI) actually accept image
 * content blocks. The rest of the providers ignore [LlmMessage.parts]
 * — they read the text-only [LlmMessage.content] field and pretend the
 * image attachment never happened (the chat layer surfaces a warning
 * banner to the user before sending so this isn't a silent drop).
 */
internal object ContentBlocks {

    /**
     * Anthropic Messages API content-block shape.
     *
     * Text-only message (no [LlmMessage.parts]) → `null` (caller uses
     * the plain-string content path).
     * Image-bearing message → array of `{type:"image", source:{type:"base64", media_type, data}}`
     * + `{type:"text", text}` blocks, in the order Anthropic recommends
     * (image first then text — the model attends to the image while
     * reading the question).
     */
    fun anthropic(message: LlmMessage): List<JsonElement>? {
        val parts = message.parts ?: return null
        return parts.map { block ->
            when (block) {
                is LlmContentBlock.Text -> buildJsonObject {
                    put("type", "text")
                    put("text", block.content)
                }
                is LlmContentBlock.Image -> buildJsonObject {
                    put("type", "image")
                    put("source", buildJsonObject {
                        put("type", "base64")
                        put("media_type", block.mimeType)
                        put("data", block.base64)
                    })
                }
            }
        }
    }

    /**
     * OpenAI Chat Completions content-block shape. OpenAI accepts
     * `image_url` as a data URI, so the base64 + mime get spliced
     * into a single `data:<mime>;base64,<…>` string.
     *
     * Text-only message (no [LlmMessage.parts]) → `null`.
     */
    fun openAi(message: LlmMessage): List<JsonElement>? {
        val parts = message.parts ?: return null
        return parts.map { block ->
            when (block) {
                is LlmContentBlock.Text -> buildJsonObject {
                    put("type", "text")
                    put("text", block.content)
                }
                is LlmContentBlock.Image -> buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", "data:${block.mimeType};base64,${block.base64}")
                    })
                }
            }
        }
    }
}
