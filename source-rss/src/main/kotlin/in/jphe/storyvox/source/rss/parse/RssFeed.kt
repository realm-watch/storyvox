package `in`.jphe.storyvox.source.rss.parse

/**
 * Issue #236 — minimal feed model that's a structural superset of RSS
 * 2.0 + Atom 1.0. Anything that doesn't map cleanly to both stays
 * as nullable so the parser can surface the data when it's there
 * without forcing every emitter to fill the field. The TTS pipeline
 * only really needs (title, description, items: [(title, body, pubdate)]).
 */
data class RssFeed(
    val title: String,
    val link: String?,
    val description: String?,
    val author: String?,
    val items: List<RssItem>,
)

data class RssItem(
    /** Stable per-feed identifier — `<guid>` (RSS) or `<id>` (Atom)
     *  if present, else falling back to `<link>` or a hash of the
     *  title. Used as the chapter id; persistence keys on it. */
    val id: String,
    val title: String,
    /** Sanitized HTML body — `<content:encoded>` (RSS) preferred,
     *  else `<description>` (RSS) / `<content>` (Atom) / `<summary>`. */
    val htmlBody: String,
    val link: String?,
    val author: String?,
    /** Epoch milliseconds when the item was published. Null if the
     *  feed didn't carry a parseable date. */
    val publishedAtEpochMs: Long?,
)
