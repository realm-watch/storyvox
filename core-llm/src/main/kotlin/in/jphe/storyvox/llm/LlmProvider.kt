package `in`.jphe.storyvox.llm

import kotlinx.coroutines.flow.Flow

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
}
