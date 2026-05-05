package `in`.jphe.storyvox.source.royalroad.parser

import `in`.jphe.storyvox.core.data.source.ChapterContent
import `in`.jphe.storyvox.core.data.source.ChapterParseResult
import `in`.jphe.storyvox.source.royalroad.model.RoyalRoadIds
import `in`.jphe.storyvox.source.royalroad.model.extractChapterIdFromHref
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

internal object ChapterParser {

    private val PATREON_RE = Regex(
        """(?:patreon|early\s*access|subscribe\s*to\s*read|chapter\s+is\s+unavailable|exclusive)""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(html: String, fictionId: String, chapterId: String): ChapterParseResult {
        val doc = Jsoup.parse(html, RoyalRoadIds.BASE_URL)
        HoneypotFilter.strip(doc)

        val content = doc.selectFirst("div.chapter-inner.chapter-content")
        val isAuthRequired = content == null || content.select("p, img").isEmpty()

        if (isAuthRequired && looksLikePatreonGate(doc)) {
            return ChapterParseResult.AuthRequired("Patreon-locked: sign in or subscribe to read")
        }
        if (content == null) {
            return ChapterParseResult.Failure("chapter-content block missing")
        }

        val title = doc.selectFirst("div.fic-header h1.font-white.break-word")?.text()?.trim().orEmpty()
        val authorNotes = doc.select("div.author-note-portlet div.portlet-body.author-note").map { it.html() }
        val nextHref = doc.select("div.row.nav-buttons a.btn-primary").lastOrNull()?.attr("href")?.takeIf { it.isNotBlank() }
        val prevHref = doc.select("div.row.nav-buttons a.btn-primary").firstOrNull()?.let { firstAnchor ->
            val all = doc.select("div.row.nav-buttons a.btn-primary")
            if (all.size >= 2) all.first()?.attr("href") else null
        }

        val nextChapterId = nextHref?.let(::extractChapterIdFromHref)
        val prevChapterId = prevHref?.let(::extractChapterIdFromHref)?.takeIf { it != chapterId }

        val plainText = extractPlainText(content)

        return ChapterParseResult.Ok(
            ChapterContent(
                sourceId = RoyalRoadIds.SOURCE_ID,
                fictionId = fictionId,
                chapterId = chapterId,
                title = title,
                contentHtml = content.html(),
                contentPlainText = plainText,
                authorNotesHtml = authorNotes,
                prevChapterId = prevChapterId,
                nextChapterId = nextChapterId,
            )
        )
    }

    private fun looksLikePatreonGate(doc: Document): Boolean {
        val text = doc.body()?.text().orEmpty()
        if (PATREON_RE.containsMatchIn(text).not()) return false
        val patreonLink = doc.select("a[href^=https://www.patreon.com/]").isNotEmpty()
        return patreonLink || text.contains("subscribe", ignoreCase = true)
    }

    /**
     * TTS-friendly plaintext extraction:
     *   - strip <img>, <hr>, <iframe>
     *   - convert <br> to newline
     *   - join paragraph text with double-newlines
     *   - collapse internal whitespace
     */
    private fun extractPlainText(content: org.jsoup.nodes.Element): String {
        content.select("img, hr, iframe, script, style").remove()
        content.select("br").forEach { it.replaceWith(org.jsoup.nodes.TextNode("\n")) }
        val paragraphs = content.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
        return if (paragraphs.isNotEmpty()) {
            paragraphs.joinToString("\n\n")
        } else {
            content.text().trim()
        }
    }
}
