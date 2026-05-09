package `in`.jphe.storyvox.llm.provider

import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmCredentialsStore
import `in`.jphe.storyvox.llm.LlmError
import `in`.jphe.storyvox.llm.LlmMessage
import `in`.jphe.storyvox.llm.LlmProvider
import `in`.jphe.storyvox.llm.ProbeResult
import `in`.jphe.storyvox.llm.ProviderId
import `in`.jphe.storyvox.llm.di.LlmHttp
import `in`.jphe.storyvox.llm.sse.SseLineParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Azure AI Foundry chat-completions client. Two URL shapes depending
 * on which Azure deployment model the user picked:
 *
 *  - **Deployed** ("Azure OpenAI Service"-style): the model is baked
 *    into the URL as a deployment name; the request body omits the
 *    `model` field. Used for OpenAI models served through Azure.
 *  - **Serverless** ("Azure AI model catalog"-style): a single
 *    `/models/chat/completions` endpoint that takes the model id in
 *    the body. Used for Llama / Phi / DeepSeek / Grok / Cohere /
 *    Mistral via Azure's pay-per-token catalog.
 *
 * Wire format is otherwise identical to OpenAI's chat-completions —
 * same SSE shape, same body shape (modulo the `model` field). We
 * reuse [SseLineParser.openAiCompat] and [OpenAiRequest] / [OpenAiMessage].
 *
 * Auth is **`api-key: <key>`** as a header — Azure rejects
 * `Authorization: Bearer` on these endpoints. Ported from
 * `cloud-chat-assistant/llm_stream.py`'s `azure` branch.
 *
 * Distinct from `:source-azure` (TTS via Azure Speech). This is
 * `:core-llm` LLM inference; the two share nothing beyond a brand.
 */
