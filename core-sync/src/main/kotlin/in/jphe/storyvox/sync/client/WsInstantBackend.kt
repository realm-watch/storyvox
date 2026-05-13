package `in`.jphe.storyvox.sync.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID

/**
 * Production [InstantBackend] backed by InstantDB's WebSocket transact
 * protocol.
 *
 * The protocol (from `instantdb/instant @main client/packages/core/src/Reactor.js`):
 *  1. Connect to `wss://api.instantdb.com/runtime/session?app_id=<id>`.
 *  2. Send `{op: "init", "app-id": <id>, "refresh-token": <token>, versions: {}}`.
 *  3. Wait for `{op: "init-ok", ...}` — anything else (`init-error`) is fatal.
 *  4. Send `{op: "transact", "tx-steps": [...]}`. Server replies
 *     `{op: "transact-ok", "tx-id": ...}` on success or
 *     `{op: "transact-error", message: ...}` on failure.
 *  5. Close.
 *
 * Read path: send `{op: "add-query", q: {...}}`; the server replies with
 * `{op: "add-query-ok", result: {...}}` and the data we need is in the
 * result blob.
 *
 * Why one-shot: the full reactor keeps a long-lived socket so it can
 * push real-time updates. v1's request-driven model only needs to push
 * or pull a single row at a time. A one-shot connection is simpler to
 * test, fits the v1 conflict model (we don't need real-time updates
 * across devices yet), and is well-suited to retry-with-backoff on
 * transient failures. v2 will hoist this into a persistent connection
 * managed by the [SyncCoordinator].
 *
 * Schema mapping: storyvox uses entity names `sets`, `blobs`, and
 * `positions`. Each row is `{payload: <serialized string>, updatedAt: <long>}`.
 * InstantDB's schema is dynamic — the first transact creates the entity
 * shape implicitly.
 */
class WsInstantBackend(
    private val appId: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = "wss://api.instantdb.com/runtime/session",
    private val operationTimeoutMs: Long = 15_000L,
) : InstantBackend {
    override val isConfigured: Boolean = appId.isNotBlank() && appId != PLACEHOLDER_APP_ID

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    override suspend fun fetch(user: SignedInUser, entity: String, id: String): Result<RowSnapshot?> = runCatching {
        if (!isConfigured) error("Sync backend not configured")
        // Build an InstaQL query: `{ <entity>: { $: { where: { id: <id> } } } }`.
        // This is the documented shape from the InstantDB query reference.
        val q = buildJsonObject {
            put(entity, buildJsonObject {
                put("$", buildJsonObject {
                    put("where", buildJsonObject {
                        put("id", JsonPrimitive(id))
                    })
                })
            })
        }
        val msg = buildJsonObject {
            put("op", JsonPrimitive("add-query"))
            put("q", q)
        }
        val resp = roundTrip(user.refreshToken, msg, awaitOp = "add-query-ok")
        val rows = resp["result"]?.jsonObject?.get(entity) as? kotlinx.serialization.json.JsonArray
        val row = rows?.firstOrNull()?.jsonObject ?: return@runCatching null
        val payload = row["payload"]?.jsonPrimitive?.contentOrNull
        val updatedAt = row["updatedAt"]?.jsonPrimitive?.long
        if (payload != null && updatedAt != null) RowSnapshot(payload, updatedAt) else null
    }

    override suspend fun upsert(
        user: SignedInUser,
        entity: String,
        id: String,
        payload: String,
        updatedAt: Long,
    ): Result<Unit> = runCatching {
        if (!isConfigured) error("Sync backend not configured")
        // One tx-step: ["update", <entity>, <id>, { payload, updatedAt }]
        val step = buildJsonArray {
            add(JsonPrimitive("update"))
            add(JsonPrimitive(entity))
            add(JsonPrimitive(id))
            add(buildJsonObject {
                put("payload", JsonPrimitive(payload))
                put("updatedAt", JsonPrimitive(updatedAt))
            })
        }
        val msg = buildJsonObject {
            put("op", JsonPrimitive("transact"))
            put("tx-steps", buildJsonArray { add(step) })
        }
        roundTrip(user.refreshToken, msg, awaitOp = "transact-ok")
        Unit
    }

    /**
     * Connect, init, send [message], wait for the matching `awaitOp`,
     * close. Throws on any error (server-side init/transact failure or
     * a timeout). Single-shot — no connection pooling in v1.
     */
    private suspend fun roundTrip(
        refreshToken: String,
        message: JsonObject,
        awaitOp: String,
    ): JsonObject {
        val req = Request.Builder().url("$baseUrl?app_id=$appId").build()
        val initOk = CompletableDeferred<Unit>()
        val response = CompletableDeferred<JsonObject>()

        val listener = object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                val parsed = runCatching { json.parseToJsonElement(text).jsonObject }
                    .getOrNull() ?: return
                when (val op = parsed["op"]?.jsonPrimitive?.contentOrNull) {
                    "init-ok" -> initOk.complete(Unit)
                    "init-error" -> initOk.completeExceptionally(
                        RuntimeException(parsed["message"]?.jsonPrimitive?.contentOrNull ?: "init failed"),
                    )
                    awaitOp -> response.complete(parsed)
                    null -> Unit
                    else -> {
                        // Server-side error for our op.
                        if (op.endsWith("-error")) {
                            response.completeExceptionally(
                                RuntimeException(parsed["message"]?.jsonPrimitive?.contentOrNull ?: op),
                            )
                        }
                    }
                }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                if (!initOk.isCompleted) initOk.completeExceptionally(t)
                if (!response.isCompleted) response.completeExceptionally(t)
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (!initOk.isCompleted) initOk.completeExceptionally(RuntimeException("closed: $reason"))
                if (!response.isCompleted) response.completeExceptionally(RuntimeException("closed: $reason"))
            }
        }
        val ws = client.newWebSocket(req, listener)
        try {
            // Step 1: init
            ws.send(json.encodeToString(JsonObject.serializer(), initMessage(refreshToken)))
            withTimeout(operationTimeoutMs) { initOk.await() }
            // Step 2: send the actual op
            ws.send(json.encodeToString(JsonObject.serializer(), message))
            return withTimeout(operationTimeoutMs) { response.await() }
        } catch (e: TimeoutCancellationException) {
            throw RuntimeException("instantdb timeout after ${operationTimeoutMs}ms", e)
        } finally {
            ws.close(NORMAL_CLOSURE, "done")
        }
    }

    private fun initMessage(refreshToken: String): JsonObject = buildJsonObject {
        put("op", JsonPrimitive("init"))
        put("app-id", JsonPrimitive(appId))
        put("refresh-token", JsonPrimitive(refreshToken))
        put("versions", JsonObject(emptyMap()))
        put("client-event-id", JsonPrimitive(UUID.randomUUID().toString()))
    }

    companion object {
        const val PLACEHOLDER_APP_ID: String = "PLACEHOLDER"
        private const val NORMAL_CLOSURE = 1000
    }
}
