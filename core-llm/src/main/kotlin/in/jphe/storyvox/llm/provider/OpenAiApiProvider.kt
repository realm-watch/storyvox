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
import `in`.jphe.storyvox.llm.tools.ChatStreamEvent
import `in`.jphe.storyvox.llm.tools.ToolCallRequest
import `in`.jphe.storyvox.llm.tools.ToolRegistry
import `in`.jphe.storyvox.llm.tools.ToolResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

    /** Issue #216 — OpenAI's `gpt-4o` family supports function
     *  calling out of the box. */
    override val supportsTools: Boolean = true

    /** Issue #215 — OpenAI's `gpt-4o` family (and every subsequent
     *  Chat-Completions model JP has the Settings UI offer) accepts
     *  `image_url` content blocks. The chat layer attaches an image
     *  when the composer has one queued; we splice it into the user
     *  message's content array as `{type:"image_url", image_url:{url:"data:…"}}`. */
    override val supportsImages: Boolean = true

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

        // Issue #215 — when any message carries multi-modal parts we
        // serialize each message as a JSON object whose `content` is
        // a typed-block array. Text-only chats keep the cheaper
        // string-content [OpenAiRequest] shape.
        val payload: String = if (messages.any { it.parts != null }) {
            val list = ArrayList<JsonObject>()
            systemPrompt?.takeIf { it.isNotBlank() }?.let { sp ->
                list.add(buildJsonObject {
                    put("role", "system")
                    put("content", sp)
                })
            }
            messages.forEach { msg ->
                val blocks = ContentBlocks.openAi(msg)
                list.add(buildJsonObject {
                    put("role", msg.role.name)
                    if (blocks != null) {
                        put("content", kotlinx.serialization.json.JsonArray(blocks))
                    } else {
                        put("content", msg.content)
                    }
                })
            }
            val body = OpenAiImageRequest(
                model = resolvedModel,
                messages = list,
                stream = true,
            )
            json.encodeToString(body)
        } else {
            // OpenAI wants system as a regular message at the head of
            // the array (unlike Anthropic which has a top-level field).
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
            json.encodeToString(body)
        }

        val request = Request.Builder()
            .url(endpointUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
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

    /**
     * Issue #216 — tool-aware chat. Same pattern as
     * [ClaudeApiProvider.chatWithTools]: non-streaming request/
     * response loop when tools are involved, fall through to
     * streaming text when they aren't. OpenAI's `tool_calls` arrive
     * in `choices[0].message.tool_calls`; we issue a follow-up turn
     * with the assistant message preserved verbatim plus one
     * `role: "tool"` message per call.
     */
    override fun chatWithTools(
        messages: List<LlmMessage>,
        systemPrompt: String?,
        model: String?,
        tools: ToolRegistry,
    ): Flow<ChatStreamEvent> {
        if (tools.catalog.isEmpty()) {
            return stream(messages, systemPrompt, model)
                .map { ChatStreamEvent.TextDelta(it) }
        }
        return flow {
            val cfg = configFlow.first()
            val apiKey = store.openAiApiKey()
                ?: throw LlmError.NotConfigured(ProviderId.OpenAi)
            val resolvedModel = model ?: cfg.openAiModel

            val toolDecls = tools.catalog.map { spec ->
                OpenAiToolDecl(
                    type = "function",
                    function = OpenAiToolFunction(
                        name = spec.name,
                        description = spec.description,
                        parameters = spec.toOpenAiParameters(),
                    ),
                )
            }

            val conversation = ArrayList<JsonObject>()
            systemPrompt?.takeIf { it.isNotBlank() }?.let { sp ->
                conversation.add(buildJsonObject {
                    put("role", "system")
                    put("content", sp)
                })
            }
            messages.forEach { msg ->
                // Issue #215 — image-bearing message → typed-block array;
                // text-only → string content (cheaper to serialize and
                // backward-compatible with every OpenAI-compat backend).
                val blocks = ContentBlocks.openAi(msg)
                conversation.add(buildJsonObject {
                    put("role", msg.role.name)
                    if (blocks != null) {
                        put("content", kotlinx.serialization.json.JsonArray(blocks))
                    } else {
                        put("content", msg.content)
                    }
                })
            }

            var round = 0
            while (round < MAX_TOOL_ROUNDS) {
                round++
                val body = OpenAiToolRequest(
                    model = resolvedModel,
                    messages = conversation,
                    tools = toolDecls,
                    stream = false,
                )
                val response = postToolRequest(apiKey, body)
                val choice = response["choices"]?.jsonArray
                    ?.firstOrNull()?.jsonObject ?: break
                val finishReason = choice["finish_reason"]
                    ?.jsonPrimitive?.contentOrNull
                val message = choice["message"]?.jsonObject ?: break
                val toolCalls = parseToolCalls(message)
                val text = message["content"]?.jsonPrimitive?.contentOrNull.orEmpty()

                if (toolCalls.isEmpty() || finishReason != "tool_calls") {
                    if (text.isNotEmpty()) emit(ChatStreamEvent.TextDelta(text))
                    return@flow
                }
                // Replay the assistant turn (with tool_calls preserved
                // verbatim) so the follow-up message's `tool_call_id`s
                // resolve.
                conversation.add(message)
                for (call in toolCalls) {
                    emit(ChatStreamEvent.ToolCallStarted(call))
                    val handler = tools.handler(call.name)
                    val result = if (handler == null) {
                        ToolResult.Error("Unknown tool: ${call.name}")
                    } else {
                        try {
                            handler.execute(call.arguments)
                        } catch (t: Throwable) {
                            ToolResult.Error(
                                "Tool ${call.name} failed: ${t.message ?: t.javaClass.simpleName}",
                            )
                        }
                    }
                    emit(ChatStreamEvent.ToolCallCompleted(call.id, call.name, result))
                    conversation.add(buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", call.id)
                        put("content", result.message)
                    })
                }
            }
            emit(
                ChatStreamEvent.TextDelta(
                    "(Stopped after $MAX_TOOL_ROUNDS tool rounds.)",
                ),
            )
        }.flowOn(Dispatchers.IO)
    }

    private suspend fun postToolRequest(
        apiKey: String,
        body: OpenAiToolRequest,
    ): JsonObject {
        val request = Request.Builder()
            .url(endpointUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("content-type", "application/json")
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val resp = try {
            http.newCall(request).executeAsync()
        } catch (e: IOException) {
            throw LlmError.Transport(ProviderId.OpenAi, e)
        }
        resp.use { r ->
            when {
                r.code == 401 || r.code == 403 ->
                    throw LlmError.AuthFailed(
                        ProviderId.OpenAi,
                        r.message.ifBlank { "HTTP ${r.code}" },
                    )
                !r.isSuccessful -> {
                    val excerpt = r.body?.string()?.take(256) ?: r.message
                    throw LlmError.ProviderError(
                        ProviderId.OpenAi, r.code, excerpt,
                    )
                }
            }
            val text = r.body?.string()
                ?: throw LlmError.Transport(
                    ProviderId.OpenAi,
                    IOException("Empty response body"),
                )
            return json.parseToJsonElement(text).jsonObject
        }
    }

    private fun parseToolCalls(message: JsonObject): List<ToolCallRequest> {
        val raw = message["tool_calls"]?.jsonArray ?: return emptyList()
        return raw.mapNotNull { el ->
            val obj = el.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val fn = obj["function"]?.jsonObject ?: return@mapNotNull null
            val name = fn["name"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val argsRaw = fn["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val parsed = runCatching {
                if (argsRaw.isBlank()) JsonObject(emptyMap())
                else json.parseToJsonElement(argsRaw).jsonObject
            }.getOrDefault(JsonObject(emptyMap()))
            ToolCallRequest(id = id, name = name, arguments = parsed)
        }
    }

    private companion object {
        const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
        /** See [ClaudeApiProvider.MAX_TOOL_ROUNDS]. */
        const val MAX_TOOL_ROUNDS = 5
    }
}
