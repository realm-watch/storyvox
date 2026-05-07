package `in`.jphe.storyvox.data.repository

import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import `in`.jphe.storyvox.data.db.entity.DownloadMode
import `in`.jphe.storyvox.data.db.entity.Fiction
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.UrlRouter
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The aggregate read/write surface over fiction metadata. UI never touches
 * Room directly; everything goes through this interface.
 */
interface FictionRepository {

    fun observeLibrary(): Flow<List<FictionSummary>>
    fun observeFollowsRemote(): Flow<List<FictionSummary>>
    fun observeFiction(id: String): Flow<FictionDetail?>

    suspend fun browsePopular(page: Int): FictionResult<ListPage<FictionSummary>>
    suspend fun browseLatest(page: Int): FictionResult<ListPage<FictionSummary>>
    suspend fun browseByGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>>
    suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>>
    suspend fun genres(): FictionResult<List<String>>

    /** Force a detail-page refresh, upserting the cached row. */
    suspend fun refreshDetail(id: String): FictionResult<Unit>

    /**
     * Re-fetch the user's source-side follows list and reconcile against the
     * local DB: rows that appear remotely get `followedRemotely = true`, rows
     * that previously had it but are absent get cleared. Requires an
     * authenticated session; returns [FictionResult.AuthRequired] if not.
     */
    suspend fun refreshRemoteFollows(): FictionResult<Unit>

    suspend fun addToLibrary(id: String, mode: DownloadMode? = null)
    suspend fun removeFromLibrary(id: String)
    suspend fun setDownloadMode(id: String, mode: DownloadMode?)
    suspend fun setPinnedVoice(id: String, voiceId: String?, locale: String?)

    suspend fun setFollowedRemote(id: String, followed: Boolean): FictionResult<Unit>

    /**
     * Resolve a pasted URL (or short form like `owner/repo`) to a fiction,
     * persist a stub row, and refresh its detail. Returns the resolved
     * `fictionId` on success so the UI can navigate to the detail screen.
     *
     * Recognised-but-unsupported sources (GitHub today) return
     * [AddByUrlResult.UnsupportedSource] so the UI can surface a
     * "coming soon" message without the user thinking they pasted
     * something invalid.
     */
    suspend fun addByUrl(url: String): AddByUrlResult
}

/** Outcome of [FictionRepository.addByUrl]. */
sealed class AddByUrlResult {
    /** URL parsed, source supported, detail fetched + persisted. */
    data class Success(val fictionId: String) : AddByUrlResult()

    /** No source's URL pattern matched the input. */
    data object UnrecognizedUrl : AddByUrlResult()

    /** Pattern matched a known source that is wired but not yet implemented. */
    data class UnsupportedSource(val sourceId: String) : AddByUrlResult()

    /** Source-layer failure (network, 404, auth, rate limit, Cloudflare, ...). */
    data class SourceFailure(val failure: FictionResult.Failure) : AddByUrlResult()
}

