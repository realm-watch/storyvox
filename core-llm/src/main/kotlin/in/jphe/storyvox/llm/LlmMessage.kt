package `in`.jphe.storyvox.llm

import kotlinx.serialization.Serializable

/**
 * Wire-layer chat message. Distinct from the Room-stored
 * [`in`.jphe.storyvox.data.db.entity.LlmStoredMessage] entity — wire
 * messages don't carry an id or timestamp. Storage layer converts.
 *
 * Anthropic's Messages API places the system prompt outside the
 * messages array (top-level `system` field) while OpenAI/Ollama put
 * it as a `system` role inside the array. This type intentionally
 * does NOT have a `system` role — the provider impl handles
 * placement, so callers don't have to reshape input per provider.
 */
@Serializable
data class LlmMessage(
    val role: Role,
    val content: String,
) {
    @Serializable
    enum class Role { user, assistant }
}
