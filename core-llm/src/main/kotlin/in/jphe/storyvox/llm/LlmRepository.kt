package `in`.jphe.storyvox.llm

import `in`.jphe.storyvox.llm.provider.ClaudeApiProvider
import `in`.jphe.storyvox.llm.provider.OllamaProvider
import `in`.jphe.storyvox.llm.provider.OpenAiApiProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * High-level entry to the LLM subsystem. ViewModels inject this and
 * stream against the user's currently active provider, ignoring
 * which class is on the wire.
 *
 * Sessions can override the active provider — the multi-session
 * repository [LlmSessionRepository] passes a specific [ProviderId]
 * to [streamWith] rather than relying on global config.
 */
@Singleton
class LlmRepository @Inject constructor(
    private val configFlow: Flow<LlmConfig>,
    private val claude: ClaudeApiProvider,
    private val openAi: OpenAiApiProvider,
    private val ollama: OllamaProvider,
) {

    /** The provider currently picked in Settings, or null when AI
     *  is disabled. */
    val active: Flow<LlmProvider?> = configFlow.map { cfg ->
        cfg.provider?.let { providerFor(it) }
    }

    /** Stream against the active provider. Throws
     *  [LlmError.NotConfigured] if the user hasn't picked one. */
    fun stream(
        messages: List<LlmMessage>,
        systemPrompt: String? = null,
    ): Flow<String> = flow {
        val provider = active.first()
            ?: throw LlmError.NotConfigured(ProviderId.Claude)
        emitAll(provider.stream(messages, systemPrompt))
    }

    /** Stream against an explicit provider (for sessions bound to a
     *  specific provider regardless of global active config). */
    fun streamWith(
        provider: ProviderId,
        messages: List<LlmMessage>,
        systemPrompt: String? = null,
        model: String? = null,
    ): Flow<String> = flow {
        emitAll(providerFor(provider).stream(messages, systemPrompt, model))
    }

    /** Run a probe on the named provider. Returns
     *  [ProbeResult.Misconfigured] for spec-only providers since they
     *  have no implementation yet. */
    suspend fun probe(provider: ProviderId): ProbeResult =
        if (!provider.implemented) {
            ProbeResult.Misconfigured("${provider.displayName} is not yet implemented")
        } else {
            providerFor(provider).probe()
        }

    /** Look up a provider class by id. Spec-only providers throw —
     *  the Settings UI should not be calling this for them; it
     *  should be greying their selection out. */
    private fun providerFor(id: ProviderId): LlmProvider = when (id) {
        ProviderId.Claude -> claude
        ProviderId.OpenAi -> openAi
        ProviderId.Ollama -> ollama
        ProviderId.Bedrock,
        ProviderId.Vertex,
        ProviderId.Foundry,
        ProviderId.Teams ->
            throw LlmError.NotConfigured(id)
    }
}
