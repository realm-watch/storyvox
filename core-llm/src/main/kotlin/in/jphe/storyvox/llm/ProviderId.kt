package `in`.jphe.storyvox.llm

/**
 * The seven providers spec'd in `2026-05-08-ai-integration-design.md`.
 *
 * Three are implemented in the introducing PR: Claude direct, OpenAi,
 * Ollama. The remaining four ([Bedrock], [Vertex], [Foundry], [Teams])
 * are reserved enum slots; the Settings UI surfaces them as
 * "coming soon" rows that are visible but not selectable until the
 * corresponding provider class lands. The enum lives here in
 * :core-llm rather than in :core-data so the implemented set and the
 * full set live next to each other and stay in sync.
 */
enum class ProviderId {
    /** Anthropic Messages API direct, BYOK x-api-key. */
    Claude,
    /** OpenAI Chat Completions, BYOK Authorization: Bearer. */
    OpenAi,
    /** OpenAI-compat local endpoint, no auth. */
    Ollama,

    // ── Spec-only — see ai-integration-design.md "Provider matrix" ─────
    /** AWS Bedrock converse-stream, BYOK access key + SigV4 signer. */
    Bedrock,
    /** Google Vertex AI Gemini, BYOK API key in URL. */
    Vertex,
    /** Azure AI Foundry, BYOK endpoint + api-key header. */
    Foundry,
    /** Anthropic Teams, OAuth2 bearer token. */
    Teams;

    /** True if a real LlmProvider implementation exists for this id.
     *  False ids are surfaced as "coming soon" in the AI Settings. */
    val implemented: Boolean
        get() = this == Claude || this == OpenAi || this == Ollama ||
            this == Vertex || this == Foundry

    /** Human-friendly label for the Settings UI. Localization is a
     *  follow-up; English-only labels for now match the rest of the
     *  app. */
    val displayName: String
        get() = when (this) {
            Claude -> "Claude (Anthropic, BYOK)"
            OpenAi -> "OpenAI (BYOK)"
            Ollama -> "Ollama (local LAN)"
            Bedrock -> "AWS Bedrock"
            Vertex -> "Google Vertex AI"
            Foundry -> "Azure AI Foundry"
            Teams -> "Anthropic Teams (OAuth)"
        }
}
