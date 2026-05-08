package `in`.jphe.storyvox.source.github

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.source.github.net.GitHubApi
import `in`.jphe.storyvox.source.github.registry.Registry
import `in`.jphe.storyvox.source.github.registry.RegistryEntry
import `in`.jphe.storyvox.source.github.registry.toSummary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GitHub [FictionSource]. Browse calls (popular/latestUpdates/byGenre/
 * genres) are wired to the curated [Registry] as of step 3c. Detail
 * and chapter still throw [NotImplementedError] — they land in step 3d
 * when manifest parsing returns real [FictionDetail] data. Search
 * lands in step 3-search (spec sequence step 8).
 *
 * **Still not Hilt-bound**: detail is the load-bearing call when the
 * UrlRouter routes a paste through [`in`.jphe.storyvox.data.repository
 * .FictionRepository.addByUrl], so binding the source before detail
 * works would break that flow. Step 3d adds the @IntoMap binding
 * once detail returns real data.
 */
@Singleton
internal class GitHubSource @Inject constructor(
    @Suppress("unused") // wired so the graph compiles when 3d adds detail
    private val api: GitHubApi,
    private val registry: Registry,
) : FictionSource {

    override val id: String = SourceIds.GITHUB
    override val displayName: String = "GitHub"

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        registryPage(page) { entries ->
            // Featured first, then the rest in registry order. Within each
            // band we keep the curator's authored ordering rather than
            // imposing a synthetic rank — curators sort hand-picks for a
            // reason.
            entries.sortedByDescending { it.featured }
        }

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        registryPage(page) { entries ->
            // `addedAt` is the only freshness signal we have pre-manifest.
            // ISO-8601 yyyy-MM-dd string-sorts correctly; entries without
            // an addedAt sort last (empty string < any date).
            entries.sortedByDescending { it.addedAt.orEmpty() }
        }

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        registryPage(page) { entries ->
            val needle = genre.trim().lowercase()
            if (needle.isBlank()) entries
            else entries.filter { it.tags.any { tag -> tag.equals(needle, ignoreCase = true) } }
        }

    override suspend fun genres(): FictionResult<List<String>> {
        return when (val r = registry.entries()) {
            is FictionResult.Success -> FictionResult.Success(
                r.value.flatMap { it.tags }
                    .map { it.lowercase() }
                    .distinct()
                    .sorted(),
            )
            is FictionResult.Failure -> r
        }
    }

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> =
        throw NotImplementedError(STEP_3_SEARCH)

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> =
        throw NotImplementedError(STEP_3D_DETAIL)

    override suspend fun chapter(
        @Suppress("UNUSED_PARAMETER") fictionId: String,
        @Suppress("UNUSED_PARAMETER") chapterId: String,
    ): FictionResult<ChapterContent> =
        throw NotImplementedError(STEP_3D_DETAIL)

    override suspend fun followsList(
        @Suppress("UNUSED_PARAMETER") page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        throw NotImplementedError(STEP_3F_AUTH)

    override suspend fun setFollowed(
        @Suppress("UNUSED_PARAMETER") fictionId: String,
        @Suppress("UNUSED_PARAMETER") followed: Boolean,
    ): FictionResult<Unit> =
        throw NotImplementedError(STEP_3F_AUTH)

    /**
     * Registry-backed paging. Single page today (registry is small +
     * hand-curated; pagination would be over-engineering until the
     * curated set exceeds a screenful). [transform] runs over the raw
     * [RegistryEntry] list — keeping `addedAt`/`featured` available
     * for sort/filter before mapping to the cross-source
     * [FictionSummary] shape.
     */
    private suspend fun registryPage(
        page: Int,
        transform: (List<RegistryEntry>) -> List<RegistryEntry>,
    ): FictionResult<ListPage<FictionSummary>> {
        // Page 2+ always empty — caller's pagination cursor short-
        // circuits the next-page fetch via hasNext=false on page 1.
        if (page > 1) {
            return FictionResult.Success(
                ListPage(items = emptyList(), page = page, hasNext = false),
            )
        }
        return when (val r = registry.entries()) {
            is FictionResult.Success -> FictionResult.Success(
                ListPage(
                    items = transform(r.value).map { it.toSummary() },
                    page = 1,
                    hasNext = false,
                ),
            )
            is FictionResult.Failure -> r
        }
    }

    private companion object {
        const val STEP_3D_DETAIL = "GitHub source detail/chapter not implemented yet — lands in step 3d (manifest parsing + markdown rendering)"
        const val STEP_3F_AUTH = "GitHub source auth-gated calls not implemented yet — lands in step 3f (optional PAT support)"
        const val STEP_3_SEARCH = "GitHub /search/repositories not implemented yet — lands in step 3-search (spec sequence step 8)"
    }
}