@Singleton
class FictionRepositoryImpl @Inject constructor(
    private val sources: Map<String, @JvmSuppressWildcards FictionSource>,
    private val fictionDao: FictionDao,
    private val chapterDao: ChapterDao,
) : FictionRepository {

    /**
     * Resolves a [FictionSource] by [sourceId]. When [sourceId] is null or
     * not bound, falls back to the only source in the map — preserves
     * single-source behaviour today and short-circuits the catalog calls
     * (browse, search, genres) that don't yet have a per-source UX. Errors
     * if multiple sources are bound and the key is missing/unknown — that's
     * a programming bug we want to catch loudly when GitHub source lands.
     */
    private fun sourceFor(sourceId: String?): FictionSource =
        sourceId?.let { sources[it] }
            ?: sources.values.singleOrNull()
            ?: error("No FictionSource for id=$sourceId; bound: ${sources.keys}")

    private val source: FictionSource get() = sourceFor(null)

    override fun observeLibrary(): Flow<List<FictionSummary>> =
        fictionDao.observeLibrary().map { rows -> rows.map { it.toSummary() } }

    override fun observeFollowsRemote(): Flow<List<FictionSummary>> =
        fictionDao.observeFollowsRemote().map { rows -> rows.map { it.toSummary() } }

    override fun observeFiction(id: String): Flow<FictionDetail?> =
        fictionDao.observe(id).combine(chapterDao.observeChapterInfosByFiction(id)) { fiction, chapters ->
            fiction?.let {
                FictionDetail(
                    summary = it.toSummary(),
                    chapters = chapters.map(::toInfo),
                    genres = it.genres,
                    wordCount = it.wordCount,
                    views = it.views,
                    followers = it.followers,
                    lastUpdatedAt = it.lastUpdatedAt,
                    authorId = it.authorId,
                )
            }
        }

    override suspend fun browsePopular(page: Int): FictionResult<ListPage<FictionSummary>> =
        cacheListing(source.popular(page))

    override suspend fun browseLatest(page: Int): FictionResult<ListPage<FictionSummary>> =
        cacheListing(source.latestUpdates(page))

    override suspend fun browseByGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> =
        cacheListing(source.byGenre(genre, page))

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> =
        cacheListing(source.search(query))

    override suspend fun genres(): FictionResult<List<String>> = source.genres()

    override suspend fun refreshDetail(id: String): FictionResult<Unit> = withContext(Dispatchers.IO) {
        // Look up the persisted row to route to the correct source. Falls
        // back to the default source when the row is absent (first-add flow:
        // addToLibrary → refreshDetail before the row exists). Future
        // multi-source addByUrl pre-writes a stub row with sourceId so this
        // path always finds it.
        val src = sourceFor(fictionDao.get(id)?.sourceId)
        when (val result = src.fictionDetail(id)) {
            is FictionResult.Success -> {
                upsertDetail(result.value)
                FictionResult.Success(Unit)
            }
            is FictionResult.Failure -> result
        }
    }

    override suspend fun refreshRemoteFollows(): FictionResult<Unit> = withContext(Dispatchers.IO) {
        when (val result = source.followsList(page = 1)) {
            is FictionResult.Success -> {
                val now = System.currentTimeMillis()
                val incoming = result.value.items
                val incomingIds = incoming.map { it.id }.toSet()

                // Upsert each follow, preserving prior fields (the follows
                // page is row-shape only — author/status/rating come from
                // detail-page refresh later) and flipping followedRemotely.
                incoming.forEach { summary ->
                    val existing = fictionDao.get(summary.id)
                    val merged = if (existing != null) {
                        existing.copy(
                            // Refresh whatever the rows do carry without
                            // clobbering richer detail-page fields.
                            title = summary.title.ifBlank { existing.title },
                            coverUrl = summary.coverUrl ?: existing.coverUrl,
                            tags = summary.tags.ifEmpty { existing.tags },
                            followedRemotely = true,
                        )
                    } else {
                        summary.toEntity(now).copy(followedRemotely = true)
                    }
                    fictionDao.upsert(merged)
                }

                // Clear followedRemotely on rows that aren't in the latest
                // list (user unfollowed remotely on the website).
                val previously = fictionDao.followsSnapshot().map { it.id }.toSet()
                (previously - incomingIds).forEach { gone ->
                    fictionDao.setFollowedRemote(gone, false)
                }

                FictionResult.Success(Unit)
            }
            is FictionResult.Failure -> result
        }
    }

    override suspend fun addToLibrary(id: String, mode: DownloadMode?) = withContext(Dispatchers.IO) {
        // Ensure the row exists — refresh from source if we don't have it.
        val existing = fictionDao.get(id)
        if (existing == null) refreshDetail(id) // best-effort; ignore failure
        fictionDao.setInLibrary(id, true, System.currentTimeMillis())
        if (mode != null) fictionDao.setDownloadMode(id, mode)
        Unit
    }

    override suspend fun removeFromLibrary(id: String) = withContext(Dispatchers.IO) {
        fictionDao.setInLibrary(id, false, System.currentTimeMillis())
        // Eviction policy: keep the metadata around (no auto-evict per spec).
        // Caller may explicitly purge transient rows via deleteIfTransient.
        fictionDao.deleteIfTransient(id)
        Unit
    }

    override suspend fun setDownloadMode(id: String, mode: DownloadMode?) {
        fictionDao.setDownloadMode(id, mode)
    }

    override suspend fun setPinnedVoice(id: String, voiceId: String?, locale: String?) {
        fictionDao.setPinnedVoice(id, voiceId, locale)
    }

    override suspend fun setFollowedRemote(id: String, followed: Boolean): FictionResult<Unit> =
        withContext(Dispatchers.IO) {
            val src = sourceFor(fictionDao.get(id)?.sourceId)
            when (val r = src.setFollowed(id, followed)) {
                is FictionResult.Success -> {
                    fictionDao.setFollowedRemote(id, followed)
                    FictionResult.Success(Unit)
                }
                is FictionResult.Failure -> r
            }
        }

    override suspend fun addByUrl(url: String): AddByUrlResult = withContext(Dispatchers.IO) {
        val match = UrlRouter.route(url) ?: return@withContext AddByUrlResult.UnrecognizedUrl
        val src = sources[match.sourceId]
            ?: return@withContext AddByUrlResult.UnsupportedSource(match.sourceId)

        // Pre-write a stub row carrying sourceId so refreshDetail (and any
        // subsequent setFollowedRemote / ChapterDownloadWorker) can route
        // to the right source even before the detail fetch completes. The
        // upsert only seeds fields we know from the URL; richer fields are
        // filled in by upsertDetail on success.
        val now = System.currentTimeMillis()
        if (fictionDao.get(match.fictionId) == null) {
            fictionDao.upsert(
                Fiction(
                    id = match.fictionId,
                    sourceId = match.sourceId,
                    title = "",
                    author = "",
                    firstSeenAt = now,
                    metadataFetchedAt = now,
                ),
            )
        }

        when (val r = src.fictionDetail(match.fictionId)) {
            is FictionResult.Success -> {
                upsertDetail(r.value)
                AddByUrlResult.Success(match.fictionId)
            }
            is FictionResult.Failure -> AddByUrlResult.SourceFailure(r)
        }
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private suspend fun cacheListing(
        result: FictionResult<ListPage<FictionSummary>>,
    ): FictionResult<ListPage<FictionSummary>> {
        if (result is FictionResult.Success) {
            val now = System.currentTimeMillis()
            fictionDao.upsertAllPreservingUserState(result.value.items.map { it.toEntity(now) })
        }
        return result
    }

    private suspend fun upsertDetail(detail: FictionDetail) {
        val now = System.currentTimeMillis()
        val existing = fictionDao.get(detail.summary.id)
        fictionDao.upsert(detail.toEntity(existing, now))

        // Upsert chapter rows; preserve body + download state for chapters we
        // already have, drop in fresh metadata for new ones.
        val incoming = detail.chapters.map { it.toEntity(detail.summary.id) }
        val merged = incoming.map { fresh ->
            val previous = chapterDao.get(fresh.id)
            if (previous == null) fresh else fresh.copy(
                htmlBody = previous.htmlBody,
                plainBody = previous.plainBody,
                bodyFetchedAt = previous.bodyFetchedAt,
                bodyChecksum = previous.bodyChecksum,
                downloadState = previous.downloadState,
                lastDownloadAttemptAt = previous.lastDownloadAttemptAt,
                lastDownloadError = previous.lastDownloadError,
                userMarkedRead = previous.userMarkedRead,
                firstReadAt = previous.firstReadAt,
            )
        }
        chapterDao.upsertAll(merged)
    }
}

// ─── mappers ──────────────────────────────────────────────────────────────

internal fun Fiction.toSummary(): FictionSummary = FictionSummary(
    id = id,
    sourceId = sourceId,
    title = title,
    author = author,
    coverUrl = coverUrl,
    description = description,
    tags = tags,
    status = status,
    chapterCount = chapterCount,
    rating = rating,
)

internal fun FictionSummary.toEntity(now: Long): Fiction = Fiction(
    id = id,
    sourceId = sourceId,
    title = title,
    author = author,
    coverUrl = coverUrl,
    description = description,
    tags = tags,
    status = status,
    chapterCount = chapterCount ?: 0,
    rating = rating,
    firstSeenAt = now,
    metadataFetchedAt = now,
)

internal fun FictionDetail.toEntity(existing: Fiction?, now: Long): Fiction {
    val base = existing ?: summary.toEntity(now)
    return base.copy(
        title = summary.title,
        author = summary.author,
        authorId = authorId ?: base.authorId,
        coverUrl = summary.coverUrl ?: base.coverUrl,
        description = summary.description ?: base.description,
        genres = genres,
        tags = summary.tags.ifEmpty { base.tags },
        status = summary.status,
        chapterCount = chapters.size,
        wordCount = wordCount ?: base.wordCount,
        rating = summary.rating ?: base.rating,
        views = views ?: base.views,
        followers = followers ?: base.followers,
        lastUpdatedAt = lastUpdatedAt ?: base.lastUpdatedAt,
        metadataFetchedAt = now,
    )
}

internal fun ChapterInfo.toEntity(fictionId: String): Chapter = Chapter(
    id = id,
    fictionId = fictionId,
    sourceChapterId = sourceChapterId,
    index = index,
    title = title,
    publishedAt = publishedAt,
    wordCount = wordCount,
    downloadState = ChapterDownloadState.NOT_DOWNLOADED,
)

internal fun Chapter.toInfo(): ChapterInfo = ChapterInfo(
    id = id,
    sourceChapterId = sourceChapterId,
    index = index,
    title = title,
    publishedAt = publishedAt,
    wordCount = wordCount,
)

internal fun toInfo(row: `in`.jphe.storyvox.data.db.dao.ChapterInfoRow): ChapterInfo = ChapterInfo(
    id = row.id,
    sourceChapterId = row.sourceChapterId,
    index = row.index,
    title = row.title,
    publishedAt = row.publishedAt,
    wordCount = row.wordCount,
)
