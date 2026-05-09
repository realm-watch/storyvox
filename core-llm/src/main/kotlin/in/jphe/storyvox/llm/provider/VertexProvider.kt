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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Google Vertex AI / Gemini streaming client. Wire format documented
 * here:
 *   https://ai.google.dev/api/rest/v1beta/models/streamGenerateContent
 *
 * BYOK auth via API key in the URL query string (`?key=…`). Direct
 * port of `cloud-chat-assistant/llm_stream.py`'s `google` branch —
 * the `generativelanguage.googleapis.com` endpoint accepts the same
 * key format that an end-user generates from
 * https://aistudio.google.com/app/apikey, so we don't pull in the
 * Google Cloud auth library or implement OAuth token refresh. If a
 * future spec wants service-account JSON / Vertex Enterprise auth,
 * that's a separate provider class.
 *
 * The endpoint emits true SSE when called with `?alt=sse`, so we
 * reuse [SseLineParser]'s `vertex` branch — the JSON body inside the
 * `data:` lines is Gemini-shaped, not OpenAI-compat.
 */
@Singleton
open class VertexProvider @Inject constructor(
    @LlmHttp private val http: OkHttpClient,
    private val store: LlmCredentialsStore,
    private val configFlow: Flow<LlmConfig>,
    private val json: Json,
) : LlmProvider {

    override val id: ProviderId = ProviderId.Vertex

    /** Base URL up to but not including `models/{model}:…`. Open so
     *  tests can point at MockWebServer. */
    protected open val baseUrl: String = BASE_URL

    override fun stream(
        messages: List<LlmMessage>,
        systemPrompt: String?,
        model: String?,
    ): Flow<String> = flow {
        val cfg = configFlow.first()
        val apiKey = store.vertexApiKey()
            ?: throw LlmError.NotConfigured(ProviderId.Vertex)
        val resolvedModel = model ?: cfg.vertexModel

        // Gemini's role names: user is "user", assistant is "model".
        // System prompt rides outside the contents array on its own
        // top-level field — same shape as Anthropic in spirit.
        val contents = messages.map {
            VertexContent(
                role = if (it.role == LlmMessage.Role.assistant) "model" else "user",
                parts = listOf(VertexPart(it.content)),
            )
        }
        val systemInstruction = systemPrompt?.takeIf { it.isNotBlank() }
            ?.let { VertexContent(parts = listOf(VertexPart(it))) }

        val body = VertexRequest(
            contents = contents,
            systemInstruction = systemInstruction,
            generationConfig = VertexGenerationConfig(maxOutputTokens = MAX_OUTPUT_TOKENS),
        )

        val request = Request.Builder()
            .url(streamUrl(resolvedModel, apiKey))
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = try {
            http.newCall(request).executeAsync()
        } catch (e: IOException) {
            throw LlmError.Transport(ProviderId.Vertex, e)
        }

        response.use { resp ->
            when {
                resp.code == 401 || resp.code == 403 ->
                    throw LlmError.AuthFailed(
                        ProviderId.Vertex,
                        resp.message.ifBlank { "HTTP ${resp.code}" },
                    )
                !resp.isSuccessful -> {
                    val excerpt = resp.body?.string()?.take(256) ?: resp.message
                    throw LlmError.ProviderError(
                        ProviderId.Vertex, resp.code, excerpt,
                    )
                }
            }
            val src = resp.body?.source()
                ?: throw LlmError.Transport(
                    ProviderId.Vertex,
                    IOException("Empty response body"),
                )
            src.use {
                while (!it.exhausted()) {
                    val line = it.readUtf8Line() ?: break
                    val token = SseLineParser.vertex(line, json) ?: continue
                    emit(token)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun probe(): ProbeResult {
        val apiKey = store.vertexApiKey()
            ?: return ProbeResult.Misconfigured("No Vertex API key")
        // GET /v1beta/models — instant, free, doesn't burn token
        // budget. Same trick OpenAI's probe uses.
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("v1beta/models")
            .addQueryParameter("key", apiKey)
            .build()
        val request = Request.Builder().url(url).get().build()
        return try {
            http.newCall(request).executeAsync().use { resp ->
                when {
                    resp.isSuccessful -> ProbeResult.Ok
                    resp.code == 401 || resp.code == 403 ->
                        ProbeResult.AuthError("Invalid Vertex API key")
                    else -> ProbeResult.NotReachable(
                        "Vertex returned ${resp.code}",
                    )
                }
            }
        } catch (e: IOException) {
            ProbeResult.NotReachable(e.message ?: "Connection failed")
        }
    }

    private fun streamUrl(model: String, apiKey: String): okhttp3.HttpUrl =
        baseUrl.toHttpUrl().newBuilder()
            // Path is `v1beta/models/{model}:streamGenerateContent` —
            // the colon-suffix is part of the path segment per
            // Google's protobuf-derived URL convention. addPathSegment
            // url-encodes the colon, which Vertex accepts.
            .addPathSegments("v1beta/models/$model:streamGenerateContent")
            .addQueryParameter("alt", "sse")
            .addQueryParameter("key", apiKey)
            .build()

    private companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/"
        /** Generous cap. Vertex bills per output token; the user can
         *  cancel mid-stream via flow cancellation. */
        const val MAX_OUTPUT_TOKENS = 1024
    }
}
