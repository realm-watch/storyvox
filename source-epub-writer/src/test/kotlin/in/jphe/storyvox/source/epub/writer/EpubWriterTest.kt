package `in`.jphe.storyvox.source.epub.writer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #117 — verifies the [EpubWriter] produces a spec-compliant EPUB 3
 * file structure:
 *
 *  - `mimetype` is the first entry, STORED (uncompressed), with the exact
 *    payload `application/epub+zip` — this is how reader apps identify
 *    the file as EPUB without parsing the central directory.
 *  - `META-INF/container.xml` points to `OEBPS/content.opf`.
 *  - `OEBPS/content.opf` declares every chapter in its manifest and spine.
 *  - The synthetic title page is the first spine item.
 *  - Chapter XHTML files contain the original body text.
 *
 * No external EPUB parser dependency — these tests parse the zip and the
 * minimum amount of XML they need with regex / substring, which is the
 * point of rolling our own writer (we own the structure).
 */
class EpubWriterTest {

    private val writer = EpubWriter()

    @Test
    fun `mimetype is first entry, STORED, with exact payload`() {
        val bytes = writeMinimalBook()

        // We need to inspect the entry method on the raw zip — ZipInputStream
        // exposes `method` on each entry it reads.
        val zip = ZipInputStream(ByteArrayInputStream(bytes))
        val first = zip.nextEntry
        assertNotNull("mimetype must be present", first)
        assertEquals("First entry must be 'mimetype'", "mimetype", first!!.name)
        assertEquals(
            "mimetype must be STORED (uncompressed) per EPUB 3 spec",
            ZipEntry.STORED,
            first.method,
        )
        val payload = zip.readBytes().toString(Charsets.US_ASCII)
        assertEquals("application/epub+zip", payload)
    }

    @Test
    fun `container_xml points to the OPF root file`() {
        val bytes = writeMinimalBook()
        val container = readEntry(bytes, "META-INF/container.xml")
            ?: error("META-INF/container.xml missing")
        assertTrue(
            "container.xml must reference OEBPS/content.opf",
            container.contains("OEBPS/content.opf"),
        )
        assertTrue(
            "container.xml must declare the OEBPS media type",
            container.contains("application/oebps-package+xml"),
        )
    }

    @Test
    fun `OPF lists every chapter in manifest and spine`() {
        val chapters = listOf(
            EpubChapter(id = "ch1", title = "Chapter One", htmlBody = "<p>body 1</p>"),
            EpubChapter(id = "ch2", title = "Chapter Two", htmlBody = "<p>body 2</p>"),
            EpubChapter(id = "ch3", title = "Chapter Three", htmlBody = "<p>body 3</p>"),
        )
        val bytes = write(book(chapters))
        val opf = readEntry(bytes, "OEBPS/content.opf")
            ?: error("content.opf missing")

        // Every chapter id appears in the manifest as an <item href> and in
        // the spine as an <itemref idref>.
        chapters.forEachIndexed { i, ch ->
            assertTrue(
                "manifest must include text/ch-$i.xhtml",
                opf.contains("href=\"text/ch-$i.xhtml\""),
            )
            assertTrue(
                "spine must reference ${ch.id}",
                opf.contains("itemref idref=\"${ch.id}\""),
            )
        }
        assertTrue("title page must precede chapters in spine", opf.indexOf("idref=\"title\"") < opf.indexOf("idref=\"ch1\""))
    }

    @Test
    fun `chapter XHTML files contain the body text`() {
        val chapters = listOf(
            EpubChapter(id = "ch1", title = "First", htmlBody = "<p>Once upon a time, frob.</p>"),
            EpubChapter(id = "ch2", title = "Second", htmlBody = "<p>The end, blat.</p>"),
        )
        val bytes = write(book(chapters))
        val ch1 = readEntry(bytes, "OEBPS/text/ch-0.xhtml") ?: error("ch-0 missing")
        val ch2 = readEntry(bytes, "OEBPS/text/ch-1.xhtml") ?: error("ch-1 missing")
        assertTrue(ch1.contains("Once upon a time, frob."))
        assertTrue(ch1.contains("First"))
        assertTrue(ch2.contains("The end, blat."))
    }

