package `in`.jphe.storyvox.llm

import `in`.jphe.storyvox.llm.tools.ChatStreamEvent
import `in`.jphe.storyvox.llm.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * One LLM backend. Implementations live in
 * [`in`.jphe.storyvox.llm.provider]: ClaudeApiProvider,
 * OpenAiApiProvider, OllamaProvider for v1.
 *
 * Mirrors the `cloud-chat-assistant/llm_stream.py` provider
 * abstraction: each implementation knows how to (a) build a streaming
 * HTTPS request for its provider's wire format and (b) parse SSE
 * events into text tokens. The ViewModel collects from a single
 * Flow<String> regardless of which provider's wire-format we're
 * behind.
 */
interface LlmProvider {

    /** Provider identity, used in Settings, error messages, logs. */
    val id: ProviderId

    /**
     * Stream a chat completion. Cold flow — collection starts the
     * HTTPS request; cancellation cancels the OkHttp Call so the
     * server stops billing characters.
     *
     * @param messages user/assistant turns. System prompt is its
     *   own parameter (not a message) because Anthropic puts it in
     *   a separate field — keeping it out of the list dodges
     *   provider-specific reshaping.
     * @param systemPrompt optional. Recap uses this for tone control.
     * @param model canonical model id (e.g. "claude-haiku-4.5",
     *   "llama3.3"). When null, falls back to the provider's
     *   configured default model on [LlmConfig].
     * @return cold Flow<String> emitting text deltas in arrival
     *   order. Completes normally on `[DONE]` or stream-end. Throws
     *   [LlmError] on transport / auth / config failure.
     */
    fun stream(
        messages: List<LlmMessage>,
        systemPrompt: String? = null,
        model: String? = null,
    ): Flow<String>

    /**
     * Lightweight reachability + auth probe. Used by Settings
     * "Test connection" + once at first-time activation. Does NOT
     * consume model time — Claude/OpenAI hit a tiny endpoint;
     * Ollama hits `/api/tags`. Errors surface as [ProbeResult]
     * variants rather than thrown — the probe is meant for
     * fire-and-forget UI use, not flow consumption.
     */
    suspend fun probe(): ProbeResult

    /**
     * Issue #216 — does this provider support function calling /
     * tool use? v1 ships with Anthropic (Claude direct + Teams) and
     * OpenAI only; other providers return false, and the chat layer
     * surfaces an empty-state message ("Tools not supported on this
     * provider — actions will not work") when the user attempts an
     * action.
     *
     * Override on the two supporting providers; the default false
     * keeps the rest of the matrix correct without per-provider
     * boilerplate.
     */
    val supportsTools: Boolean get() = false

    /**
     * Issue #215 — does this provider's wire format / configured
     * model accept image content blocks alongside text? v1 enables
     * this on Anthropic (Claude direct + Teams) and OpenAI (gpt-4o
     * family). Vertex / Bedrock / Foundry / Ollama leave it false;
     * the chat layer drops the attached image and surfaces a warning
     * banner when the user sends an image to an unsupported provider.
     */
    val supportsImages: Boolean get() = false

    /**
     * Issue #216 — tool-aware chat. Default impl falls back to
     * [stream] (no tools), wrapping each text emit as a
     * [ChatStreamEvent.TextDelta]. Providers that implement
     * [supportsTools] override this to drive the full
     * model→tool_call→tool_result→model loop and emit
     * [ChatStreamEvent.ToolCallStarted] / [ChatStreamEvent.ToolCallCompleted]
     * pairs in line with text deltas.
     *
     * @param tools registry of available tools + handlers. When
     *   empty, behaves identically to a tool-less chat.
     */
    fun chatWithTools(
        messages: List<LlmMessage>,
        systemPrompt: String? = null,
        model: String? = null,
        tools: ToolRegistry = ToolRegistry.EMPTY,
    ): Flow<ChatStreamEvent> = stream(messages, systemPrompt, model)
        .map { ChatStreamEvent.TextDelta(it) }
}
