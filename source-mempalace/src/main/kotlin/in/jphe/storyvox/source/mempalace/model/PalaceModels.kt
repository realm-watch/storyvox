package `in`.jphe.storyvox.source.mempalace.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /health` response shape.
 *
 * The daemon returns 200 + `{status: "ok", ...}` when the palace
 * collection is open, or 503 + `{status: "degraded", ...}` when the
 * collection is unavailable. We treat any non-200 as `Unreachable`
 * for storyvox's purposes — even a 503 means the palace can't serve
 * reads right now, which is functionally indistinguishable from
 * "off the LAN" from the reader's perspective.
 */
@Serializable
data class PalaceHealth(
    val status: String,
    val daemon: String? = null,
    val version: String? = null,
)

/**
 * `GET /graph` response shape — the structural snapshot the daemon
 * built specifically for diagnostic / dashboard use. Storyvox uses
 * the wings + rooms portion to drive Browse → Palace.
 *
 * `tunnels`, `kg_entities`, `kg_triples`, and `kg_stats` are present
 * in the response but unused in v1; they're tracked here so the
 * deserializer doesn't reject them.
 */
@Serializable
data class PalaceGraph(
    /** wing name → drawer count across that whole wing. */
    val wings: Map<String, Int> = emptyMap(),
    /** Per-wing room breakdown. */
    val rooms: List<PalaceWingRooms> = emptyList(),
)

@Serializable
data class PalaceWingRooms(
    val wing: String,
    /** room name → drawer count in that room. */
    val rooms: Map<String, Int> = emptyMap(),
)

/**
 * `GET /list` response — unranked browse of drawers by metadata.
 *
 * The daemon wraps `mempalace_list_drawers` MCP tool. `count` is the
 * number of drawers actually returned in this page (≤ `limit`); we
 * derive `hasNext` from `count == limit` since the daemon doesn't
 * expose a total.
 */
@Serializable
data class PalaceList(
    val drawers: List<PalaceDrawerSummary> = emptyList(),
    val count: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
)

@Serializable
data class PalaceDrawerSummary(
    @SerialName("drawer_id") val drawerId: String,
    val wing: String,
    val room: String,
    @SerialName("content_preview") val contentPreview: String? = null,
)

/**
 * Full-content drawer fetch via `mempalace_get_drawer` MCP tool.
 * Storyvox calls `POST /mcp` with the JSON-RPC envelope and unwraps
 * the result. The daemon returns the full content + metadata.
 */
@Serializable
data class PalaceDrawer(
    @SerialName("drawer_id") val drawerId: String,
    val content: String,
    val wing: String,
    val room: String,
    val metadata: PalaceDrawerMetadata = PalaceDrawerMetadata(),
)

@Serializable
data class PalaceDrawerMetadata(
    @SerialName("filed_at") val filedAt: String? = null,
    @SerialName("source_file") val sourceFile: String? = null,
    @SerialName("chunk_index") val chunkIndex: Int = 0,
    @SerialName("added_by") val addedBy: String? = null,
)
