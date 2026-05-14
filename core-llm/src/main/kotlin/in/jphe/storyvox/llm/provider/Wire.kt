package `in`.jphe.storyvox.llm.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-format data classes for the implemented providers.
 * Anthropic, OpenAI/Ollama, and Vertex/Gemini all have distinct
 * shapes; each is kept narrow (only the fields we send) so we don't
 * accidentally over-specify and break on minor API changes.
 *
 * Spec-only providers (Bedrock, Foundry, Teams) get their own wire
 * types when those classes ship.
 */

// ── Anthropic Messages API ──────────────────────────────────────────

@Serializable
internal data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<AnthropicMessage>,
    val system: String? = null,
    val stream: Boolean = true,
)

@Serializable
internal data class AnthropicMessage(
    val role: String,        // "user" or "assistant"
    val content: String,
)

// ── OpenAI Chat Completions (also Ollama, DigitalOcean, etc.) ──────

@Serializable
internal data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    @SerialName("max_tokens") val maxTokens: Int = 1024,
    val stream: Boolean = true,
)

@Serializable
internal data class OpenAiMessage(
    val role: String,        // "system", "user", or "assistant"
    val content: String,
)

// ── Google Vertex AI / Gemini ───────────────────────────────────────

@Serializable
internal data class VertexRequest(
    val contents: List<VertexContent>,
    @SerialName("system_instruction") val systemInstruction: VertexContent? = null,
    val generationConfig: VertexGenerationConfig? = null,
)

@Serializable
internal data class VertexContent(
    /** "user" or "model" — Gemini calls the assistant role "model".
     *  Optional on `system_instruction` (Gemini ignores it there). */
    val role: String? = null,
    val parts: List<VertexPart>,
)

@Serializable
internal data class VertexPart(
    val text: String,
)

@Serializable
internal data class VertexGenerationConfig(
    val temperature: Float? = null,
    @SerialName("maxOutputTokens") val maxOutputTokens: Int? = null,
)

// ── Azure Foundry deployed-mode chat completions ───────────────────
//
// Same shape as [OpenAiRequest] minus `model` — Foundry pins the
// model in the URL (`/openai/deployments/{name}/...`) and rejects
// requests that also carry the field in the body. Serverless-mode
// Foundry reuses [OpenAiRequest] verbatim. Tested against
// `cloud-chat-assistant/llm_stream.py`'s `azure` branch (line ~272).

@Serializable
internal data class FoundryDeployedRequest(
    val messages: List<OpenAiMessage>,
    @SerialName("max_tokens") val maxTokens: Int = 1024,
    val stream: Boolean = true,
)

// ── Tool-aware Anthropic (#216) ─────────────────────────────────────
//
// Distinct shape from [AnthropicRequest] because Anthropic's tool-use
// path requires the content field to be an array of typed blocks
// (text / tool_use / tool_result) rather than a single string. We
// keep [AnthropicRequest] for the plain-text path (cheaper, easier to
// debug) and use these when at least one tool is registered.

@Serializable
internal data class AnthropicToolRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<AnthropicToolMessage>,
    val system: String? = null,
    val tools: List<AnthropicToolDecl>? = null,
    val stream: Boolean = false,
)

@Serializable
internal data class AnthropicToolMessage(
    val role: String,        // "user" or "assistant"
    val content: List<kotlinx.serialization.json.JsonElement>,
)

@Serializable
internal data class AnthropicToolDecl(
    val name: String,
    val description: String,
    @SerialName("input_schema")
    val inputSchema: kotlinx.serialization.json.JsonObject,
)

// ── Tool-aware OpenAI (#216) ────────────────────────────────────────
//
// OpenAI's chat completions schema accepts an optional `tools` array
// + per-message `tool_calls` array; messages with `role: "tool"`
// carry `tool_call_id` + result content. We keep [OpenAiRequest]
// for the plain-text path and use these when tools are registered.

@Serializable
internal data class OpenAiToolRequest(
    val model: String,
    val messages: List<kotlinx.serialization.json.JsonObject>,
    @SerialName("max_tokens") val maxTokens: Int = 1024,
    val tools: List<OpenAiToolDecl>? = null,
    val stream: Boolean = false,
)

@Serializable
internal data class OpenAiToolDecl(
    /** Always emitted (no default) so a strict OpenAI receiver that
     *  reads `type` first sees it on the wire. The OpenAI API does in
     *  practice accept a missing type field, but `function` is the
     *  only documented value, and emitting it is one byte cheaper to
     *  reason about. */
    val type: String,
    val function: OpenAiToolFunction,
)

@Serializable
internal data class OpenAiToolFunction(
    val name: String,
    val description: String,
    val parameters: kotlinx.serialization.json.JsonObject,
)

// ── AWS Bedrock converse-stream ────────────────────────────────────
//
// Body shape: { modelId, messages:[{role, content:[{text}]}],
//   inferenceConfig:{maxTokens, temperature}, system:[{text}] }
// Bedrock requires the content array even for text-only messages (room
// for image / tool_use blocks down the line).

@Serializable
internal data class BedrockRequest(
    val modelId: String,
    val messages: List<BedrockMessage>,
    val inferenceConfig: BedrockInferenceConfig,
    val system: List<BedrockTextBlock>? = null,
)

@Serializable
internal data class BedrockMessage(
    val role: String,        // "user" or "assistant"
    val content: List<BedrockTextBlock>,
)

@Serializable
internal data class BedrockTextBlock(val text: String)

@Serializable
internal data class BedrockInferenceConfig(
    val maxTokens: Int,
    val temperature: Double = 1.0,
)
