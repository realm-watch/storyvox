package `in`.jphe.storyvox.source.ao3.net

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Typed view of an AO3 per-tag Atom feed
 * (`/tags/<tag>/feed.atom`). Pulls the fields storyvox needs into a
 * flat data class — title, author display, summary, work id (parsed
 * out of the entry's `id`/`link`), updated timestamp — and discards
 * everything else. Re-parses cheaply enough on each fetch that we
 * don't cache; the network round-trip dominates the cost.
 *
 * Uses Android's built-in [Xml.newPullParser] (no kotlinx.xml or
 * SAX dependency) — the feed shape is well-defined Atom and
 * pull-parsing keeps the dependency surface minimal.
 *
 * Issue #381.
 */
internal data class Ao3AtomFeed(
    val tag: String,
    val entries: List<Ao3FeedEntry>,
) {
    companion object {
        /**
         * Parse an Atom-XML string. Throws on malformed XML — the
         * caller wraps the throw into [FictionResult.NetworkError].
         */
        fun parse(xml: String): Ao3AtomFeed {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))

            var feedTitle = ""
            val entries = mutableListOf<Ao3FeedEntry>()

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "title" -> {
                            // Feed-level title appears before any
                            // <entry>. Once we're inside an entry the
                            // entry-handler consumes its own <title>.
                            if (feedTitle.isEmpty() && entries.isEmpty()) {
                                feedTitle = parser.safeText().trim()
                            }
                        }
                        "entry" -> entries += parseEntry(parser)
                    }
                }
                event = parser.next()
            }
            return Ao3AtomFeed(tag = feedTitle, entries = entries)
        }

        private fun parseEntry(parser: XmlPullParser): Ao3FeedEntry {
            var id = ""
            var title = ""
            var summary = ""
            var updated = ""
            var workUrl = ""
            val authors = mutableListOf<String>()
            val categories = mutableListOf<String>()

            // Loop until the matching </entry>. Atom spec guarantees
            // entries are non-nested, so depth tracking is unnecessary.
            while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "entry")) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "id" -> id = parser.safeText().trim()
                        "title" -> title = parser.safeText().trim()
                        "summary" -> summary = parser.safeText().trim()
                        "updated" -> updated = parser.safeText().trim()
                        "link" -> {
                            // First `<link rel="alternate" .../>`
                            // points at the work page. `rel` is
                            // sometimes absent (Atom default is
                            // "alternate"), so accept either case.
                            val rel = parser.getAttributeValue(null, "rel") ?: "alternate"
                            val href = parser.getAttributeValue(null, "href").orEmpty()
                            if (rel == "alternate" && href.isNotEmpty() && workUrl.isEmpty()) {
                                workUrl = href
                            }
                        }
                        "author" -> {
                            // Author block wraps <name>...</name>; we
                            // descend until the matching </author>.
                            while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "author")) {
                                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "name") {
                                    authors += parser.safeText().trim()
                                }
                                parser.next()
                            }
                        }
                        "category" -> {
                            // AO3 emits one <category term="..."/> per
                            // fandom / character / freeform tag. Self-
                            // closing, so we grab the attr and move on.
                            val term = parser.getAttributeValue(null, "term").orEmpty()
                            if (term.isNotEmpty()) categories += term
                        }
                    }
                }
                parser.next()
            }

            val workId = workIdFromUrl(workUrl) ?: workIdFromUrn(id) ?: 0L
            return Ao3FeedEntry(
                workId = workId,
                title = title,
                authorDisplay = authors.joinToString(", "),
                summary = summary.ifBlank { null },
                tags = categories,
                updated = updated.ifBlank { null },
                workUrl = workUrl.ifBlank { null },
            )
        }

        /**
         * Pull the AO3 work id out of a `/works/<id>` URL. Returns
         * null on a malformed or non-`/works/` URL (e.g. tag-level
         * links, which we don't surface as entries anyway).
         */
        private fun workIdFromUrl(url: String): Long? {
            if (url.isBlank()) return null
            // Strip the host if present; the path is what we care about.
            val path = url.substringAfter("archiveofourown.org", url)
            val tail = path.substringAfter("/works/", missingDelimiterValue = "")
            if (tail.isEmpty()) return null
            // Trim any trailing path segment ("/chapters/...") or query.
            val idStr = tail.substringBefore('/').substringBefore('?')
            return idStr.toLongOrNull()
        }

        /**
         * Atom `<id>` is typically `tag:archiveofourown.org,...:Work/<id>`.
         * Fall back here when the `<link>` block was missing.
         */
        private fun workIdFromUrn(urn: String): Long? {
            val tail = urn.substringAfterLast("Work/", missingDelimiterValue = "")
            return tail.toLongOrNull()
        }

        /** Safe text extraction — `parser.nextText()` advances past
         *  the END_TAG, so the outer loop's `parser.next()` would skip
         *  over the following sibling. We capture the text and let the
         *  outer loop handle the cursor advance. Returns "" when the
         *  current token isn't a START_TAG (defensive). */
        private fun XmlPullParser.safeText(): String {
            if (eventType != XmlPullParser.START_TAG) return ""
            val depth = depth
            val sb = StringBuilder()
            var ev = next()
            while (!(ev == XmlPullParser.END_TAG && this.depth == depth)) {
                if (ev == XmlPullParser.TEXT) sb.append(text)
                if (ev == XmlPullParser.END_DOCUMENT) break
                ev = next()
            }
            return sb.toString()
        }
    }
}

/**
 * One entry from a per-tag feed. AO3 includes the summary inline
 * (often HTML-wrapped — `<p>` and `<br>`); we keep it as-is and let
 * the downstream UI render it. Author display joins multiple authors
 * with `", "`, mirroring [GutendexBook][in.jphe.storyvox.source.gutenberg.net.GutendexBook].
 */
internal data class Ao3FeedEntry(
    val workId: Long,
    val title: String,
    val authorDisplay: String,
    val summary: String?,
    val tags: List<String>,
    val updated: String?,
    val workUrl: String?,
)
