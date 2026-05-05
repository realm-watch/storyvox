package `in`.jphe.storyvox.source.royalroad.parser

import `in`.jphe.storyvox.core.data.source.ChapterSummary
import `in`.jphe.storyvox.core.data.source.FictionDetails
import `in`.jphe.storyvox.core.data.source.FictionStatus
import `in`.jphe.storyvox.core.data.source.FictionType
import `in`.jphe.storyvox.source.royalroad.model.RoyalRoadIds
import `in`.jphe.storyvox.source.royalroad.model.extractChapterIdFromHref
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object FictionParser {

    fun parse(html: String, fictionId: String): FictionDetails {
        val doc = Jsoup.parse(html, RoyalRoadIds.BASE_URL)
        val ldJson = readJsonLd(doc)

        val title = doc.selectFirst("div.fic-header h1.font-white")?.text()?.trim()
            ?: ldJson["name"]
            ?: error("Could not parse fiction title")

        val authorEl = doc.selectFirst("div.fic-header h4 a")
        val authorName = authorEl?.text()?.trim() ?: ldJson["author.name"] ?: ""
        val authorId = authorEl?.attr("href")?.substringAfterLast("/")?.takeIf { it.toLongOrNull() != null }

        val cover = doc.selectFirst("div.cover-art-container img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: ldJson["image"]

        val pills = doc.select("div.fiction-info span.label.bg-blue-hoki").map { it.text().trim() }
        val type = pills.firstNotNullOfOrNull(::asType) ?: FictionType.ORIGINAL
        val status = pills.firstNotNullOfOrNull(::asStatus) ?: FictionStatus.UNKNOWN

        val tags = doc.select("a.fiction-tag").map { tag ->
            val slug = tag.attr("href").substringAfter("tagsAdd=").substringBefore("&")
            slug.trim() to tag.text().trim()
        }.distinctBy { it.first }

        val warnings = doc.select("div.font-red-sunglo:has(strong) ul.list-inline li")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }

        val descriptionHtml = doc.selectFirst("div.description div.hidden-content")?.html()
            ?: ldJson["description"].orEmpty()

        val ratingValue = ldJson["aggregateRating.ratingValue"]?.toDoubleOrNull()
            ?: doc.selectFirst("span.star[data-original-title=Overall Score]")
                ?.attr("data-content")?.substringBefore("/")?.trim()?.toDoubleOrNull()

        val ratingCount = ldJson["aggregateRating.ratingCount"]?.replace(",", "")?.toLongOrNull()
            ?: numericStat(doc, "Ratings")

        val totalViews = numericStat(doc, "Total Views")
        val followers = numericStat(doc, "Followers")
        val favorites = numericStat(doc, "Favorites")
        val pages = numericStat(doc, "Pages")

        val chapters = parseChapterTable(doc)

        return FictionDetails(
            sourceId = RoyalRoadIds.SOURCE_ID,
            fictionId = fictionId,
            title = title,
            authorName = authorName,
            authorId = authorId,
            coverImageUrl = cover,
            type = type,
            status = status,
            descriptionHtml = descriptionHtml,
            tags = tags.map { it.first },
            tagLabels = tags.toMap(),
            warnings = warnings,
            rating = ratingValue,
            ratingCount = ratingCount,
            totalViews = totalViews,
            followers = followers,
            favorites = favorites,
            pageCount = pages,
            chapters = chapters,
        )
    }

    private fun parseChapterTable(doc: Document): List<ChapterSummary> {
        val rows = doc.select("table#chapters tbody tr.chapter-row")
        return rows.mapIndexedNotNull { index, row ->
            val href = row.attr("data-url").ifBlank {
                row.selectFirst("td a")?.attr("href").orEmpty()
            }
            if (href.isBlank()) return@mapIndexedNotNull null
            val chapterId = extractChapterIdFromHref(href) ?: return@mapIndexedNotNull null
            val title = row.selectFirst("td a")?.text()?.trim().orEmpty()
            val time = row.selectFirst("time[unixtime]")?.attr("unixtime")?.toLongOrNull()
            val volumeIdRaw = row.attr("data-volume-id")
            val volumeId = volumeIdRaw.takeIf { it.isNotBlank() && it != "null" }
            ChapterSummary(
                chapterId = chapterId,
                title = title,
                url = absoluteUrl(href),
                postedAtEpochSec = time,
                index = index,
                volumeId = volumeId,
            )
        }
    }

    private fun numericStat(doc: Document, label: String): Long? {
        val labelEls = doc.select("li.bold.uppercase")
        val labelEl = labelEls.firstOrNull { it.text().trim().startsWith(label, ignoreCase = true) }
            ?: return null
        val valueEl = labelEl.nextElementSibling() ?: return null
        return valueEl.text().trim().replace(",", "").toLongOrNull()
    }

    private fun asStatus(label: String): FictionStatus? = when (label.uppercase()) {
        "ONGOING" -> FictionStatus.ONGOING
        "COMPLETED" -> FictionStatus.COMPLETED
        "HIATUS" -> FictionStatus.HIATUS
        "STUB" -> FictionStatus.STUB
        "DROPPED" -> FictionStatus.DROPPED
        else -> null
    }

    private fun asType(label: String): FictionType? = when (label.uppercase()) {
        "ORIGINAL" -> FictionType.ORIGINAL
        "FAN FICTION" -> FictionType.FAN_FICTION
        else -> null
    }

    private fun absoluteUrl(href: String): String =
        if (href.startsWith("http")) href else RoyalRoadIds.BASE_URL + href

    /**
     * Coarse JSON-LD reader — we only need a few flat fields, so a regex
     * approach avoids pulling in a JSON dependency. Keys we care about:
     *   name, description, image, author.name,
     *   aggregateRating.ratingValue, aggregateRating.ratingCount.
     */
    private fun readJsonLd(doc: Document): Map<String, String> {
        val script = doc.select("script[type=application/ld+json]").firstOrNull() ?: return emptyMap()
        val raw = script.data()
        val out = mutableMapOf<String, String>()
        listOf("name", "description", "image").forEach { k ->
            stringField(raw, k)?.let { out[k] = it }
        }
        stringField(raw, "name", scope = "author")?.let { out["author.name"] = it }
        numberField(raw, "ratingValue")?.let { out["aggregateRating.ratingValue"] = it }
        numberField(raw, "ratingCount")?.let { out["aggregateRating.ratingCount"] = it }
        return out
    }

    private fun stringField(raw: String, key: String, scope: String? = null): String? {
        val pattern = if (scope != null) {
            // crude: find "scope":{ ... "key":"..." ... }
            Regex("""\"$scope\"\s*:\s*\{[^}]*?\"$key\"\s*:\s*\"((?:[^\"\\]|\\.)*)\"""")
        } else {
            Regex("""\"$key\"\s*:\s*\"((?:[^\"\\]|\\.)*)\"""")
        }
        return pattern.find(raw)?.groupValues?.get(1)?.let(::unescapeJson)
    }

    private fun numberField(raw: String, key: String): String? {
        val m = Regex("""\"$key\"\s*:\s*([0-9]+(?:\.[0-9]+)?)""").find(raw)
        return m?.groupValues?.get(1)
    }

    private fun unescapeJson(s: String): String =
        s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\/", "/")
}
