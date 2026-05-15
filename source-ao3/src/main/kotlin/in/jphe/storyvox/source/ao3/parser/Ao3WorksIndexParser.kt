package `in`.jphe.storyvox.source.ao3.parser

import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Jsoup parser for AO3's authed "works index" pages (PR2 of #426).
 *
 * Three AO3 pages share the same HTML shape:
 *
 *  - `/users/<u>/subscriptions` (work subscriptions — the user's
 *    list of works they've subscribed to for update notifications)
 *  - `/users/<u>/readings?show=marked` (Marked for Later — works
 *    the user tagged for "I'll read this later")
 *  - `/users/<u>/readings` (full history; not surfaced in v1)
 *
 * AO3 renders all three as:
 *
 * ```html
 * <ol class="work index group">
 *   <li class="work blurb group" id="work_12345" role="article">
 *     <h4 class="heading">
 *       <a href="/works/12345">Title</a>
 *       by <a rel="author" href="/users/author">Author</a>
 *     </h4>
 *     <blockquote class="userstuff summary">Summary HTML</blockquote>
 *     <ul class="tags commas"><li class="freeforms"><a>Tag</a></li>…</ul>
 *     <dl class="stats">…</dl>
 *   </li>
 *   …
 *   <li class="work blurb group">…</li>
 * </ol>
 * ```
 *
 * Plus a pagination block at the bottom (`<ol class="pagination"…>`)
 * with a "Next →" link when more pages exist. The parser surfaces
 * each `<li class="work blurb">` as a [FictionSummary] keyed by the
 * `ao3:<workId>` id the rest of the AO3 source uses.
 *
 * AO3 does not require sign-in to *render* these pages — they just
 * 302 to the login form when the requester isn't authed. The
 * authed [Ao3Api][in.jphe.storyvox.source.ao3.net.Ao3Api] surfaces
 * pre-check the cookie state; this parser only runs on bodies that
 * already came back 200.
 *
 * Tolerant of structural variation: AO3 occasionally renders an
 * empty state (`<div class="notice"…>You have no…</div>`) — the
 * parser returns an empty [ListPage] in that case rather than
 * throwing. Defensive on every selector — Jsoup queries that miss
 * return null and the parser skips the row.
 */
internal object Ao3WorksIndexParser {

    /**
     * Parse one page of an AO3 works index. [page] is echoed into
     * the returned [ListPage]; [hasNext] is computed from the
     * presence of a "Next" link in the pagination block.
     *
     * Throws nothing — malformed cards are silently dropped, an
     * empty body returns an empty list. Callers wrap the call in
     * the standard `FictionResult.Failure` envelope at the HTTP
     * layer if the body itself failed to fetch.
     */
    fun parse(html: String, page: Int = 1): ListPage<FictionSummary> {
        val doc: Document = Jsoup.parse(html, BASE_URL)
        // Some AO3 surfaces wrap the work list under #main; others
        // under #main #user-subscriptions. Select the broadest
        // possible `<li class="work blurb">` query so all three
        // page shapes (subscriptions, readings, readings?show=marked)
        // resolve through the same selector.
        val cards: List<Element> = doc.select("li.work.blurb, li.bookmark.blurb")
            .filterNot { it.id().isBlank() && it.selectFirst("h4.heading a") == null }
        val items = cards.mapNotNull(::parseCard)
        val hasNext = doc.select("ol.pagination a, ol.pagination .next a").any { a ->
            // AO3's pagination uses literal text "Next" + a chevron;
            // some skins use the rel="next" attribute. Accept either.
            a.text().contains("Next", ignoreCase = true) || a.attr("rel").contains("next")
        }
        return ListPage(items = items, page = page, hasNext = hasNext)
    }

    private fun parseCard(card: Element): FictionSummary? {
        // The work id is encoded in the `<li id="work_12345">` attribute.
        // Bookmarks variant uses `bookmark_<id>` which references the
        // bookmark row, not the work — for that shape we fall back to
        // the title anchor's href.
        val workId = card.id()
            .removePrefix("work_")
            .takeIf { it.isNotBlank() && it.toLongOrNull() != null }
            ?.let { "ao3:$it" }
            ?: extractWorkIdFromTitleAnchor(card)
            ?: return null

        val titleAnchor = card.selectFirst("h4.heading a[href^=/works/]") ?: return null
        val title = titleAnchor.text().trim().ifEmpty { return null }

        // AO3 renders multiple author anchors when a work has co-authors;
        // join them with ", " mirroring the Atom feed shape.
        val authors = card.select("h4.heading a[rel=author]").map { it.text().trim() }
            .filter { it.isNotEmpty() }
        val authorDisplay = authors.joinToString(", ").ifEmpty { "" }

        val summary = card.selectFirst("blockquote.userstuff.summary")?.text()?.trim()
            ?.ifEmpty { null }

        // AO3 tag taxonomy: warning / category / fandom / relationship /
        // character / freeform. Flatten the freeform + fandom tags only
        // for the v1 browse-card surface; the rest are AO3-specific
        // metadata that the storyvox UI doesn't render as chips.
        val tags = card.select("ul.tags li.freeforms a.tag, ul.tags li.fandoms a.tag")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }

        // AO3 marks completed works with `<dd class="status">Complete</dd>`
        // (or "Yes" in the words-and-chapters stats block). Conservative
        // default Ongoing — wrong "Completed" is more confusing than
        // wrong "Ongoing" (the UI's "may update" affordance is benign).
        val status = if (card.selectFirst("dd.status, .status .complete-yes") != null) {
            FictionStatus.COMPLETED
        } else {
            FictionStatus.ONGOING
        }

        // Chapter count surfaces as e.g. `<dd class="chapters">3/?</dd>`
        // or `5/5`. Pull the numerator out; null when AO3 hasn't filled
        // it in (rare — single-chapter works render as `1/1`).
        val chapterCount = card.selectFirst("dd.chapters")?.text()
            ?.substringBefore('/')
            ?.trim()
            ?.toIntOrNull()

        return FictionSummary(
            id = workId,
            sourceId = SourceIds.AO3,
            title = title,
            author = authorDisplay,
            coverUrl = null, // AO3 has no per-work cover image API.
            description = summary,
            tags = tags,
            status = status,
            chapterCount = chapterCount,
        )
    }

    private fun extractWorkIdFromTitleAnchor(card: Element): String? {
        val href = card.selectFirst("h4.heading a[href^=/works/]")?.attr("href")
            ?: return null
        val tail = href.substringAfter("/works/", missingDelimiterValue = "")
        if (tail.isEmpty()) return null
        val id = tail.substringBefore('/').substringBefore('?')
        return id.toLongOrNull()?.let { "ao3:$it" }
    }

    private const val BASE_URL = "https://archiveofourown.org"
}
