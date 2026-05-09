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
