package `in`.jphe.storyvox.llm.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-format data classes for the three implemented providers.
 * Anthropic and OpenAI/Ollama have different shapes; both are kept
 * narrow (only the fields we send) so we don't accidentally
 * over-specify and break on minor API changes.
 *
 * Spec-only providers (Bedrock, Vertex, Foundry, Teams) get their
 * own wire types when those classes ship.
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
