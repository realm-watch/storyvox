package `in`.jphe.storyvox.llm.provider

import `in`.jphe.storyvox.llm.LlmConfig
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
 * Ollama local-LAN client. Uses the OpenAI-compat endpoint Ollama
 * exposes since 0.1.34 (`/v1/chat/completions`) so we share the
 * SSE parser with [OpenAiApiProvider].
 *
 * No auth — Ollama on the LAN is unauthenticated by default. The
 * URL is the only configuration; users paste their LAN host
 * (e.g. `http://10.0.6.50:11434`) into Settings.
 *
 * The default URL ([LlmConfig.ollamaBaseUrl] = `http://10.0.0.1:11434`)
 * is intentionally a sentinel — clearly wrong on most setups, so
 * users see "Test connection" fail immediately rather than silently
 * sending to a bad endpoint. Localhost would be a worse default
 * because storyvox runs on phones/tablets where localhost is the
 * device, not the Ollama host.
 */
@Singleton
open class OllamaProvider @Inject constructor(
    @LlmHttp private val http: OkHttpClient,
    private val configFlow: Flow<LlmConfig>,
    private val json: Json,
) : LlmProvider {

    override val id: ProviderId = ProviderId.Ollama

    override fun stream(
        messages: List<LlmMessage>,
        systemPrompt: String?,
        model: String?,
    ): Flow<String> = flow {
        val cfg = configFlow.first()
        val baseUrl = cfg.ollamaBaseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            throw LlmError.NotConfigured(ProviderId.Ollama)
        }
        val resolvedModel = model ?: cfg.ollamaModel

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
            .url("$baseUrl/v1/chat/completions")
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = try {
            http.newCall(request).executeAsync()
        } catch (e: IOException) {
            throw LlmError.Transport(ProviderId.Ollama, e)
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                val excerpt = resp.body?.string()?.take(256) ?: resp.message
                throw LlmError.ProviderError(
                    ProviderId.Ollama, resp.code, excerpt,
                )
            }
            val src = resp.body?.source()
                ?: throw LlmError.Transport(
                    ProviderId.Ollama,
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
        val cfg = configFlow.first()
        val baseUrl = cfg.ollamaBaseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return ProbeResult.Misconfigured("No Ollama URL")
        }
        // Hit /api/tags — instant, doesn't consume model time, fails
        // fast if URL is wrong or Ollama isn't running.
        val request = Request.Builder()
            .url("$baseUrl/api/tags")
            .get()
            .build()
        return try {
            http.newCall(request).executeAsync().use { resp ->
                when {
                    resp.isSuccessful -> ProbeResult.Ok
                    resp.code in 400..499 ->
                        ProbeResult.Misconfigured("Ollama returned ${resp.code}")
                    else -> ProbeResult.NotReachable(
                        "Ollama returned ${resp.code}",
                    )
                }
            }
        } catch (e: IOException) {
            ProbeResult.NotReachable(e.message ?: "Connection failed")
        }
    }
}
