package `in`.jphe.storyvox.source.outline.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.map
import `in`.jphe.storyvox.source.outline.config.OutlineConfig
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton
import java.io.IOException

/**
 * Issue #245 — minimal Outline API client. Three endpoints cover the
 * read-only surface storyvox needs:
 *
 *  - `POST /api/collections.list` — list user's collections.
 *  - `POST /api/documents.list?collectionId=…` — documents in a collection.
 *  - `POST /api/documents.info?id=…` — full document body (markdown).
 *
 * All calls use `Authorization: Bearer <token>`. Body is JSON.
 * Parses with kotlinx.serialization (already on this module's
 * classpath). Surfaces failures as [FictionResult.Failure] without
 * leaking OkHttp / Outline-specific exceptions.
 */
@Singleton
internal class OutlineApi @Inject constructor(
    private val client: OkHttpClient,
    private val config: OutlineConfig,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun collections(): FictionResult<List<OutlineCollection>> =
        post<CollectionsResponse>("/api/collections.list", body = """{"limit":100}""")
            .map { it.data }

    suspend fun documents(
        collectionId: String,
        offset: Int = 0,
        limit: Int = 100,
    ): FictionResult<List<OutlineDocument>> =
        post<DocumentsResponse>(
            "/api/documents.list",
            body = json.encodeToString(
                DocumentsRequest.serializer(),
                DocumentsRequest(collectionId = collectionId, offset = offset, limit = limit),
            ),
        ).map { it.data }

    suspend fun documentInfo(id: String): FictionResult<OutlineDocumentDetail> =
        post<DocumentInfoResponse>(
            "/api/documents.info",
            body = """{"id":"$id"}""",
        ).map { it.data }

    private suspend inline fun <reified T> post(
        path: String,
        body: String,
    ): FictionResult<T> {
        val state = config.state.first()
        if (!state.isConfigured) return FictionResult.AuthRequired("Outline host or API key not set")
        val url = state.baseUrl + path
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${state.apiKey}")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 ->
                        FictionResult.AuthRequired("Outline rejected the API token (HTTP ${resp.code})")
                    !resp.isSuccessful ->
                        FictionResult.NetworkError(
                            "HTTP ${resp.code} from $url",
                            IOException("HTTP ${resp.code}"),
                        )
                    else -> {
                        val text = resp.body?.string()
                            ?: return FictionResult.NetworkError("empty body", IOException("empty body"))
                        FictionResult.Success(json.decodeFromString<T>(text))
                    }
                }
            }
        } catch (e: IOException) {
            FictionResult.NetworkError(e.message ?: "fetch failed", e)
        } catch (e: kotlinx.serialization.SerializationException) {
            FictionResult.NetworkError("Outline returned unexpected JSON shape", e)
        }
    }

    companion object {
        const val USER_AGENT = "storyvox-outline/1.0 (+https://github.com/jphein/storyvox)"
    }
}

// ── Wire types ──────────────────────────────────────────────────

@Serializable
internal data class OutlineCollection(
    val id: String,
    val name: String,
    val description: String? = null,
    val color: String? = null,
)

@Serializable
internal data class OutlineDocument(
    val id: String,
    val title: String,
    val collectionId: String? = null,
    val updatedAt: String? = null,
    val publishedAt: String? = null,
)

@Serializable
internal data class OutlineDocumentDetail(
    val id: String,
    val title: String,
    val text: String,
    val collectionId: String? = null,
    val updatedAt: String? = null,
    val publishedAt: String? = null,
)

@Serializable
private data class CollectionsResponse(val data: List<OutlineCollection> = emptyList())

@Serializable
private data class DocumentsResponse(
    val data: List<OutlineDocument> = emptyList(),
    val pagination: PaginationInfo? = null,
)

@Serializable
private data class PaginationInfo(val offset: Int = 0, val limit: Int = 0, val nextPath: String? = null)

@Serializable
private data class DocumentInfoResponse(val data: OutlineDocumentDetail)

@Serializable
private data class DocumentsRequest(
    val collectionId: String,
    val offset: Int = 0,
    val limit: Int = 100,
)
