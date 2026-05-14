package `in`.jphe.storyvox.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
 *
 * Issue #215 — multi-modal extension. [parts] is a transient
 * (in-memory only, not serialized to the wire model — providers walk
 * the list and emit their own per-provider shape) sidecar that lets
 * a user message carry image+text content for one send round. The
 * persistence layer (Room) reads from [content] only, so an image
 * attached to a turn doesn't survive a process restart — that's a v1
 * trade-off. When [parts] is null the provider falls back to the
 * text-only [content] field, preserving the pre-#215 behaviour for
 * every text-only caller.
 */
@Serializable
data class LlmMessage(
    val role: Role,
    val content: String,
    /** Multi-modal content blocks (#215). When non-null, providers
     *  with image support serialize this list onto the wire instead
     *  of [content]. The text portion of [parts] (concatenated)
     *  should equal [content] so the storage layer's text-only view
     *  stays correct on rehydration. `@Transient` because [parts] is
     *  in-memory glue between the composer and the provider — never
     *  read from the wire model on its own, and Room doesn't store it. */
    @Transient
    val parts: List<LlmContentBlock>? = null,
) {
    @Serializable
    enum class Role { user, assistant }
}
