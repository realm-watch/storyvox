package `in`.jphe.storyvox.source.epub.writer

/**
 * Inputs the [EpubWriter] needs to serialize a single fiction to a valid
 * EPUB 3 file. Pure data; no dependencies on Room / Android. Built by
 * [ExportFictionToEpubUseCase] from DB rows.
 *
 * Issue #117 — JP's decision was to include full content + metadata:
 * cover image (best-effort), title page with author/source/dates/tags,
 * chapter XHTML files, and per-chapter author notes.
 */
data class EpubBook(
    /** Stable identifier — used as `dc:identifier` in the OPF and as the
     *  `book-id` unique-identifier name. We use `urn:storyvox:<fictionId>`
     *  so re-exports of the same fiction land on the same logical book in
     *  a reader's library (useful when the user re-exports after new
     *  chapters arrive). */
    val identifier: String,
    val title: String,
    val author: String,
    /** ISO 639-1 (e.g. `"en"`). Default `"en"` — storyvox sources today are
     *  all English-heavy and EPUB 3 requires *some* language tag. */
    val language: String = "en",
    /** Cover image as raw bytes + extension, or null if no cover. The
     *  writer embeds at `images/cover.{ext}` and tags it `properties="cover-image"`
     *  in the OPF manifest per EPUB 3 spec. */
    val cover: CoverImage? = null,
    /** Title-page metadata — author, source URL, date range, tags, description.
     *  Rendered as the first XHTML in the spine. */
    val titlePageMetadata: TitlePageMetadata,
    /** Chapters in spine order. */
    val chapters: List<EpubChapter>,
)

data class CoverImage(
    val bytes: ByteArray,
    /** "jpg" / "png" / "gif" / "webp". Used both for the filename extension
     *  inside the zip and the media-type declaration in the OPF. Defaults
     *  to "jpg" because every realm we ship (Royal Road, RSS, MemPalace)
     *  serves JPEG covers; pass a real extension if you have one. */
    val extension: String = "jpg",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CoverImage) return false
        return extension == other.extension && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * extension.hashCode() + bytes.contentHashCode()
}

/**
 * What we render on the synthetic title page (chapter 0 of the spine).
 * All fields except [title] / [author] are optional; the writer skips
 * the corresponding block when they're blank/null.
 */
data class TitlePageMetadata(
    val title: String,
    val author: String,
    /** Where the fiction originated, e.g. `"Royal Road"`, `"GitHub"`, `"RSS feed"`.
     *  Used for the "From {sourceName}" line on the title page. */
    val sourceName: String,
    /** Direct URL to the source fiction (RR fiction page, GitHub repo, RSS feed URL).
     *  Rendered as an `<a>` link below the source name. Null hides the link block. */
    val sourceUrl: String? = null,
    /** Earliest chapter publishedAt (ms epoch). Null hides the date range. */
    val publishedFromMs: Long? = null,
    val publishedToMs: Long? = null,
    /** Fiction-level tags (`Fiction.tags`). Rendered as a chip row at the
     *  bottom of the title page. */
    val tags: List<String> = emptyList(),
    /** `Fiction.description`. Rendered as the synopsis paragraph block. */
    val description: String? = null,
)

data class EpubChapter(
    /** Stable id for the manifest item (must be valid as an XML id —
     *  letters, digits, `-`, `_`, `.`; first char must be a letter).
     *  [EpubWriter] sanitizes whatever you pass to fit that constraint. */
    val id: String,
    val title: String,
    /** Sanitized HTML body. The writer wraps this in a `<section>` with
     *  a brass-accented serif body style; if [htmlBody] is blank it
     *  inserts a "(chapter body not downloaded yet)" placeholder so the
     *  EPUB still parses and the user sees a visible breadcrumb.
     *
     *  IMPORTANT: This must be safe XHTML (no `<br>` self-close-by-omission,
     *  no `<hr>` open tag). Storyvox sources already sanitize via Jsoup;
     *  the writer additionally runs a pass to close void elements before
     *  embedding so any sloppy source HTML doesn't break readers. */
    val htmlBody: String,
    /** Author's note (Royal Road populates this from the chapter-page sidebar).
     *  Null = no note. Rendered after the body, under an "Author's note"
     *  heading with a horizontal rule above. */
    val authorNote: String? = null,
    /** Whether the author note belongs before or after the main body. */
    val authorNotePosition: AuthorNotePosition = AuthorNotePosition.AFTER,
)

enum class AuthorNotePosition { BEFORE, AFTER }
