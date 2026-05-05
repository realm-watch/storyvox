package `in`.jphe.storyvox.source.royalroad.parser

import `in`.jphe.storyvox.core.data.source.FictionSummary
import `in`.jphe.storyvox.source.royalroad.model.RoyalRoadIds
import `in`.jphe.storyvox.source.royalroad.model.extractFictionIdFromHref
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * RR's profile page lists a logged-in user's bookmarks/follows. Layout
 * mirrors the search result card (div.fiction-list-item), so we delegate
 * to SearchParser's row reader for each list item we find.
 *
 * If the response redirects to /home, the user is unauthed.
 */
internal object FollowsParser {

    fun parse(html: String, finalUrl: String): FollowsResult {
        if ("/home" in finalUrl && "/profile/" !in finalUrl) {
            return FollowsResult.NotAuthenticated
        }
        val doc = Jsoup.parse(html, RoyalRoadIds.BASE_URL)
        val items = doc.select("div.fiction-list-item.row").mapNotNull { parseSummary(it) }
        return FollowsResult.Ok(items)
    }

    private fun parseSummary(row: org.jsoup.nodes.Element): FictionSummary? {
        val anchor = row.selectFirst("h2.fiction-title a") ?: return null
        val fictionId = extractFictionIdFromHref(anchor.attr("href")) ?: return null
        return FictionSummary(
            sourceId = RoyalRoadIds.SOURCE_ID,
            fictionId = fictionId,
            title = anchor.text().trim(),
            coverImageUrl = row.selectFirst("figure img")?.attr("src"),
            statusRaw = null,
            typeRaw = null,
            tags = emptyList(),
            shortDescription = null,
            followers = null,
            pageCount = null,
            views = null,
            chapterCount = null,
            lastUpdatedEpochSec = null,
        )
    }

    /** Looks for a fresh CSRF token for state-changing posts. */
    fun extractCsrfToken(html: String): String? {
        val doc = Jsoup.parse(html, RoyalRoadIds.BASE_URL)
        return doc.selectFirst("input[name=__RequestVerificationToken]")?.attr("value")?.takeIf { it.isNotBlank() }
    }
}

internal sealed interface FollowsResult {
    data class Ok(val items: List<`in`.jphe.storyvox.core.data.source.FictionSummary>) : FollowsResult
    data object NotAuthenticated : FollowsResult
}
