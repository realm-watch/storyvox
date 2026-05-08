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
 * Anthropic Messages API streaming client. Wire format documented
 * here:
 *   https://docs.anthropic.com/en/api/messages-streaming
 *
 * BYOK auth via `x-api-key` header. The version pin
 * `anthropic-version: 2023-06-01` has been stable since '23 — the
 * API has added events on top, but the named version is the wire
 * shape we test against. Bumping is a one-line change if a future
 * version offers something we want.
 */
@Singleton
open class ClaudeApiProvider @Inject constructor(
    @LlmHttp private val http: OkHttpClient,
    private val store: LlmCredentialsStore,
    private val configFlow: Flow<LlmConfig>,
    private val json: Json,
) : LlmProvider {

    override val id: ProviderId = ProviderId.Claude

    /** Anthropic Messages endpoint. Open so tests can override to
     *  point at MockWebServer. */
    protected open val endpointUrl: String = ENDPOINT

    override fun stream(
        messages: List<LlmMessage>,
        systemPrompt: String?,
        model: String?,
    ): Flow<String> = flow {
        val cfg = configFlow.first()
        val apiKey = store.claudeApiKey()
            ?: throw LlmError.NotConfigured(ProviderId.Claude)
        val resolvedModel = (model ?: cfg.claudeModel).resolveAnthropic()

        val body = AnthropicRequest(
            model = resolvedModel,
            maxTokens = MAX_TOKENS,
            messages = messages.map {
                AnthropicMessage(it.role.name, it.content)
            },
            system = systemPrompt,
            stream = true,
        )

        val request = Request.Builder()
            .url(endpointUrl)
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = try {
            http.newCall(request).executeAsync()
        } catch (e: IOException) {
            throw LlmError.Transport(ProviderId.Claude, e)
        }

        response.use { resp ->
            when {
                resp.code == 401 || resp.code == 403 ->
                    throw LlmError.AuthFailed(
                        ProviderId.Claude,
                        resp.message.ifBlank { "HTTP ${resp.code}" },
                    )
                !resp.isSuccessful -> {
                    val excerpt = resp.body?.string()?.take(256) ?: resp.message
                    throw LlmError.ProviderError(
                        ProviderId.Claude, resp.code, excerpt,
                    )
                }
            }
            val source = resp.body
                ?: throw LlmError.Transport(
                    ProviderId.Claude,
                    IOException("Empty response body"),
                )
            source.source().use { src ->
                while (!src.exhausted()) {
                    val line = src.readUtf8Line() ?: break
                    val token = SseLineParser.anthropic(line, json) ?: continue
                    emit(token)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun probe(): ProbeResult {
        val apiKey = store.claudeApiKey()
            ?: return ProbeResult.Misconfigured("No Claude API key")
        // 1-token POST — cheapest possible probe. Anthropic doesn't
        // expose a free reachability endpoint; this consumes ~1¢
        // worth of tokens, which is fine for a manually-triggered
        // Test connection button.
        val body = AnthropicRequest(
            model = LlmConfig().claudeModel.resolveAnthropic(),
            maxTokens = 1,
            messages = listOf(AnthropicMessage("user", "ping")),
            stream = false,
        )
        val request = Request.Builder()
            .url(endpointUrl)
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return try {
            http.newCall(request).executeAsync().use { resp ->
                when {
                    resp.isSuccessful -> ProbeResult.Ok
                    resp.code == 401 || resp.code == 403 ->
                        ProbeResult.AuthError("Invalid Claude API key")
                    else -> ProbeResult.NotReachable(
                        "Anthropic returned ${resp.code}",
                    )
                }
            }
        } catch (e: IOException) {
            ProbeResult.NotReachable(e.message ?: "Connection failed")
        }
    }

    /**
     * Map our canonical model name (e.g. "claude-haiku-4.5") to
     * Anthropic's wire format ("claude-haiku-4-5-20251001").
     * Direct port of `cloud-chat-assistant/llm_stream.py:MODEL_MAP`.
     * Falls through to the input string when no mapping exists, so
     * advanced users can paste a literal Anthropic model id into
     * the Settings dropdown if they want a model we haven't
     * canonicalized.
     */
    private fun String.resolveAnthropic(): String = when (this) {
        "claude-opus-4.6" -> "claude-opus-4-6"
        "claude-sonnet-4.6" -> "claude-sonnet-4-6"
        "claude-haiku-4.5" -> "claude-haiku-4-5-20251001"
        "claude-opus-4.5" -> "claude-opus-4-5-20251101"
        "claude-sonnet-4.5" -> "claude-sonnet-4-5-20250929"
        else -> this
    }

    private companion object {
        const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        const val API_VERSION = "2023-06-01"
        const val MAX_TOKENS = 1024
    }
}
