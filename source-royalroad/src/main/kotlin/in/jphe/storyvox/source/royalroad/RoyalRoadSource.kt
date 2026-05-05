package `in`.jphe.storyvox.source.royalroad

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.source.royalroad.net.CloudflareAwareFetcher
import `in`.jphe.storyvox.source.royalroad.net.RateLimitedClient
import `in`.jphe.storyvox.source.royalroad.net.RoyalRoadCookieJar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1 stub. The full RoyalRoadSource impl (with parsers, search, browse, follows)
 * is in `_unintegrated/` pending an integration pass that bridges Oneiros's
 * parser output types to Selene's [FictionSource] interface shapes.
 *
 * The auth/net infrastructure (rate-limited HTTP, Cloudflare-aware fetcher,
 * cookie jar, login WebView, honeypot filter) is wired and ready to be plumbed
 * back in. This stub returns Failure for every read so the build is green
 * end-to-end while the integration is finished separately.
 *
 * See `docs/superpowers/specs/2026-05-05-storyvox-design.md` §5 and
 * `scratch/dreamers/oneiros.md` for the full plan.
 */
@Singleton
class RoyalRoadSource @Inject internal constructor(
    @Suppress("unused") private val fetcher: CloudflareAwareFetcher,
    @Suppress("unused") private val client: RateLimitedClient,
    @Suppress("unused") private val cookieJar: RoyalRoadCookieJar,
) : FictionSource {

    override val id: String = "royalroad"
    override val displayName: String = "Royal Road"

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> = unimplemented()
    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> = unimplemented()
    override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> = unimplemented()
    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> = unimplemented()
    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> = unimplemented()
    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> = unimplemented()
    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> = unimplemented()
    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> = unimplemented()
    override suspend fun genres(): FictionResult<List<String>> = FictionResult.Success(KnownTagSlugs)

    private fun <T> unimplemented(): FictionResult<T> =
        FictionResult.NetworkError(
            message = "RoyalRoadSource integration pending — see source-royalroad/_unintegrated/",
            cause = NotImplementedError("RoyalRoadSource v1 stub"),
        )

    private companion object {
        /** RR's tag slug taxonomy — surfaces something for the genre picker until the live impl lands. */
        val KnownTagSlugs = listOf(
            "loop", "adventure", "fantasy", "mystery", "magic", "litrpg", "progression",
            "cultivation", "xianxia", "gamelit", "harem", "villainous_lead", "martial_arts",
            "mythos", "dungeon_core", "dystopia", "post_apocalyptic", "reincarnation",
            "time_travel", "wuxia", "super_heroes", "school_life", "slice_of_life",
            "psychological", "tragedy", "urban_fantasy", "low_fantasy", "high_fantasy",
            "gender_bender", "multiple_lead", "attractive_lead", "non-human_lead", "strong_lead",
            "weak_to_strong", "summoned_hero", "anti-hero_lead", "male_lead", "female_lead",
            "secret_identity", "space_opera", "steampunk", "historical", "mythical_beasts",
            "magical_realism", "contemporary", "satire", "tutorial", "xuanhuan",
        )
    }
}