@Singleton
open class AzureFoundryProvider @Inject constructor(
    @LlmHttp private val http: OkHttpClient,
    private val store: LlmCredentialsStore,
    private val configFlow: Flow<LlmConfig>,
    private val json: Json,
) : LlmProvider {

    override val id: ProviderId = ProviderId.Foundry

    override fun stream(
        messages: List<LlmMessage>,
        systemPrompt: String?,
        model: String?,
    ): Flow<String> = flow {
        val cfg = configFlow.first()
        val apiKey = store.foundryApiKey()
            ?: throw LlmError.NotConfigured(ProviderId.Foundry)
        val endpoint = cfg.foundryEndpoint.takeIf { it.isNotBlank() }
            ?: throw LlmError.NotConfigured(ProviderId.Foundry)
        val resolvedModel = model
            ?: cfg.foundryDeployment.takeIf { it.isNotBlank() }
            ?: throw LlmError.NotConfigured(ProviderId.Foundry)

        val systemMsg = systemPrompt?.takeIf { it.isNotBlank() }
            ?.let { listOf(OpenAiMessage("system", it)) }
            .orEmpty()
        val openAiMsgs = systemMsg + messages.map {
            OpenAiMessage(it.role.name, it.content)
        }

        val url = buildUrl(endpoint, resolvedModel, cfg.foundryServerless)
        // Deployed mode pins the model in the URL; the body must NOT
        // also include `model` (Azure rejects with 400). Serverless
        // mode picks the model via the body field.
        val bodyJson = if (cfg.foundryServerless) {
            json.encodeToString(
                OpenAiRequest(
                    model = resolvedModel,
                    messages = openAiMsgs,
                    stream = true,
                ),
            )
        } else {
            json.encodeToString(
                FoundryDeployedRequest(
                    messages = openAiMsgs,
                    stream = true,
                ),
            )
        }

        val request = Request.Builder()
            .url(url)
            .header("api-key", apiKey)
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = try {
            http.newCall(request).executeAsync()
        } catch (e: IOException) {
            throw LlmError.Transport(ProviderId.Foundry, e)
        }

        response.use { resp ->
            when {
                resp.code == 401 || resp.code == 403 ->
                    throw LlmError.AuthFailed(
                        ProviderId.Foundry,
                        resp.message.ifBlank { "HTTP ${resp.code}" },
                    )
                !resp.isSuccessful -> {
                    val excerpt = resp.body?.string()?.take(256) ?: resp.message
                    throw LlmError.ProviderError(
                        ProviderId.Foundry, resp.code, excerpt,
                    )
                }
            }
            val src = resp.body?.source()
                ?: throw LlmError.Transport(
                    ProviderId.Foundry,
                    IOException("Empty response body"),
                )
            src.use {
                while (!it.exhausted()) {
                    val line = it.readUtf8Line() ?: break
                    val token = SseLineParser.openAiCompat(line, json) ?: continue
                    emit(token)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun probe(): ProbeResult {
        val apiKey = store.foundryApiKey()
            ?: return ProbeResult.Misconfigured("No Azure Foundry API key")
        val cfg = configFlow.first()
        val endpoint = cfg.foundryEndpoint.takeIf { it.isNotBlank() }
            ?: return ProbeResult.Misconfigured("No Azure Foundry endpoint")
        val deployment = cfg.foundryDeployment.takeIf { it.isNotBlank() }
            ?: return ProbeResult.Misconfigured("No Foundry model / deployment")

        // Foundry has no equivalent of OpenAI's `/v1/models` cheap
        // probe — `GET` against the chat-completions URL returns 405,
        // and 405 with the right `api-key` header is positive evidence
        // the endpoint exists and the key is accepted. Anything 401/
        // 403 → auth bad; network failure → not reachable.
        val request = Request.Builder()
            .url(buildUrl(endpoint, deployment, cfg.foundryServerless))
            .header("api-key", apiKey)
            .get()
            .build()
        return try {
            http.newCall(request).executeAsync().use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 ->
                        ProbeResult.AuthError("Invalid Azure Foundry API key")
                    // 200 (unlikely on chat-completions) or 405 both
                    // mean "endpoint exists, key accepted".
                    resp.isSuccessful || resp.code == 405 -> ProbeResult.Ok
                    resp.code == 404 ->
                        ProbeResult.NotReachable(
                            "Azure returned 404 — check endpoint URL and " +
                                "deployment / model id",
                        )
                    else -> ProbeResult.NotReachable(
                        "Azure Foundry returned ${resp.code}",
                    )
                }
            }
        } catch (e: IOException) {
            ProbeResult.NotReachable(e.message ?: "Connection failed")
        }
    }

    companion object {
        /**
         * Build the chat-completions URL for either Foundry deployment
         * mode. Pure function — exposed for unit testing without a
         * MockWebServer. The trailing slash on [endpoint] is tolerated
         * (and stripped) so users who paste either form get the same
         * URL out.
         *
         * @param endpoint base URL the user pasted, e.g.
         *   `https://my-resource.openai.azure.com` (deployed) or
         *   `https://my-project.services.ai.azure.com` (serverless).
         * @param model deployment name (deployed) or catalog model id
         *   (serverless).
         * @param serverless `true` selects the `/models/...` shape;
         *   `false` selects `/openai/deployments/{model}/...`.
         */
        fun buildUrl(endpoint: String, model: String, serverless: Boolean): String {
            val base = endpoint.trimEnd('/')
            return if (serverless) {
                "$base/models/chat/completions?api-version=$API_VERSION"
            } else {
                "$base/openai/deployments/$model/chat/completions?api-version=$API_VERSION"
            }
        }

        /**
         * Catalog model ids surfaced as Foundry serverless choices.
         * Mirrors a small subset of cloud-chat-assistant's
         * `AZURE_SERVERLESS` list — we don't need every model
         * Microsoft offers; users can override by typing any id into
         * the model field.
         */
        val SERVERLESS_MODELS: List<String> = listOf(
            "gpt-4o",
            "gpt-4o-mini",
            "Llama-3.3-70B-Instruct",
            "DeepSeek-R1",
            "Phi-4",
            "grok-3",
        )

        private const val API_VERSION = "2024-12-01-preview"
    }
}
