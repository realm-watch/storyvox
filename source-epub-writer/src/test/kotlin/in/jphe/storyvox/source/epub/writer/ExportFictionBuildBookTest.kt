package `in`.jphe.storyvox.source.epub.writer

import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.Fiction
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.NotePosition
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #117 — integration-style test that exercises [ExportFictionToEpubUseCase.buildBook]
 * against seeded Fiction + Chapter rows, then writes the resulting [EpubBook]
 * through [EpubWriter] and asserts the zip contents end-to-end.
 *
 * Does NOT touch the [ExportFictionToEpubUseCase.export] path because that
 * needs an Android Context (cache dir + FileProvider) — those bindings are
 * exercised on-device, not in unit tests. The book-composition logic here
 * is where every interesting decision lives: title page metadata, author
 * note routing, missing-body fallback, source URL derivation.
 */
class ExportFictionBuildBookTest {

    private val useCase = ExportFictionToEpubUseCase(
        // The DAO references are unused by buildBook (the integration path
        // pulls the rows itself); the test injects throwaway no-op fakes
        // via reflection-free direct instantiation isn't worth it here.
        // We construct the use case with null-equivalents by going through
        // the internal API surface — buildBook doesn't read the DAOs.
        fictionDao = NoopFictionDao(),
        chapterDao = NoopChapterDao(),
    )

    private val writer = EpubWriter()

    @Test
    fun `seeded fiction + 3 chapters round-trips through writer with chapter texts present`() {
        val fiction = Fiction(
            id = "royalroad:42",
            sourceId = SourceIds.ROYAL_ROAD,
            title = "The Wayward Mage",
            author = "Alice Author",
            description = "A short test fiction with three downloaded chapters.",
            tags = listOf("Fantasy", "Adventure"),
            status = FictionStatus.ONGOING,
            chapterCount = 3,
            firstSeenAt = 1700000000000L,
            metadataFetchedAt = 1700000000000L,
        )
        val chapters = listOf(
            chapter(fiction.id, idx = 0, title = "Awakening", plain = "Chapter one body, the unmistakable smell of pine."),
            chapter(fiction.id, idx = 1, title = "The Road", plain = "Chapter two body, hooves clattering on cobblestone."),
            chapter(fiction.id, idx = 2, title = "Arrival", plain = "Chapter three body, the city rose in the distance."),
        )

        val book = useCase.buildBook(fiction, chapters, cover = null)

        // Book-level metadata
        assertEquals("urn:storyvox:royalroad:42", book.identifier)
        assertEquals("The Wayward Mage", book.title)
        assertEquals("Alice Author", book.author)
        assertEquals(3, book.chapters.size)
        assertEquals("Royal Road", book.titlePageMetadata.sourceName)
        assertEquals(
            "https://www.royalroad.com/fiction/42",
            book.titlePageMetadata.sourceUrl,
        )
        assertEquals(listOf("Fantasy", "Adventure"), book.titlePageMetadata.tags)

        // Plain bodies get wrapped in <p> when no htmlBody is present.
        assertTrue(book.chapters[0].htmlBody.contains("smell of pine"))

        // Round-trip through the writer
        val bytes = ByteArrayOutputStream().also { writer.write(book, it) }.toByteArray()

        // All three chapter texts must appear in their respective entries.
        val ch0 = readEntry(bytes, "OEBPS/text/ch-0.xhtml") ?: error("ch-0 missing")
        val ch1 = readEntry(bytes, "OEBPS/text/ch-1.xhtml") ?: error("ch-1 missing")
        val ch2 = readEntry(bytes, "OEBPS/text/ch-2.xhtml") ?: error("ch-2 missing")
        assertTrue(ch0.contains("smell of pine"))
        assertTrue(ch1.contains("clattering on cobblestone"))
        assertTrue(ch2.contains("the city rose in the distance"))

        val title = readEntry(bytes, "OEBPS/text/title.xhtml") ?: error("title missing")
        assertTrue("title page mentions source", title.contains("From Royal Road"))
        assertTrue("title page links to source URL", title.contains("https://www.royalroad.com/fiction/42"))
    }

    @Test
    fun `author note from chapter row appears in the chapter XHTML`() {
        val fiction = baseFiction("royalroad:99", "Notable Tales")
        val chapters = listOf(
            chapter(fiction.id, idx = 0, title = "First", plain = "ZBODYZERO",
                notesAuthor = "Thanks for reading!",
                notesAuthorPosition = NotePosition.AFTER),
            chapter(fiction.id, idx = 1, title = "Second", plain = "ZBODYONE",
                notesAuthor = "Heads up: dragons.",
                notesAuthorPosition = NotePosition.BEFORE),
            chapter(fiction.id, idx = 2, title = "Third", plain = "ZBODYTWO", notesAuthor = null),
        )

        val book = useCase.buildBook(fiction, chapters)

        assertEquals("Thanks for reading!", book.chapters[0].authorNote)
        assertEquals(AuthorNotePosition.AFTER, book.chapters[0].authorNotePosition)
        assertEquals("Heads up: dragons.", book.chapters[1].authorNote)
        assertEquals(AuthorNotePosition.BEFORE, book.chapters[1].authorNotePosition)
        assertNull(book.chapters[2].authorNote)

        val bytes = ByteArrayOutputStream().also { writer.write(book, it) }.toByteArray()
        val ch1 = readEntry(bytes, "OEBPS/text/ch-1.xhtml") ?: error("ch-1 missing")
        // Note BEFORE: the note text appears before the body text. Use a
        // distinctive sentinel for the body so we don't accidentally collide
        // with literal `body` chars in the rendered CSS / <body> tag.
        assertTrue("note before body in ch-1", ch1.indexOf("Heads up: dragons.") < ch1.indexOf("ZBODYONE"))
    }

