package `in`.jphe.storyvox.source.royalroad.parser

import `in`.jphe.storyvox.core.data.source.BrowsePage
import `in`.jphe.storyvox.core.data.source.FictionSummary
import `in`.jphe.storyvox.source.royalroad.model.RoyalRoadIds
import `in`.jphe.storyvox.source.royalroad.model.extractFictionIdFromHref
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object SearchParser {

    fun parse(html: String): BrowsePage {
        val doc = Jsoup.parse(html, RoyalRoadIds.BASE_URL)
        val items = doc.select("div.fiction-list-item.row").mapNotNull(::parseRow)
        val (current, total) = parsePagination(doc)
        return BrowsePage(items = items, page = current, totalPages = total)
    }

    private fun parseRow(row: Element): FictionSummary? {
        val titleAnchor = row.selectFirst("h2.fiction-title a") ?: return null
        val href = titleAnchor.attr("href")
        val fictionId = extractFictionIdFromHref(href) ?: return null
        val title = titleAnchor.text().trim()

        val cover = row.selectFirst("figure img")?.attr("src")
            ?.takeIf { !it.endsWith("/dist/img/nocover-new-min.png") }

        val pills = row.select("span.label.bg-blue-hoki").map { it.text().trim() }
        val statusLabel = pills.firstOrNull { it.uppercase() in STATUS_VOCAB }
        val typeLabel = pills.firstOrNull { it.uppercase() in TYPE_VOCAB }

        val tags = row.select("a.fiction-tag").map { tag ->
            tag.attr("href").substringAfter("tagsAdd=").substringBefore("&").trim()
        }.filter { it.isNotEmpty() }

        val statRow = row.select("div.stats div")
        val followers = statValue(statRow, "Followers")
        val pages = statValue(statRow, "Pages")
        val views = statValue(statRow, "Views")
        val chapters = statValue(statRow, "Chapters")
        val lastUpdated = row.selectFirst("time[unixtime]")?.attr("unixtime")?.toLongOrNull()
        val description = row.selectFirst("[id^=description-]")?.text()?.trim()

        return FictionSummary(
            sourceId = RoyalRoadIds.SOURCE_ID,
            fictionId = fictionId,
            title = title,
            coverImageUrl = cover,
            statusRaw = statusLabel,
            typeRaw = typeLabel,
            tags = tags,
            shortDescription = description,
            followers = followers,
            pageCount = pages,
            views = views,
            chapterCount = chapters,
            lastUpdatedEpochSec = lastUpdated,
        )
    }

    private fun statValue(stats: org.jsoup.select.Elements, keyword: String): Long? {
        val cell = stats.firstOrNull { it.text().contains(keyword, ignoreCase = true) }
            ?: return null
        val number = Regex("""([0-9][0-9,]*)""").find(cell.text())?.groupValues?.get(1)
        return number?.replace(",", "")?.toLongOrNull()
    }

    private fun parsePagination(doc: Document): Pair<Int, Int> {
        val items = doc.select("ul.pagination li a[data-page]")
        if (items.isEmpty()) return 1 to 1
        val pages = items.mapNotNull { it.attr("data-page").toIntOrNull() }
        val current = doc.selectFirst("ul.pagination li.page-active a[data-page]")?.attr("data-page")?.toIntOrNull() ?: 1
        val total = pages.maxOrNull() ?: 1
        return current to total
    }

    private val STATUS_VOCAB = setOf("ONGOING", "COMPLETED", "HIATUS", "STUB", "DROPPED", "INACTIVE")
    private val TYPE_VOCAB = setOf("ORIGINAL", "FAN FICTION")
}
