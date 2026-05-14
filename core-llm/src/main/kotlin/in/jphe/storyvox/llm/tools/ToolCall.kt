package `in`.jphe.storyvox.llm.tools

import kotlinx.serialization.json.JsonObject

/**
 * Issue #216 — wire-shaped record of one tool invocation requested by
 * the AI. Captured during stream parsing; passed to the matching
 * [ToolHandler] for execution.
 *
 * [id] is the provider-issued call id. Anthropic ships `tool_use.id`
 * (e.g. `toolu_01ABC...`); OpenAI ships `tool_calls[].id`
 * (e.g. `call_abc123`). We round-trip it back unchanged in the
 * follow-up message so the provider can correlate the result.
 */
data class ToolCallRequest(
    val id: String,
    val name: String,
    /** Parsed JSON arguments. Always non-null; empty object when the
     *  tool takes no parameters. The [ToolHandler] is responsible for
     *  pulling typed values out and validating them. */
    val arguments: JsonObject,
)

/**
 * Output of a [ToolHandler.execute]. Threaded back into the chat as
 * a tool-result message so the model can react ("Added — anything
 * else?" / "I couldn't find that book in your library.").
 */
sealed class ToolResult {
    abstract val message: String

    /** Tool ran successfully. [message] is a short natural-language
     *  result the AI can echo back ("Added \"Frankenstein\" to your
     *  Reading shelf."). Keep it under ~140 chars — it ends up
     *  inlined in the AI's reply context window. */
    data class Success(override val message: String) : ToolResult()

    /** Tool failed (invalid args, missing entity, provider not
     *  reachable, etc.). The model will typically apologize + ask
     *  for clarification. */
    data class Error(override val message: String) : ToolResult()
}

/**
 * One round of "AI called a tool" surfaced through the UI layer. The
 * chat screen renders a brass-edged card per event. After settling
 * (Success or Error), the card collapses to a one-line summary.
 *
 * Distinct from [ToolCallRequest] (wire shape) because the UI also
 * wants to know "did this finish yet?" — `result` is null while the
 * handler is running.
 */
data class ToolCallEvent(
    val id: String,
    val name: String,
    val arguments: JsonObject,
    /** null while the handler is in flight; set on completion. */
    val result: ToolResult? = null,
)
