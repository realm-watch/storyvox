package `in`.jphe.storyvox.sync.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Wire-format helpers for InstantDB transactions.
 *
 * The Instant runtime accepts transactions as a list of tuple-shaped steps:
 *   `["update", "fictions", "id-abc", { "title": "X" }]`
 * mirrored from `client/packages/core/src/Reactor.js` op:transact handling
 * and the public `/admin/transact` HTTP shape documented at
 * https://www.instantdb.com/docs/http-api.
 *
 * Why hand-rolled instead of kotlinx-serialization polymorphism: tuples of
 * mixed type don't fit the polymorphism model cleanly, and our needs are
 * small (4 step variants, all 3-or-4-tuples). A pair of `toJson`/`fromJson`
 * functions is clearer than a custom polymorphic serializer here.
 */
internal object InstantTransact {

    /** Convert a single [TxStep] into the wire-shape JSON array. */
    fun toJson(step: TxStep): JsonArray = when (step) {
        is TxStep.Update -> buildJsonArray {
            add(JsonPrimitive("update"))
            add(JsonPrimitive(step.entity))
            add(JsonPrimitive(step.id))
            add(step.fields)
        }
        is TxStep.Delete -> buildJsonArray {
            add(JsonPrimitive("delete"))
            add(JsonPrimitive(step.entity))
            add(JsonPrimitive(step.id))
        }
        is TxStep.Link -> buildJsonArray {
            add(JsonPrimitive("link"))
            add(JsonPrimitive(step.entity))
            add(JsonPrimitive(step.id))
            add(step.links)
        }
        is TxStep.Unlink -> buildJsonArray {
            add(JsonPrimitive("unlink"))
            add(JsonPrimitive(step.entity))
            add(JsonPrimitive(step.id))
            add(step.links)
        }
    }

    /**
     * Build the WebSocket envelope for a transact op:
     *   `{ "op": "transact", "tx-steps": [ ... ] }`
     *
     * Server adds its own message id / response routing on top. We don't
     * include `tx-id` here — Reactor.js generates one client-side, but for
     * a fire-and-forget v1 client we leave it to the server.
     */
    fun envelope(steps: List<TxStep>): JsonObject = buildJsonObject {
        put("op", JsonPrimitive("transact"))
        put("tx-steps", JsonArray(steps.map { toJson(it) }))
    }

    /** Convert a map into a JsonObject; safe for any of our scalar field types. */
    fun fieldsOf(values: Map<String, JsonElement>): JsonObject = JsonObject(values)
}
