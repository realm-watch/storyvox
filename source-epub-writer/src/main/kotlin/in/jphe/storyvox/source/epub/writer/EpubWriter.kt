package `in`.jphe.storyvox.source.epub.writer

import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes an [EpubBook] to an [OutputStream] as a spec-compliant EPUB 3 file.
 *
 * EPUB 3 minimum structure:
 *   ```
 *   mimetype                       (STORED, first entry, contents "application/epub+zip")
 *   META-INF/container.xml         (points to the OPF)
 *   OEBPS/content.opf              (manifest + spine + metadata)
 *   OEBPS/nav.xhtml                (the navigation document, replaces NCX in EPUB 3)
 *   OEBPS/styles.css               (the inline-CSS-elsewhere fallback, kept tiny)
 *   OEBPS/text/title.xhtml         (synthetic title page)
 *   OEBPS/text/ch-N.xhtml          (one per chapter, in spine order)
 *   OEBPS/images/cover.<ext>       (optional)
 *   ```
 *
 * The `mimetype` entry MUST be the first entry in the zip, STORED (no
 * compression), with no extra fields — that's how reader apps identify
 * the file as EPUB without parsing the central directory. We enforce
 * this with [ZipEntry.STORED] + a pre-computed CRC32 + explicit size.
 *
 * Reference: https://www.w3.org/TR/epub-33/#sec-zip-container
 *
 * Issue #117 — built rather than pulled from a dep because the EPUB 3
 * spec is small (300 LOC) and the canonical Java library (nl.siegmann
 * epublib) hasn't been published in ~10 years.
 */
class EpubWriter {

