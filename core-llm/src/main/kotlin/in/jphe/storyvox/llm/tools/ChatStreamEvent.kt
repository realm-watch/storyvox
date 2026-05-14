package `in`.jphe.storyvox.llm.tools

/**
 * Issue #216 — rich event stream from the tool-aware chat path.
 * Distinct from `Flow<String>` (text-only stream) because the chat UI
 * needs to render tool-call cards in-line as they happen, not after
 * the fact.
 *
 * Order guarantee: events arrive in the order the model emitted
 * them — interleaved [TextDelta] + [ToolCallStarted] / [ToolCallCompleted]
 * pairs. The UI can map this directly onto a turn timeline.
 */
sealed class ChatStreamEvent {
    /** A text token (or chunk) from the assistant. Same semantics as
     *  the legacy `Flow<String>` emit. */
    data class TextDelta(val text: String) : ChatStreamEvent()

    /** Model decided to call a tool. The corresponding
     *  [ToolCallCompleted] follows after the handler finishes. */
    data class ToolCallStarted(val call: ToolCallRequest) : ChatStreamEvent()

    /** Tool handler finished. The chat layer reissues the request to
     *  the model with the tool result threaded in; the model's next
     *  text response arrives as [TextDelta] emits after this. */
    data class ToolCallCompleted(
        val id: String,
        val name: String,
        val result: ToolResult,
    ) : ChatStreamEvent()
}
