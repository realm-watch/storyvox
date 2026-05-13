package `in`.jphe.storyvox.source.epub.writer

import `in`.jphe.storyvox.data.source.SourceIds

/**
 * Maps a `sourceId` to a human-readable label for the EPUB title page's
 * "From {sourceName}" line. Kept here (not on `SourceIds`) so the writer
 * module doesn't have to take a dep on every per-source library just to
 * read their display string.
 *
 * Unknown sourceId falls back to the id itself so the title page is
 * never blank — useful breadcrumb when a new source ships without
 * updating this map.
 */
internal object SourceLabels {
    fun displayName(sourceId: String): String = when (sourceId) {
        SourceIds.ROYAL_ROAD -> "Royal Road"
        SourceIds.GITHUB -> "GitHub"
        SourceIds.MEMPALACE -> "the Memory Palace"
        SourceIds.RSS -> "an RSS feed"
        SourceIds.EPUB -> "a local EPUB"
        SourceIds.OUTLINE -> "Outline"
        else -> sourceId
    }

    /**
     * Best-effort: return a public URL the user can click on the title
     * page to reach the fiction at its source.
     *
     * The fictionId scheme varies per source — for Royal Road it's the
     * numeric id, for RSS it's the feed URL itself, for GitHub it's
     * `github:owner/repo`. We resolve only the cases we can do without
     * leaking source-specific knowledge here: RSS already carries the URL
     * verbatim in the id (after the `rss:` prefix); Royal Road needs the
     * numeric id and a known prefix. Everything else returns null and
     * the title page hides the link block.
     */
    fun sourceUrl(sourceId: String, fictionId: String): String? = when (sourceId) {
        SourceIds.ROYAL_ROAD -> {
            // Royal Road fiction ids are stored as `royalroad:<n>` since
            // multi-source-routing landed; before that they were bare
            // numbers. Try both.
            val numeric = fictionId.removePrefix("royalroad:").toLongOrNull()
            numeric?.let { "https://www.royalroad.com/fiction/$it" }
        }
        SourceIds.GITHUB -> {
            // GitHub fiction ids are `github:owner/repo` or
            // `github:gist:<id>`. Skip gists for the URL block; they
            // don't have a "fiction homepage" the way repos do.
            val tail = fictionId.removePrefix("github:")
            if (tail.startsWith("gist:")) "https://gist.github.com/${tail.removePrefix("gist:")}"
            else "https://github.com/$tail"
        }
        SourceIds.RSS -> {
            // RSS fiction ids are `rss:<feedUrl>` per RssSource.
            val tail = fictionId.removePrefix("rss:")
            if (tail.startsWith("http")) tail else null
        }
        SourceIds.OUTLINE, SourceIds.MEMPALACE, SourceIds.EPUB -> null
        else -> null
    }
}