    @Test
    fun `author note renders after body with horizontal rule when position is AFTER`() {
        val chapters = listOf(
            EpubChapter(
                id = "ch1",
                title = "Postface",
                htmlBody = "<p>main body</p>",
                authorNote = "thanks for reading",
                authorNotePosition = AuthorNotePosition.AFTER,
            ),
        )
        val bytes = write(book(chapters))
        val ch = readEntry(bytes, "OEBPS/text/ch-0.xhtml") ?: error("ch missing")
        // Body appears before note; note section contains the heading + hr.
        val bodyIdx = ch.indexOf("main body")
        val noteIdx = ch.indexOf("Author's note")
        val hrIdx = ch.indexOf("<hr/>", startIndex = bodyIdx)
        assertTrue("body before note", bodyIdx in 0..<noteIdx)
        // Spec says "horizontal rule above" the heading — so hr sits between
        // body and the heading text, not strictly inside the note region.
        assertTrue("hr between body and note heading", bodyIdx < hrIdx && hrIdx < noteIdx)
        assertTrue(ch.contains("thanks for reading"))
    }

    @Test
    fun `author note renders before body when position is BEFORE`() {
        val chapters = listOf(
            EpubChapter(
                id = "ch1",
                title = "With preface",
                htmlBody = "<p>main body</p>",
                authorNote = "heads up",
                authorNotePosition = AuthorNotePosition.BEFORE,
            ),
        )
        val bytes = write(book(chapters))
        val ch = readEntry(bytes, "OEBPS/text/ch-0.xhtml") ?: error("ch missing")
        assertTrue("note before body", ch.indexOf("heads up") < ch.indexOf("main body"))
    }

    @Test
    fun `title page surfaces metadata blocks`() {
        val bytes = write(
            EpubBook(
                identifier = "urn:storyvox:fic-meta",
                title = "Sample Tale",
                author = "Jane Doe",
                titlePageMetadata = TitlePageMetadata(
                    title = "Sample Tale",
                    author = "Jane Doe",
                    sourceName = "Royal Road",
                    sourceUrl = "https://www.royalroad.com/fiction/12345",
                    publishedFromMs = 1700000000000L,
                    publishedToMs = 1710000000000L,
                    tags = listOf("Fantasy", "LitRPG"),
                    description = "A short fictional sample for the test.",
                ),
                chapters = listOf(
                    EpubChapter("ch1", "Only Chapter", "<p>only body</p>"),
                ),
            ),
        )
        val title = readEntry(bytes, "OEBPS/text/title.xhtml") ?: error("title.xhtml missing")
        assertTrue("title page mentions source name", title.contains("From Royal Road"))
        assertTrue("title page links to the source URL", title.contains("https://www.royalroad.com/fiction/12345"))
        assertTrue("title page surfaces author", title.contains("Jane Doe"))
        assertTrue("title page lists tags", title.contains("Fantasy") && title.contains("LitRPG"))
        assertTrue("title page includes description", title.contains("A short fictional sample for the test."))
        assertTrue("title page shows date range", title.contains("Published "))
    }

    @Test
    fun `unicode and XML special chars in titles round-trip safely`() {
        val bytes = write(
            EpubBook(
                identifier = "urn:storyvox:fic-x",
                title = "Tales & Truths <draft>",
                author = "André O'Connor",
                titlePageMetadata = TitlePageMetadata(
                    title = "Tales & Truths <draft>",
                    author = "André O'Connor",
                    sourceName = "Royal Road",
                ),
                chapters = listOf(EpubChapter("ch1", "1 & 2 < 3", "<p>body</p>")),
            ),
        )
        val opf = readEntry(bytes, "OEBPS/content.opf") ?: error("OPF missing")
        // Special chars must be escaped (no raw `&` outside `&amp;` / no raw `<`).
        assertTrue("ampersand is escaped", opf.contains("Tales &amp; Truths &lt;draft&gt;"))
        assertTrue("apostrophe is escaped", opf.contains("Andr") && opf.contains("&apos;Connor"))
    }

    @Test
    fun `void HTML tags get self-closed in chapter XHTML`() {
        val chapters = listOf(
            EpubChapter(
                id = "ch1",
                title = "Tagged",
                htmlBody = "<p>before<br>after</p><hr><img src=\"x\">",
            ),
        )
        val bytes = write(book(chapters))
        val ch = readEntry(bytes, "OEBPS/text/ch-0.xhtml") ?: error("ch missing")
        assertTrue("br is self-closed", ch.contains("<br/>"))
        assertTrue("hr is self-closed", ch.contains("<hr/>"))
        assertTrue("img is self-closed", ch.contains("<img src=\"x\"/>"))
        // And the original open forms should be gone from the body region.
        assertTrue("no bare <br>", !ch.contains("<br>"))
        assertTrue("no bare <hr>", !ch.contains("<hr>"))
    }

