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
 * OpenAI Chat Completions streaming client. Wire format documented
 * here:
 *   https://platform.openai.com/docs/api-reference/chat/create
 *
 * Same wire format is used by Ollama (via the OpenAI-compat path),
 * DigitalOcean, Puter, and Foundry. This class is the canonical
 * "OpenAI-compat over BYOK Bearer auth" implementation; the
 * Ollama provider is a near-twin with a different base URL and no
 * auth header.
 */
@Singleton
open class OpenAiApiProvider @Inject constructor(
    @LlmHttp private val http: OkHttpClient,
    private val store: LlmCredentialsStore,
    private val configFlow: Flow<LlmConfig>,
    private val json: Json,
) : LlmProvider {

    override val id: ProviderId = ProviderId.OpenAi

    /** Chat-completions endpoint. Open for tests. */
    protected open val endpointUrl: String = ENDPOINT
    /** /v1/models endpoint for [probe]. Open for tests. */
    protected open val probeUrl: String = "https://api.openai.com/v1/models"

    override fun stream(
        messages: List<LlmMessage>,
        systemPrompt: String?,
        model: String?,
    ): Flow<String> = flow {
        val cfg = configFlow.first()
        val apiKey = store.openAiApiKey()
            ?: throw LlmError.NotConfigured(ProviderId.OpenAi)
        val resolvedModel = model ?: cfg.openAiModel

        // OpenAI wants system as a regular message at the head of the
        // array (unlike Anthropic which has a top-level field).
        val systemMsg = systemPrompt?.takeIf { it.isNotBlank() }
            ?.let { listOf(OpenAiMessage("system", it)) }
            .orEmpty()
        val body = OpenAiRequest(
            model = resolvedModel,
            messages = systemMsg + messages.map {
                OpenAiMessage(it.role.name, it.content)
            },
            stream = true,
        )

        val request = Request.Builder()
            .url(endpointUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = try {
            http.newCall(request).executeAsync()
        } catch (e: IOException) {
            throw LlmError.Transport(ProviderId.OpenAi, e)
        }

        response.use { resp ->
            when {
                resp.code == 401 || resp.code == 403 ->
                    throw LlmError.AuthFailed(
                        ProviderId.OpenAi,
                        resp.message.ifBlank { "HTTP ${resp.code}" },
                    )
                !resp.isSuccessful -> {
                    val excerpt = resp.body?.string()?.take(256) ?: resp.message
                    throw LlmError.ProviderError(
                        ProviderId.OpenAi, resp.code, excerpt,
                    )
                }
            }
            val src = resp.body?.source()
                ?: throw LlmError.Transport(
                    ProviderId.OpenAi,
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
        val apiKey = store.openAiApiKey()
            ?: return ProbeResult.Misconfigured("No OpenAI API key")
        // GET /v1/models — instant, free, doesn't burn model time.
        val request = Request.Builder()
            .url(probeUrl)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        return try {
            http.newCall(request).executeAsync().use { resp ->
                when {
                    resp.isSuccessful -> ProbeResult.Ok
                    resp.code == 401 || resp.code == 403 ->
                        ProbeResult.AuthError("Invalid OpenAI API key")
                    else -> ProbeResult.NotReachable(
                        "OpenAI returned ${resp.code}",
                    )
                }
            }
        } catch (e: IOException) {
            ProbeResult.NotReachable(e.message ?: "Connection failed")
        }
    }

    private companion object {
        const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
    }
}
