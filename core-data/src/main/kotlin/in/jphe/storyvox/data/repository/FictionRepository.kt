package `in`.jphe.storyvox.data.repository

import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import `in`.jphe.storyvox.data.db.entity.DownloadMode
import `in`.jphe.storyvox.data.db.entity.Fiction
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
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

    /**
     * Browse the popular fictions on [sourceId]. Defaults to
     * [SourceIds.ROYAL_ROAD] for backwards-compat with existing
     * callers; UI surfaces that route to other sources (e.g.
     * Browse → GitHub) pass the chosen sourceId explicitly.
     */
    suspend fun browsePopular(
        page: Int,
        sourceId: String = SourceIds.ROYAL_ROAD,
    ): FictionResult<ListPage<FictionSummary>>

    suspend fun browseLatest(
        page: Int,
        sourceId: String = SourceIds.ROYAL_ROAD,
    ): FictionResult<ListPage<FictionSummary>>

    suspend fun browseByGenre(
        genre: String,
        page: Int,
        sourceId: String = SourceIds.ROYAL_ROAD,
    ): FictionResult<ListPage<FictionSummary>>

    suspend fun search(
        query: SearchQuery,
        sourceId: String = SourceIds.ROYAL_ROAD,
    ): FictionResult<ListPage<FictionSummary>>

    /**
     * Cache an externally-fetched browse listing the same way
     * [browsePopular] / [search] do — `upsertAllPreservingUserState` so
     * a subsequent `refreshDetail(id)` finds a row with the right
     * `sourceId` and routes to the correct source.
     *
     * Used by source-specific listing endpoints that don't fit
     * [SearchQuery] (e.g. GitHub `/user/repos` for #200) so their
     * results materialize as DB-backed fictions when the user taps a
     * card. [result] is returned untouched on either success or
     * failure; callers use this as a transparent passthrough.
     */
    suspend fun cacheBrowseListing(
        result: FictionResult<ListPage<FictionSummary>>,
    ): FictionResult<ListPage<FictionSummary>>

    suspend fun genres(
        sourceId: String = SourceIds.ROYAL_ROAD,
    ): FictionResult<List<String>>

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
     * Resolves a [FictionSource] by [sourceId]. Errors loudly if the
     * key isn't bound — callers either pass a known sourceId from
     * [SourceIds] or look one up from a persisted Fiction row. The
     * old "fall back to the only source" behaviour from #35 was
     * removed in step 8a-i now that multiple sources are bound.
     */
    private fun sourceFor(sourceId: String): FictionSource =
        sources[sourceId]
            ?: error("No FictionSource for id=$sourceId; bound: ${sources.keys}")

    // Issue #382 — small helper to look up `supportsFollow` on the
    // FictionSource the row came from. Returns false for unknown
    // sourceIds (a fiction row whose backend module has since been
    // unbound), which is the safest UI default — hides the Follow
    // button rather than wiring a never-firing path.
    private fun supportsFollowFor(sourceId: String): Boolean =
        sources[sourceId]?.supportsFollow ?: false

    override fun observeLibrary(): Flow<List<FictionSummary>> =
        fictionDao.observeLibrary().map { rows ->
            rows.map { it.toSummary(supportsFollow = supportsFollowFor(it.sourceId)) }
        }

    override fun observeFollowsRemote(): Flow<List<FictionSummary>> =
        fictionDao.observeFollowsRemote().map { rows ->
            rows.map { it.toSummary(supportsFollow = supportsFollowFor(it.sourceId)) }
        }

    override fun observeFiction(id: String): Flow<FictionDetail?> =
        fictionDao.observe(id).combine(chapterDao.observeChapterInfosByFiction(id)) { fiction, chapters ->
            fiction?.let {
                FictionDetail(
                    summary = it.toSummary(supportsFollow = supportsFollowFor(it.sourceId)),
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

    override suspend fun browsePopular(
        page: Int,
        sourceId: String,
    ): FictionResult<ListPage<FictionSummary>> =
        cacheListing(sourceFor(sourceId).popular(page))

    override suspend fun browseLatest(
        page: Int,
        sourceId: String,
    ): FictionResult<ListPage<FictionSummary>> =
        cacheListing(sourceFor(sourceId).latestUpdates(page))

    override suspend fun browseByGenre(
        genre: String,
        page: Int,
        sourceId: String,
    ): FictionResult<ListPage<FictionSummary>> =
        cacheListing(sourceFor(sourceId).byGenre(genre, page))

    override suspend fun search(
        query: SearchQuery,
        sourceId: String,
    ): FictionResult<ListPage<FictionSummary>> =
        cacheListing(sourceFor(sourceId).search(query))

    override suspend fun genres(sourceId: String): FictionResult<List<String>> =
        sourceFor(sourceId).genres()

    override suspend fun cacheBrowseListing(
        result: FictionResult<ListPage<FictionSummary>>,
    ): FictionResult<ListPage<FictionSummary>> = cacheListing(result)

    override suspend fun refreshDetail(id: String): FictionResult<Unit> = withContext(Dispatchers.IO) {
        // Look up the persisted row to route to the correct source. Falls
        // back to the default source when the row is absent (first-add flow:
        // addToLibrary → refreshDetail before the row exists). Future
        // multi-source addByUrl pre-writes a stub row with sourceId so this
        // path always finds it.
        val src = sourceFor(fictionDao.get(id)?.sourceId ?: SourceIds.ROYAL_ROAD)
        when (val result = src.fictionDetail(id)) {
            is FictionResult.Success -> {
                upsertDetail(result.value)
                FictionResult.Success(Unit)
            }
            is FictionResult.Failure -> result
        }
    }

    override suspend fun refreshRemoteFollows(): FictionResult<Unit> = withContext(Dispatchers.IO) {
        // Follows is RR-only today — GitHub source throws on
        // `followsList`. When step 3f wires GitHub PAT auth, this
        // becomes a per-source flow and the kdoc on the interface
        // method should grow a `sourceId` parameter to match.
        when (val result = sourceFor(SourceIds.ROYAL_ROAD).followsList(page = 1)) {
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
            val src = sourceFor(fictionDao.get(id)?.sourceId ?: SourceIds.ROYAL_ROAD)
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
        // Issue #349 — RSS feeds (and any future window-style backend)
        // reorder rows across refreshes. Plain upsertAll trips the
        // (fictionId, index) UNIQUE constraint mid-batch because Room's
        // @Upsert is per-row and the constraint check is immediate.
        // upsertChaptersForFiction parks existing rows above the live
        // range first, then upserts atomically inside a transaction.
        // Costs one extra UPDATE per refresh; Royal Road's append-only
        // case is unaffected by the parking pass.
        chapterDao.upsertChaptersForFiction(detail.summary.id, merged)
    }
}

// ─── mappers ──────────────────────────────────────────────────────────────

internal fun Fiction.toSummary(supportsFollow: Boolean = false): FictionSummary = FictionSummary(
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
    followedRemotely = followedRemotely,
    supportsFollow = supportsFollow,
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
        // Issue #279 — never overwrite a previously-good title / author
        // with a worse one. The RSS source falls back to the URL host
        // when the feed parse comes up blank (intermittent gateway
        // timeouts, momentarily-malformed XML, Cloudflare 524, etc.),
        // which produced a perfectly non-blank but useless string like
        // "lionsroar.com". The result: pull-to-refresh silently corrupted
        // the Library card from 'Lion's Roar / Rev. Marvin Harada' to
        // 'lionsroar.com / ?'.
        //
        // Defensive: if we have a non-blank existing title (the user has
        // seen it before) and the incoming title looks like a degraded
        // fallback — either blank OR a bare host string that matches the
        // last cached description / cover URL host — keep the cached
        // value. Same pattern for author. Sources that genuinely return
        // a richer title are unaffected: the equality check only fires
        // when the new title literally is the URL host fallback.
        title = preferTitle(incoming = summary.title, existing = base.title, sourceFallback = inferUrlHost(summary.description)),
        author = summary.author.ifBlank { base.author },
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

/**
 *  Issue #279 — title-degradation guard for [FictionDetail.toEntity].
 *
 *  Returns the incoming title unless it looks like a degraded source
 *  fallback (blank, OR equal to the URL host extracted from the same
 *  detail's description). In the degraded case we keep the existing
 *  cached title — assuming we have one. First-add flows where the
 *  existing row is the just-created stub get the incoming title verbatim
 *  (the stub's title is also derived from the same source, so there's
 *  no "better" alternative to preserve).
 *
 *  The host-equality check is what catches the [RssSource] failure mode
 *  specifically: when `feed.title.isBlank()`, RSS returns
 *  `displayLabelForUrl(sub.url)` which is `host.removePrefix("www.")`.
 *  That string is structurally distinguishable from a real title
 *  ("lionsroar.com" vs "Lion's Roar"), and from a real description, so
 *  catching it here rather than asking every source to opt in keeps the
 *  guard durable.
 */
internal fun preferTitle(incoming: String, existing: String, sourceFallback: String?): String {
    if (incoming.isBlank()) return existing.ifBlank { incoming }
    if (existing.isBlank()) return incoming
    if (sourceFallback != null && incoming.equals(sourceFallback, ignoreCase = true)) {
        return existing
    }
    return incoming
}

/** Issue #279 — pull a bare host out of a URL-shaped string. Used as
 *  the second axis of the title-degradation check in [preferTitle].
 *  Returns null when the input doesn't parse as a URI or has no host
 *  (so [preferTitle] falls back to the trust-the-incoming branch). */
internal fun inferUrlHost(maybeUrl: String?): String? {
    if (maybeUrl.isNullOrBlank()) return null
    return runCatching {
        java.net.URI(maybeUrl).host?.removePrefix("www.")
    }.getOrNull()
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
