package `in`.jphe.storyvox.llm.provider

import `in`.jphe.storyvox.llm.LlmContentBlock
import `in`.jphe.storyvox.llm.LlmMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #215 — content-block serialization tests for the two
 * providers that accept image input on the wire (Anthropic family
 * + OpenAI).
 *
 * Snapshot-style assertions on the JSON shape because the test
 * surface here is wire-format compliance — if the schema breaks,
 * every multi-modal chat will 400 against the provider's API and
 * the assertion gives us a localized diff to read.
 */
class ContentBlocksTest {

    @Test
    fun `anthropic content for text-only message is null`() {
        val msg = LlmMessage(LlmMessage.Role.user, "no image here")
        assertNull(
            "messages without parts should signal text-only path to the provider",
            ContentBlocks.anthropic(msg),
        )
    }

    @Test
    fun `anthropic content serialises image then text block`() {
        val msg = LlmMessage(
            role = LlmMessage.Role.user,
            content = "what do you see?",
            parts = listOf(
                LlmContentBlock.Image(
                    base64 = "AAAA",
                    mimeType = "image/jpeg",
                ),
                LlmContentBlock.Text("what do you see?"),
            ),
        )
        val blocks = ContentBlocks.anthropic(msg)
        requireNotNull(blocks)
        assertEquals(2, blocks.size)

        // Block 0 — Anthropic image shape.
        val img = blocks[0].jsonObject
        assertEquals("image", img["type"]?.jsonPrimitive?.contentOrNull)
        val source = img["source"]!!.jsonObject
        assertEquals("base64", source["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("image/jpeg", source["media_type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("AAAA", source["data"]?.jsonPrimitive?.contentOrNull)

        // Block 1 — text.
        val text = blocks[1].jsonObject
        assertEquals("text", text["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "what do you see?",
            text["text"]?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test
    fun `openai content for text-only message is null`() {
        val msg = LlmMessage(LlmMessage.Role.user, "no image here")
        assertNull(ContentBlocks.openAi(msg))
    }

    @Test
    fun `openai content serialises image then text block as data URI`() {
        val msg = LlmMessage(
            role = LlmMessage.Role.user,
            content = "transcribe this",
            parts = listOf(
                LlmContentBlock.Image(
                    base64 = "ZGF0YQ==",  // "data" in base64
                    mimeType = "image/png",
                ),
                LlmContentBlock.Text("transcribe this"),
            ),
        )
        val blocks = ContentBlocks.openAi(msg)
        requireNotNull(blocks)
        assertEquals(2, blocks.size)

        // Block 0 — OpenAI image_url shape with data URI.
        val img = blocks[0].jsonObject
        assertEquals("image_url", img["type"]?.jsonPrimitive?.contentOrNull)
        val imageUrl = img["image_url"]!!.jsonObject
        assertEquals(
            "data:image/png;base64,ZGF0YQ==",
            imageUrl["url"]?.jsonPrimitive?.contentOrNull,
        )

        // Block 1 — text.
        val text = blocks[1].jsonObject
        assertEquals("text", text["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "transcribe this",
            text["text"]?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test
    fun `openai content preserves arbitrary block ordering`() {
        // The chat layer puts image first then text, but the
        // serializer must respect whatever order the caller gave us
        // — a future "transcribe + summarize" path could send text
        // first.
        val msg = LlmMessage(
            role = LlmMessage.Role.user,
            content = "describe this scene",
            parts = listOf(
                LlmContentBlock.Text("describe this scene"),
                LlmContentBlock.Image(
                    base64 = "Zm9v",
                    mimeType = "image/webp",
                ),
            ),
        )
        val blocks = ContentBlocks.openAi(msg)
        requireNotNull(blocks)
        assertEquals(2, blocks.size)
        assertEquals(
            "text",
            blocks[0].jsonObject["type"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(
            "image_url",
            blocks[1].jsonObject["type"]?.jsonPrimitive?.contentOrNull,
        )
    }
}
