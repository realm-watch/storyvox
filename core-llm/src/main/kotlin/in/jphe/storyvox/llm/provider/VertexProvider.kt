package `in`.jphe.storyvox.llm.provider

import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmCredentialsStore
import `in`.jphe.storyvox.llm.LlmError
import `in`.jphe.storyvox.llm.LlmMessage
import `in`.jphe.storyvox.llm.LlmProvider
import `in`.jphe.storyvox.llm.ProbeResult
import `in`.jphe.storyvox.llm.ProviderId
import `in`.jphe.storyvox.llm.auth.GoogleOAuthTokenSource
import `in`.jphe.storyvox.llm.auth.GoogleServiceAccount
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
 * Two auth modes, both BYOK:
 *
 *  1. **API key** (`?key=…` query string) — the original mode. The
 *     key is what `aistudio.google.com/app/apikey` issues.
 *  2. **Service-account JSON** (#219) — the user uploads a GCP IAM
 *     SA key file via Settings. We sign a JWT with the embedded
 *     RSA private key, exchange it at `oauth2.googleapis.com/token`
 *     for an access token (RFC 7523), and inject it as
 *     `Authorization: Bearer …`. Token caching lives in
 *     [GoogleOAuthTokenSource]. The `?key=` query string is omitted
 *     in this mode — Google's API accepts either header-or-query
 *     auth, never both.
 *
 * The two modes are mutually exclusive at the credentials-store
 * level (setting one clears the other in
 * [SettingsRepositoryUi.setVertexApiKey] / `setVertexServiceAccountJson`).
 * If a misconfiguration leaves both populated we prefer the SA path
 * — it's the stronger credential and the one the user actively
 * uploaded most recently.
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
    private val tokenSource: GoogleOAuthTokenSource,
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
        val auth = resolveAuth()
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

        val builder = Request.Builder()
            .url(streamUrl(resolvedModel, auth))
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
        if (auth is VertexAuth.ServiceAccount) {
            // OAuth-bearer path (#219). Header auth — the key= query
            // string is intentionally absent on this branch.
            builder.header("Authorization", "Bearer ${auth.accessToken}")
        }
        val request = builder.build()

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
        val auth = try {
            resolveAuth() ?: return ProbeResult.Misconfigured(
                "No Vertex credentials — paste an API key or upload a " +
                    "service-account JSON in Settings.",
            )
        } catch (e: LlmError.AuthFailed) {
            return ProbeResult.AuthError(e.message ?: "Auth failed")
        } catch (e: IllegalArgumentException) {
            // SA JSON failed to parse — surfaced as a misconfig, not a
            // network failure. The uploaded blob is malformed.
            return ProbeResult.Misconfigured(
                "Service-account JSON is malformed: ${e.message}",
            )
        }
        // GET /v1beta/models — instant, free, doesn't burn token
        // budget. Same trick OpenAI's probe uses.
        val urlBuilder = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("v1beta/models")
        if (auth is VertexAuth.ApiKey) {
            urlBuilder.addQueryParameter("key", auth.key)
        }
        val requestBuilder = Request.Builder().url(urlBuilder.build()).get()
        if (auth is VertexAuth.ServiceAccount) {
            requestBuilder.header("Authorization", "Bearer ${auth.accessToken}")
        }
        return try {
            http.newCall(requestBuilder.build()).executeAsync().use { resp ->
                when {
                    resp.isSuccessful -> ProbeResult.Ok
                    resp.code == 401 || resp.code == 403 ->
                        ProbeResult.AuthError(
                            if (auth is VertexAuth.ServiceAccount)
                                "Service account rejected — check IAM roles " +
                                    "(needs Vertex AI User or equivalent)."
                            else "Invalid Vertex API key",
                        )
                    else -> ProbeResult.NotReachable(
                        "Vertex returned ${resp.code}",
                    )
                }
            }
        } catch (e: IOException) {
            ProbeResult.NotReachable(e.message ?: "Connection failed")
        }
    }

    /** Resolve which auth mode is configured. SA wins if both happen
     *  to be set — see class kdoc. */
    private suspend fun resolveAuth(): VertexAuth? {
        val saJson = store.vertexServiceAccountJson()
        if (saJson != null) {
            val sa = GoogleServiceAccount.parse(saJson)
            return VertexAuth.ServiceAccount(tokenSource.accessToken(sa))
        }
        val apiKey = store.vertexApiKey() ?: return null
        return VertexAuth.ApiKey(apiKey)
    }

    private fun streamUrl(model: String, auth: VertexAuth): okhttp3.HttpUrl {
        val builder = baseUrl.toHttpUrl().newBuilder()
            // Path is `v1beta/models/{model}:streamGenerateContent` —
            // the colon-suffix is part of the path segment per
            // Google's protobuf-derived URL convention. addPathSegment
            // url-encodes the colon, which Vertex accepts.
            .addPathSegments("v1beta/models/$model:streamGenerateContent")
            .addQueryParameter("alt", "sse")
        if (auth is VertexAuth.ApiKey) {
            builder.addQueryParameter("key", auth.key)
        }
        return builder.build()
    }

    /** Internal sum type — keeps the API-key vs OAuth-bearer choice
     *  in one place (per-call resolution) rather than threading two
     *  nullable strings through stream/probe/URL helpers. */
    private sealed class VertexAuth {
        data class ApiKey(val key: String) : VertexAuth()
        data class ServiceAccount(val accessToken: String) : VertexAuth()
    }

    private companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/"
        /** Generous cap. Vertex bills per output token; the user can
         *  cancel mid-stream via flow cancellation. */
        const val MAX_OUTPUT_TOKENS = 1024
    }
}
