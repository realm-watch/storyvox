package `in`.jphe.storyvox.source.rss.parse

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Issue #236 — RSS 2.0 / Atom 1.0 parser using Android's bundled
 * XmlPullParser. Picks up the same shape regardless of dialect:
 *
 *  - RSS: `<rss><channel><title/><item><title/><pubDate/><guid/>
 *    <description/><content:encoded/></item></channel></rss>`
 *  - Atom: `<feed><title/><entry><title/><published/><id/>
 *    <summary/><content/></entry></feed>`
 *
 * Why hand-rolled: the JVM RSS libs (Rome) pull in a chain of XML
 * deps that don't play nice with Android's stripped-down Xerces, and
 * the storyvox use case is read-only feed parsing — no need for
 * round-trip serialization, no Atom protocol, no RSS extensions
 * beyond `content:encoded`. ~150 LOC is cheaper than a 1.5 MB
 * dependency.
 *
 * Date parsing: tries RFC 822 (RSS standard), ISO 8601 (Atom
 * standard), and a few common WordPress / Substack variants in order.
 * Returns null on failure rather than throwing — the caller renders
 * "no date" gracefully.
 */
object RssParser {

    /**
     * Parses an XML string into a [RssFeed]. Throws [ParseException]
     * (RuntimeException subclass) if the document is missing required
     * elements (channel/feed title, items/entries with titles).
     */
    fun parse(xml: String): RssFeed {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))
        parser.nextTag()
        return when (parser.name) {
            "rss" -> parseRss(parser)
            "feed" -> parseAtom(parser)
            else -> throw ParseException("Not an RSS or Atom feed (root=${parser.name})", 0)
        }
    }

    // ── RSS 2.0 ──────────────────────────────────────────────────

    private fun parseRss(parser: XmlPullParser): RssFeed {
        // <rss><channel> ... </channel></rss>
        parser.require(XmlPullParser.START_TAG, null, "rss")
        var feedTitle = ""
        var feedLink: String? = null
        var feedDescription: String? = null
        var feedAuthor: String? = null
        val items = mutableListOf<RssItem>()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.name == "channel") {
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.END_TAG && parser.name == "channel") break
                    if (parser.eventType != XmlPullParser.START_TAG) continue
                    when (parser.name) {
                        "title" -> feedTitle = readText(parser)
                        "link" -> feedLink = readText(parser)
                        "description" -> feedDescription = readText(parser)
                        "managingEditor", "creator" -> feedAuthor = readText(parser)
                        "item" -> items += parseRssItem(parser)
                        else -> skip(parser)
                    }
                }
            } else {
                skip(parser)
            }
        }
        if (feedTitle.isBlank()) throw ParseException("RSS feed has no <channel><title>", 0)
        return RssFeed(
            title = feedTitle.trim(),
            link = feedLink?.trim(),
            description = feedDescription?.trim(),
            author = feedAuthor?.trim(),
            items = items,
        )
    }

    private fun parseRssItem(parser: XmlPullParser): RssItem {
        var title = ""
        var description: String? = null
        var contentEncoded: String? = null
        var link: String? = null
        var guid: String? = null
        var author: String? = null
        var pubDate: String? = null

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.name == "item") break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "title" -> title = readText(parser)
                "description" -> description = readText(parser)
                // <content:encoded> — the WordPress / RSS-2.0 convention
                // for the full body. Preferred over <description> when
                // both exist; description is often a teaser.
                "encoded" -> contentEncoded = readText(parser)
                "link" -> link = readText(parser)
                "guid" -> guid = readText(parser)
                "creator", "author" -> author = readText(parser)
                "pubDate" -> pubDate = readText(parser)
                else -> skip(parser)
            }
        }

        val body = (contentEncoded ?: description ?: "").trim()
        val id = guid?.takeIf { it.isNotBlank() }
            ?: link?.takeIf { it.isNotBlank() }
            ?: ("rss:" + (title.hashCode().toString(16)))
        return RssItem(
            id = id,
            title = title.trim(),
            htmlBody = body,
            link = link?.trim(),
            author = author?.trim(),
            publishedAtEpochMs = pubDate?.let(::parseDate),
        )
    }

    // ── Atom 1.0 ─────────────────────────────────────────────────

    private fun parseAtom(parser: XmlPullParser): RssFeed {
        parser.require(XmlPullParser.START_TAG, null, "feed")
        var feedTitle = ""
        var feedLink: String? = null
        var feedAuthor: String? = null
        val items = mutableListOf<RssItem>()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.name == "feed") break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "title" -> feedTitle = readText(parser)
                "link" -> {
                    // Atom uses <link href="..." rel="alternate"/>
                    val href = parser.getAttributeValue(null, "href")
                    if (!href.isNullOrBlank()) feedLink = href
                    skip(parser) // close the self-closing tag if any
                }
                "author" -> feedAuthor = parseAtomPerson(parser)
                "entry" -> items += parseAtomEntry(parser)
                else -> skip(parser)
            }
        }
        if (feedTitle.isBlank()) throw ParseException("Atom feed has no <feed><title>", 0)
        return RssFeed(
            title = feedTitle.trim(),
            link = feedLink?.trim(),
            description = null,
            author = feedAuthor?.trim(),
            items = items,
        )
    }

    private fun parseAtomEntry(parser: XmlPullParser): RssItem {
        var title = ""
        var summary: String? = null
        var content: String? = null
        var link: String? = null
        var id: String? = null
        var author: String? = null
        var published: String? = null
        var updated: String? = null

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.name == "entry") break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "title" -> title = readText(parser)
                "summary" -> summary = readText(parser)
                "content" -> content = readText(parser)
                "link" -> {
                    val href = parser.getAttributeValue(null, "href")
                    if (!href.isNullOrBlank()) link = href
                    skip(parser)
                }
                "id" -> id = readText(parser)
                "author" -> author = parseAtomPerson(parser)
                "published" -> published = readText(parser)
                "updated" -> updated = readText(parser)
                else -> skip(parser)
            }
        }

        val body = (content ?: summary ?: "").trim()
        val resolvedId = id?.takeIf { it.isNotBlank() }
            ?: link?.takeIf { it.isNotBlank() }
            ?: ("atom:" + (title.hashCode().toString(16)))
        // Prefer <published> (immutable); fall back to <updated>.
        val date = (published ?: updated)?.let(::parseDate)
        return RssItem(
            id = resolvedId,
            title = title.trim(),
            htmlBody = body,
            link = link?.trim(),
            author = author?.trim(),
            publishedAtEpochMs = date,
        )
    }

    private fun parseAtomPerson(parser: XmlPullParser): String? {
        // <author><name>X</name>...</author> in Atom; some feeds collapse to text content.
        var name: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG &&
                (parser.name == "author" || parser.name == "managingEditor")) break
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "name") {
                name = readText(parser)
            } else if (parser.eventType == XmlPullParser.START_TAG) {
                skip(parser)
            }
        }
        return name?.trim()
    }

    // ── helpers ──────────────────────────────────────────────────

    private fun readText(parser: XmlPullParser): String {
        var result = ""
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.text ?: ""
            parser.nextTag()
        }
        return result
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
            }
        }
    }

    private val dateFormats = listOf(
        // RFC 822 — RSS standard
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd MMM yyyy HH:mm:ss Z",
        // Truncated variants seen in the wild
        "dd MMM yyyy HH:mm:ss zzz",
        // ISO 8601 — Atom standard
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
    )

    private fun parseDate(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        for (pattern in dateFormats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.ENGLISH)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(trimmed)?.time
            } catch (_: ParseException) {
                continue
            }
        }
        return null
    }
}

class ParseException(message: String, val errorOffset: Int) : RuntimeException(message)