    @Test
    fun `cover is included when provided and tagged in OPF`() {
        val cover = CoverImage(
            bytes = ByteArray(64) { 0xCA.toByte() },
            extension = "jpg",
        )
        val bytes = write(book(coversTo = cover))
        val opf = readEntry(bytes, "OEBPS/content.opf") ?: error("OPF missing")
        assertTrue("OPF declares cover-image manifest entry", opf.contains("properties=\"cover-image\""))
        assertTrue("OPF references the cover file by name", opf.contains("href=\"images/cover.jpg\""))
        // Cover file itself is in the zip.
        val coverEntry = readEntryRaw(bytes, "OEBPS/images/cover.jpg")
        assertNotNull("cover.jpg must be packaged", coverEntry)
        assertEquals(64, coverEntry!!.size)
    }

    @Test
    fun `OPF omits cover meta when no cover supplied`() {
        val bytes = write(book(chapters = listOf(EpubChapter("ch1", "x", "<p>x</p>"))))
        val opf = readEntry(bytes, "OEBPS/content.opf") ?: error("OPF missing")
        assertTrue(!opf.contains("cover-image"))
        assertNull("no cover file in zip", readEntryRaw(bytes, "OEBPS/images/cover.jpg"))
    }

    @Test
    fun `missing chapter body surfaces a visible placeholder, not an empty page`() {
        val chapters = listOf(EpubChapter("ch1", "No body yet", htmlBody = ""))
        val bytes = write(book(chapters))
        val ch = readEntry(bytes, "OEBPS/text/ch-0.xhtml") ?: error("ch missing")
        assertTrue(
            "Empty body must render an explanatory placeholder",
            ch.contains("not yet downloaded"),
        )
    }

    @Test
    fun `sanitizeId replaces colons and other invalid id chars`() {
        // The OPF spec requires NCName ids — letters, digits, `-`, `_`, `.`.
        // Storyvox fictionIds use ':' (`royalroad:12345`, `github:owner/repo`),
        // which would break a strict XML parser.
        assertEquals("royalroad_12345", writer.sanitizeId("royalroad:12345"))
        assertEquals("github_owner_repo", writer.sanitizeId("github:owner/repo"))
        // First-char-not-letter cases get prefixed.
        assertEquals("c_123abc", writer.sanitizeId("123abc"))
        assertEquals("c_anon", writer.sanitizeId(""))
    }

    @Test
    fun `closeVoidTags is idempotent on already-self-closed input`() {
        val pre = "<p>x</p><br/><hr/><img src=\"a\"/>"
        val post = writer.closeVoidTags(pre)
        assertEquals("self-closed input round-trips unchanged", pre, post)
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun writeMinimalBook(): ByteArray = write(book())

    private fun book(
        chapters: List<EpubChapter> = listOf(
            EpubChapter(id = "ch1", title = "Only Chapter", htmlBody = "<p>only body</p>"),
        ),
        coversTo: CoverImage? = null,
    ): EpubBook = EpubBook(
        identifier = "urn:storyvox:test",
        title = "Test Book",
        author = "Test Author",
        cover = coversTo,
        titlePageMetadata = TitlePageMetadata(
            title = "Test Book",
            author = "Test Author",
            sourceName = "Royal Road",
        ),
        chapters = chapters,
    )

    private fun write(book: EpubBook): ByteArray {
        val out = ByteArrayOutputStream()
        writer.write(book, out)
        return out.toByteArray()
    }

    private fun readEntry(zipBytes: ByteArray, name: String): String? {
        return readEntryRaw(zipBytes, name)?.toString(Charsets.UTF_8)
    }

    private fun readEntryRaw(zipBytes: ByteArray, name: String): ByteArray? {
        val zip = ZipInputStream(ByteArrayInputStream(zipBytes))
        while (true) {
            val e = zip.nextEntry ?: return null
            if (e.name == name) return zip.readBytes()
        }
    }
}