    @Test
    fun `chapters with no body get a placeholder, not blank`() {
        val fiction = baseFiction("royalroad:1", "Half-Downloaded")
        val chapters = listOf(
            chapter(fiction.id, idx = 0, title = "Downloaded", plain = "real text"),
            chapter(fiction.id, idx = 1, title = "Not Yet", plain = null), // not downloaded
        )

        val book = useCase.buildBook(fiction, chapters)
        val bytes = ByteArrayOutputStream().also { writer.write(book, it) }.toByteArray()

        val ch1 = readEntry(bytes, "OEBPS/text/ch-1.xhtml") ?: error("ch-1 missing")
        assertTrue("placeholder visible in undownloaded chapter", ch1.contains("not yet downloaded"))
        // Still produces a valid file (the chapter is in spine).
        val opf = readEntry(bytes, "OEBPS/content.opf") ?: error("opf missing")
        assertTrue("spine includes the undownloaded chapter", opf.contains("href=\"text/ch-1.xhtml\""))
    }

    @Test
    fun `htmlBody is preferred over plainBody when both are present`() {
        val fiction = baseFiction("royalroad:7", "HTML Wins")
        val chapters = listOf(
            chapter(
                fictionId = fiction.id,
                idx = 0,
                title = "First",
                plain = "should not appear",
                html = "<p><em>this is the canonical body</em></p>",
            ),
        )

        val book = useCase.buildBook(fiction, chapters)
        assertTrue(book.chapters[0].htmlBody.contains("canonical body"))
        assertTrue(!book.chapters[0].htmlBody.contains("should not appear"))
    }

    @Test
    fun `RSS source maps to a feed URL and 'an RSS feed' label`() {
        val fiction = baseFiction("rss:https://example.com/feed.xml", "RSS Tale").copy(sourceId = SourceIds.RSS)
        val book = useCase.buildBook(fiction, emptyList())
        assertEquals("an RSS feed", book.titlePageMetadata.sourceName)
        assertEquals("https://example.com/feed.xml", book.titlePageMetadata.sourceUrl)
    }

    @Test
    fun `unknown source falls back to id-as-label and null url`() {
        val fiction = baseFiction("dummy-id", "Unmapped").copy(sourceId = "unknown_source")
        val book = useCase.buildBook(fiction, emptyList())
        assertEquals("unknown_source", book.titlePageMetadata.sourceName)
        assertNull("unknown sources surface no clickable URL", book.titlePageMetadata.sourceUrl)
    }

    @Test
    fun `published date range derives from earliest and latest chapter publishedAt`() {
        val fiction = baseFiction("royalroad:5", "Dated")
        val chapters = listOf(
            chapter(fiction.id, idx = 0, title = "a", plain = "1", publishedAt = 1_700_000_000_000L),
            chapter(fiction.id, idx = 1, title = "b", plain = "2", publishedAt = 1_700_001_000_000L),
            chapter(fiction.id, idx = 2, title = "c", plain = "3", publishedAt = 1_700_002_000_000L),
        )
        val book = useCase.buildBook(fiction, chapters)
        assertEquals(1_700_000_000_000L, book.titlePageMetadata.publishedFromMs)
        assertEquals(1_700_002_000_000L, book.titlePageMetadata.publishedToMs)
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun baseFiction(id: String, title: String) = Fiction(
        id = id,
        sourceId = SourceIds.ROYAL_ROAD,
        title = title,
        author = "Test Author",
        firstSeenAt = 0L,
        metadataFetchedAt = 0L,
    )

    private fun chapter(
        fictionId: String,
        idx: Int,
        title: String,
        plain: String? = "body",
        html: String? = null,
        notesAuthor: String? = null,
        notesAuthorPosition: NotePosition? = null,
        publishedAt: Long? = null,
    ) = Chapter(
        id = "$fictionId:$idx",
        fictionId = fictionId,
        sourceChapterId = "src-$idx",
        index = idx,
        title = title,
        publishedAt = publishedAt,
        plainBody = plain,
        htmlBody = html,
        notesAuthor = notesAuthor,
        notesAuthorPosition = notesAuthorPosition,
    )

    private fun readEntry(zipBytes: ByteArray, name: String): String? {
        val zip = ZipInputStream(ByteArrayInputStream(zipBytes))
        while (true) {
            val e = zip.nextEntry ?: return null
            if (e.name == name) return zip.readBytes().toString(Charsets.UTF_8)
        }
    }
}
