package `in`.jphe.storyvox.source.royalroad.parser

import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.source.royalroad.model.RoyalRoadIds
import `in`.jphe.storyvox.source.royalroad.model.extractFictionIdFromHref
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Parses a Royal Road browse / search results page into Selene's
 * [ListPage]<[FictionSummary]> shape.
 *
 * Browse pages don't expose author or full status — those are filled in by the
 * detail-page fetch when the user opens a fiction. We map RR's `Original`/`Fan
 * Fiction` type pill to nothing here (it lives on `FictionDetail`), and infer
 * [FictionStatus] from the row's status label, defaulting to ONGOING.
 */
internal object BrowseParser {

    fun parse(html: String, currentPage: Int): ListPage<FictionSummary> {
        val doc = Jsoup.parse(html, RoyalRoadIds.BASE_URL)
        // Defense in depth — RR's browse pages don't typically carry the chapter
        // honeypot, but stripping is cheap insurance against future shifts.
        HoneypotFilter.strip(doc)

        val items = doc.select("div.fiction-list-item.row").mapNotNull(::parseRow)
        val hasNext = hasNextPage(doc, currentPage)
        return ListPage(items = items, page = currentPage, hasNext = hasNext)
    }

    private fun parseRow(row: Element): FictionSummary? {
        val titleAnchor = row.selectFirst("h2.fiction-title a") ?: return null
        val href = titleAnchor.attr("href")
        val fictionId = extractFictionIdFromHref(href) ?: return null
        val title = titleAnchor.text().trim().ifEmpty { return null }

        val cover = row.selectFirst("figure img")?.let { absoluteCoverUrl(it) }

        val tags = row.select("a.fiction-tag").mapNotNull { tag ->
            tag.attr("href").substringAfter("tagsAdd=", "").substringBefore("&").trim()
                .takeIf { it.isNotEmpty() }
        }

        val statusLabels = row.select("span.label.bg-blue-hoki").map { it.text().trim() }
        val status = statusLabels.firstNotNullOfOrNull { mapStatus(it) } ?: FictionStatus.ONGOING

        val rating = row.selectFirst("[aria-label^=\"Rating:\"]")
            ?.attr("aria-label")
            ?.let { Regex("""Rating:\s*([0-9.]+)""").find(it)?.groupValues?.get(1)?.toFloatOrNull() }

        val chapters = row.select("div.col-sm-6 span")
            .firstOrNull { it.text().contains("Chapters", ignoreCase = true) }
            ?.let { Regex("""([0-9][0-9,]*)""").find(it.text())?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() }

        val description = row.selectFirst("[id^=description-]")?.text()?.trim()?.ifEmpty { null }

        return FictionSummary(
            id = fictionId,
            sourceId = RoyalRoadIds.SOURCE_ID,
            title = title,
            // Browse rows don't expose the author. The detail-page fetch fills it in.
            author = "",
            coverUrl = cover,
            description = description,
            tags = tags,
            status = status,
            chapterCount = chapters,
            rating = rating,
        )
    }

    private fun absoluteCoverUrl(img: Element): String? {
        // Issue #283 — Royal Road's browse listing switched to lazy-loaded
        // images sometime in late 2025: the `<img>` element now carries
        // the real cover URL on `data-src` (or `data-lazy-src`), and
        // the literal `src` attribute is a placeholder
        // (typically `nocover-new-min.png` or a 1×1 pixel data URI)
        // until JavaScript swaps it in on viewport entry. Since our
        // parser runs on raw HTML with no JS execution, reading `src`
        // alone yielded the placeholder and we mapped it to null →
        // every browse card showed the brass '?' placeholder.
        //
        // Prefer the lazy attributes when present; fall through to
        // `src` as a defensive fallback so the parser keeps working if
        // RR reverts to non-lazy markup. The placeholder check below
        // still strips the nocover sentinel either way.
        val src = listOf("data-src", "data-lazy-src", "src")
            .map { img.attr(it) }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?: return null
        if (src.endsWith("/dist/img/nocover-new-min.png")) return null
        return when {
            src.startsWith("http") -> src
            src.startsWith("//") -> "https:$src"
            src.startsWith("/") -> "${RoyalRoadIds.BASE_URL}$src"
            else -> src
        }
    }

    private fun mapStatus(label: String): FictionStatus? = when (label.uppercase()) {
        "ONGOING" -> FictionStatus.ONGOING
        "COMPLETED" -> FictionStatus.COMPLETED
        "HIATUS" -> FictionStatus.HIATUS
        "STUB" -> FictionStatus.STUB
        "DROPPED" -> FictionStatus.DROPPED
        else -> null
    }

    private fun hasNextPage(doc: Document, currentPage: Int): Boolean {
        // RR pagination: <ul class="pagination"> with <a data-page="N">. The active
        // page has `class="page-active"` on its <li>. Next exists if any link points
        // to a page > currentPage, or if there's a "Next" anchor.
        val pages = doc.select("ul.pagination li a[data-page]")
            .mapNotNull { it.attr("data-page").toIntOrNull() }
        return pages.any { it > currentPage }
    }
}