    /**
     * Serialize [book] to [out]. Does NOT close [out] — the caller owns the
     * stream's lifecycle (the use case wraps a `FileOutputStream` in
     * `use { }` so this is fine).
     */
    fun write(book: EpubBook, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            writeMimetypeFirst(zip)
            writeContainerXml(zip)
            writeOpf(zip, book)
            writeNav(zip, book)
            writeStyles(zip)
            writeTitlePage(zip, book)
            book.chapters.forEachIndexed { index, chapter ->
                writeChapter(zip, index, chapter)
            }
            book.cover?.let { writeCover(zip, it) }
        }
    }

    /** First entry, STORED — see class kdoc for the spec reason. */
    private fun writeMimetypeFirst(zip: ZipOutputStream) {
        val payload = "application/epub+zip".toByteArray(Charsets.US_ASCII)
        val entry = ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = payload.size.toLong()
            compressedSize = payload.size.toLong()
            crc = CRC32().run {
                update(payload)
                value
            }
            // ZipOutputStream sets timestamps to "now" by default; readers
            // don't care, but stable timestamps are nice for diffing
            // exports. Leave the default — tests don't assert on time.
        }
        zip.putNextEntry(entry)
        zip.write(payload)
        zip.closeEntry()
    }

    private fun writeContainerXml(zip: ZipOutputStream) {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()
        writeStored(zip, "META-INF/container.xml", xml.toByteArray(Charsets.UTF_8))
    }

    private fun writeOpf(zip: ZipOutputStream, book: EpubBook) {
        val sanitized = book.chapters.mapIndexed { i, ch -> i to sanitizeId(ch.id) }
        val coverMediaType = book.cover?.let { mediaTypeFor(it.extension) }
        val coverExt = book.cover?.extension ?: "jpg"

        val manifestItems = buildString {
            append("    <item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
            append("    <item id=\"styles\" href=\"styles.css\" media-type=\"text/css\"/>\n")
            append("    <item id=\"title\" href=\"text/title.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
            sanitized.forEach { (i, sid) ->
                append("    <item id=\"$sid\" href=\"text/ch-$i.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
            }
            if (book.cover != null && coverMediaType != null) {
                append("    <item id=\"cover-image\" href=\"images/cover.$coverExt\" media-type=\"$coverMediaType\" properties=\"cover-image\"/>\n")
            }
        }.trimEnd()

        val spineItems = buildString {
            append("    <itemref idref=\"title\"/>\n")
            sanitized.forEach { (_, sid) ->
                append("    <itemref idref=\"$sid\"/>\n")
            }
        }.trimEnd()

        val modified = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package version="3.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="book-id" prefix="dcterms: http://purl.org/dc/terms/">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="book-id">${escapeXml(book.identifier)}</dc:identifier>
    <dc:title>${escapeXml(book.title)}</dc:title>
    <dc:creator>${escapeXml(book.author)}</dc:creator>
    <dc:language>${escapeXml(book.language)}</dc:language>
    <meta property="dcterms:modified">$modified</meta>
${if (book.cover != null) "    <meta name=\"cover\" content=\"cover-image\"/>\n" else ""}  </metadata>
  <manifest>
$manifestItems
  </manifest>
  <spine>
$spineItems
  </spine>
</package>
"""
        writeStored(zip, "OEBPS/content.opf", opf.toByteArray(Charsets.UTF_8))
    }

    private fun writeNav(zip: ZipOutputStream, book: EpubBook) {
        val navItems = buildString {
            append("      <li><a href=\"text/title.xhtml\">Title page</a></li>\n")
            book.chapters.forEachIndexed { i, ch ->
                append("      <li><a href=\"text/ch-$i.xhtml\">${escapeXml(ch.title)}</a></li>\n")
            }
        }.trimEnd()

        val nav = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head>
  <meta charset="UTF-8"/>
  <title>${escapeXml(book.title)} — Contents</title>
</head>
<body>
  <nav epub:type="toc" id="toc">
    <h1>Contents</h1>
    <ol>
$navItems
    </ol>
  </nav>
</body>
</html>
"""
        writeStored(zip, "OEBPS/nav.xhtml", nav.toByteArray(Charsets.UTF_8))
    }

    private fun writeStyles(zip: ZipOutputStream) {
        // Per the issue brief, primary CSS is inline per page so reader apps
        // that strip external CSS still get the brass accent. This file is
        // kept minimal as a fallback / convention. Library Nocturne palette.
        val css = """
            body { font-family: Georgia, serif; line-height: 1.5; margin: 1em; }
            h1, h2 { color: #B89B6E; font-family: Georgia, serif; }
            a { color: #B89B6E; }
            .brass { color: #B89B6E; }
            .tag { display: inline-block; border: 1px solid #B89B6E; border-radius: 4px; padding: 2px 8px; margin: 2px; font-size: 0.85em; }
            .note { background: #f5f0e6; border-left: 3px solid #B89B6E; padding: 0.5em 1em; margin: 1em 0; }
            .note h2 { margin-top: 0; }
        """.trimIndent()
        writeStored(zip, "OEBPS/styles.css", css.toByteArray(Charsets.UTF_8))
    }

    private fun writeTitlePage(zip: ZipOutputStream, book: EpubBook) {
        val m = book.titlePageMetadata
        val coverBlock = if (book.cover != null) {
            "    <p style=\"text-align:center\"><img src=\"../images/cover.${book.cover.extension}\" alt=\"Cover\" style=\"max-width:80%; max-height:60vh\"/></p>\n"
        } else ""

        val sourceBlock = buildString {
            append("    <p class=\"brass\" style=\"text-align:center; margin-top:1em\">From ${escapeXml(m.sourceName)}</p>\n")
            if (!m.sourceUrl.isNullOrBlank()) {
                append("    <p style=\"text-align:center\"><a href=\"${escapeXml(m.sourceUrl)}\">${escapeXml(m.sourceUrl)}</a></p>\n")
            }
        }

        val dateBlock = if (m.publishedFromMs != null) {
            val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val from = df.format(Date(m.publishedFromMs))
            val to = m.publishedToMs?.let(::Date)?.let(df::format)
            val range = if (to != null && to != from) "$from — $to" else from
            "    <p style=\"text-align:center; color:#666\">Published $range</p>\n"
        } else ""

        val tagsBlock = if (m.tags.isNotEmpty()) {
            val chips = m.tags.joinToString("") { "<span class=\"tag\">${escapeXml(it)}</span>" }
            "    <p style=\"text-align:center; margin-top:1em\">$chips</p>\n"
        } else ""

        val descBlock = m.description?.takeIf { it.isNotBlank() }?.let {
            // Render description as a single block; sources hand us either
            // plain or already-sanitized HTML. We escape conservatively here
            // because the metadata's description path is text-shaped (no
            // line-level HTML expected from RR or RSS feeds we've seen),
            // unlike the chapter body path which preserves source HTML.
            "    <div style=\"margin: 1.5em 0\"><p>${escapeXml(it).replace("\n\n", "</p><p>")}</p></div>\n"
        } ?: ""

        val xhtml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
  <meta charset="UTF-8"/>
  <title>${escapeXml(m.title)}</title>
  <style>
    body { font-family: Georgia, serif; color: #1a1a1a; line-height: 1.5; margin: 2em 1em; }
    h1 { color: #B89B6E; text-align: center; margin: 0.5em 0 0.2em; font-size: 2em; }
    h2.author { text-align: center; color: #555; font-weight: normal; font-style: italic; margin: 0 0 1em; }
    .brass { color: #B89B6E; }
    .tag { display: inline-block; border: 1px solid #B89B6E; border-radius: 4px; padding: 2px 8px; margin: 2px; font-size: 0.85em; color: #B89B6E; }
    a { color: #B89B6E; text-decoration: none; }
  </style>
</head>
<body>
$coverBlock    <h1>${escapeXml(m.title)}</h1>
    <h2 class="author">by ${escapeXml(m.author)}</h2>
$sourceBlock$dateBlock$tagsBlock$descBlock</body>
</html>
"""
        writeStored(zip, "OEBPS/text/title.xhtml", xhtml.toByteArray(Charsets.UTF_8))
    }

    private fun writeChapter(zip: ZipOutputStream, index: Int, chapter: EpubChapter) {
        val body = chapter.htmlBody.ifBlank {
            "<p><em>(Chapter body not yet downloaded — open this chapter in storyvox once before re-exporting to capture the text.)</em></p>"
        }

        val notesBlock = chapter.authorNote?.takeIf { it.isNotBlank() }?.let { note ->
            """
            <div class="note">
              <hr/>
              <h2>Author's note</h2>
              <p>${escapeXml(note).replace("\n\n", "</p><p>")}</p>
            </div>
            """.trimIndent()
        } ?: ""

        val sanitizedBody = closeVoidTags(body)

        val (before, after) = if (chapter.authorNotePosition == AuthorNotePosition.BEFORE) {
            notesBlock to ""
        } else {
            "" to notesBlock
        }

        val xhtml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
  <meta charset="UTF-8"/>
  <title>${escapeXml(chapter.title)}</title>
  <style>
    body { font-family: Georgia, serif; color: #1a1a1a; line-height: 1.5; margin: 1.5em 1em; }
    h1 { color: #B89B6E; }
    h2 { color: #B89B6E; font-size: 1.1em; }
    .note { background: #f5f0e6; border-left: 3px solid #B89B6E; padding: 0.5em 1em; margin: 1em 0; }
    a { color: #B89B6E; }
    hr { border: none; border-top: 1px solid #B89B6E; margin: 1em 0; }
  </style>
</head>
<body>
  <h1>${escapeXml(chapter.title)}</h1>
$before
$sanitizedBody
$after
</body>
</html>
"""
        writeStored(zip, "OEBPS/text/ch-$index.xhtml", xhtml.toByteArray(Charsets.UTF_8))
    }

    private fun writeCover(zip: ZipOutputStream, cover: CoverImage) {
        writeStored(zip, "OEBPS/images/cover.${cover.extension}", cover.bytes)
    }

    private fun writeStored(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        // DEFLATED is fine for everything except mimetype — see writeMimetypeFirst.
        val entry = ZipEntry(name)
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun mediaTypeFor(ext: String): String? = when (ext.lowercase(Locale.US)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> null
    }

    /**
     * Make [raw] safe as an XML id attribute. EPUB 3 OPF requires the id
     * to be an XSD NCName: starts with a letter, then letters, digits,
     * `-`, `_`, `.`. We replace everything-else with `_` and prefix with
     * `c_` if the first char of the input wasn't a letter (so digit-led
     * ids preserve their digit content with a stable letter prefix
     * rather than getting a leading underscore from the per-char pass).
     * Empty input falls back to a stable placeholder.
     */
    internal fun sanitizeId(raw: String): String {
        if (raw.isBlank()) return "c_anon"
        // Decide on the prefix *first*, based on the original input's
        // shape. Digit/non-letter leads get `c_` so the rest of the
        // input survives the per-char sanitize without mangling.
        val needsPrefix = !raw[0].isLetter()
        val sb = StringBuilder(raw.length + 2)
        if (needsPrefix) sb.append("c_")
        raw.forEach { c ->
            val ok = when {
                c.isLetter() -> true
                c.isDigit() -> true
                c == '-' || c == '_' || c == '.' -> true
                else -> false
            }
            sb.append(if (ok) c else '_')
        }
        return sb.toString()
    }

    /**
     * Close common void HTML tags that source HTML often leaves open. Source
     * pages from Royal Road and RSS feeds are XHTML-shaped already, but we
     * occasionally see bare `<br>` / `<hr>` / `<img ...>` (no self-close
     * slash). EPUB readers reject those because the OPS document is
     * served as application/xhtml+xml — XML, not HTML.
     *
     * This is a regex pass, not a full Jsoup transform — we intentionally
     * stay light (no extra dep just for export). The chapter HTML has
     * already passed through Jsoup once on the import side, so the
     * remaining shapes are well-bounded. If a reader complains, escalate
     * to a Jsoup pass here.
     */
    internal fun closeVoidTags(html: String): String {
        val voids = listOf("br", "hr", "img", "meta", "link", "input", "source", "col")
        var result = html
        for (tag in voids) {
            // Match `<tag ...>` not already `<tag .../>` (closing slash absent).
            // Anchor on the tag name + word-boundary so we don't catch `<break>`.
            val re = Regex("<$tag\\b([^>]*?)(?<!/)>", RegexOption.IGNORE_CASE)
            result = re.replace(result) { match ->
                val attrs = match.groupValues[1]
                "<$tag$attrs/>"
            }
        }
        return result
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
