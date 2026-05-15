package `in`.jphe.storyvox.source.readability.extract

import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #472 — thin wrapper around the Readability4J library that
 * produces the storyvox-shaped extraction result. Pure JVM; no
 * Android dependencies so the unit tests run on a plain JUnit runner.
 *
 * Returns `null` when Readability can't extract anything readable —
 * pages that aren't article-shaped (search results, app shells with
 * client-side rendering, login walls) typically fail at this layer.
 * The caller surfaces a clear error to the user; the extraction step
 * itself never throws.
 */
@Singleton
class ReadabilityExtractor @Inject constructor() {

    /**
     * Run Readability over [html] (the page at [url]) and return a
     * structured result, or null when extraction fails. Stateless;
     * safe to call concurrently.
     */
    fun extract(url: String, html: String): Extracted? {
        val readability = Readability4J(url, html)
        val article = runCatching { readability.parse() }.getOrNull() ?: return null

        val articleHtml = article.contentWithUtf8Encoding?.takeIf { it.isNotBlank() }
            ?: article.content?.takeIf { it.isNotBlank() }
            ?: return null

        // Pull a plain-text rendering off the extracted DOM rather than the
        // raw page source — Readability strips boilerplate (nav, ads,
        // share-this rails), and the TTS pipeline cares about the cleaned
        // body, not the original HTML.
        val plainText = Jsoup.parse(articleHtml).text().trim()
        if (plainText.isBlank()) return null

        val title = article.title?.trim().orEmpty()
            .ifBlank { fallbackTitle(html) }
            .ifBlank { "Untitled article" }

        val byline = article.byline?.trim().orEmpty()
        val excerpt = article.excerpt?.trim()?.takeIf { it.isNotBlank() }

        val wordCount = plainText
            .split(WHITESPACE)
            .count { it.isNotBlank() }
            .takeIf { it > 0 }

        return Extracted(
            title = title,
            byline = byline,
            contentHtml = articleHtml,
            contentText = plainText,
            excerpt = excerpt,
            wordCount = wordCount,
        )
    }

    /** Pull a `<title>` from the raw HTML when Readability didn't
     *  surface one — happens occasionally on minimal pages. */
    private fun fallbackTitle(html: String): String {
        val match = TITLE_TAG.find(html) ?: return ""
        return match.groupValues[1].trim()
    }

    /** Storyvox-shaped extraction result. */
    data class Extracted(
        val title: String,
        val byline: String,
        val contentHtml: String,
        val contentText: String,
        val excerpt: String?,
        val wordCount: Int?,
    )

    private companion object {
        private val WHITESPACE = Regex("\\s+")
        private val TITLE_TAG = Regex(
            "<title[^>]*>(.*?)</title>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    }
}
