package `in`.jphe.storyvox.source.epub.writer

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.Fiction
import `in`.jphe.storyvox.data.source.model.NotePosition
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Result of [ExportFictionToEpubUseCase]. Always carries a [file] / [uri]
 * pointing to the EPUB in the app's cache (FileProvider-backed); [warnings]
 * surfaces non-fatal issues like "cover failed to download" so the UI can
 * pass them into the share-sheet snackbar.
 */
data class EpubExportResult(
    val file: File,
    /** content:// URI granted via FileProvider — the value you pass to
     *  ACTION_SEND / ACTION_CREATE_DOCUMENT. */
    val uri: Uri,
    /** Suggested user-facing filename for the SAF "Save…" path. */
    val suggestedFileName: String,
    /** Human-readable description of any non-fatal issues. Empty when
     *  the export was clean. UI can show a snackbar like "Exported (no
     *  cover image)". */
    val warnings: List<String> = emptyList(),
)

/**
 * Builds a `.epub` file from a stored fiction's DB rows and stages it in
 * `context.cacheDir/exports/` for sharing or SAF Save-As.
 *
 * Threading: the bulk of the work runs on `Dispatchers.IO` — file write,
 * cover download, and the ZIP stream are all blocking. The caller (the
 * fiction-detail ViewModel) typically launches into `viewModelScope`.
 *
 * Issue #117 — covers + author notes + tags + source-link metadata are
 * all included per JP's decision recorded on the issue.
 */
@Singleton
class ExportFictionToEpubUseCase @Inject constructor(
    private val fictionDao: FictionDao,
    private val chapterDao: ChapterDao,
) {

    private val writer = EpubWriter()

    // Lazy client; only constructed on first export call. Best-effort
    // cover download, so we keep the timeouts short — if the cover host
    // is slow the user shouldn't be waiting on it. Failures fall back to
    // a cover-less EPUB, which is still valid EPUB 3.
    private val coverClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .readTimeout(java.time.Duration.ofSeconds(10))
            .build()
    }

    /**
     * Build the EPUB. Throws [IllegalStateException] when there's no
     * Fiction row for [fictionId] — that's a bug at the call site, the
     * detail screen is what triggers export and it only renders for
     * fictions that exist.
     */
    suspend fun export(context: Context, fictionId: String): EpubExportResult = withContext(Dispatchers.IO) {
        val fiction = fictionDao.get(fictionId)
            ?: error("No Fiction row for id=$fictionId — was the export menu shown for an unknown fiction?")

        // Pull every chapter row directly (not just ChapterInfo) so we
        // have htmlBody + plainBody + notesAuthor available. The dao's
        // slim observe* projection drops bodies for memory reasons; for
        // export we want them all.
        val chapters = chapterDao.allChapters(fictionId)

        val warnings = mutableListOf<String>()

        val cover = fiction.coverUrl?.let { url ->
            runCatching { fetchCover(url) }.getOrElse {
                warnings += "Cover image couldn't be downloaded (${it.javaClass.simpleName})"
                null
            }
        }
        val downloadedCount = chapters.count { !it.plainBody.isNullOrBlank() || !it.htmlBody.isNullOrBlank() }
        val missing = chapters.size - downloadedCount
        if (missing > 0) {
            warnings += "$missing chapter${if (missing == 1) "" else "s"} not yet downloaded — included as placeholders"
        }

        val book = buildBook(fiction, chapters, cover)

        val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val fileName = buildFileName(fiction.title.ifBlank { fiction.id })
        val file = File(outDir, fileName)

        FileOutputStream(file).use { fos -> writer.write(book, fos) }

        val uri = FileProvider.getUriForFile(
            context,
            // Authority matches the <provider> in app/AndroidManifest.xml.
            // Keep this string in sync with that manifest.
            "${context.packageName}.fileprovider",
            file,
        )

        EpubExportResult(
            file = file,
            uri = uri,
            suggestedFileName = fileName,
            warnings = warnings,
        )
    }

    /**
     * Pure book-composition function. Extracted from [export] so it's testable
     * without an Android Context — the integration test seeds Fiction + Chapter
     * rows and asserts the resulting [EpubBook] structure (chapter count, title
     * page metadata, author notes routing, tag list).
     *
     * Visible for testing only — production code path is [export].
     */
    internal fun buildBook(
        fiction: Fiction,
        chapters: List<Chapter>,
        cover: CoverImage? = null,
    ): EpubBook {
        val publishedTimes = chapters.mapNotNull { it.publishedAt }.sorted()
        return EpubBook(
            identifier = "urn:storyvox:${fiction.id}",
            title = fiction.title.ifBlank { "Untitled" },
            author = fiction.author.ifBlank { "Unknown" },
            cover = cover,
            titlePageMetadata = TitlePageMetadata(
                title = fiction.title.ifBlank { "Untitled" },
                author = fiction.author.ifBlank { "Unknown" },
                sourceName = SourceLabels.displayName(fiction.sourceId),
                sourceUrl = SourceLabels.sourceUrl(fiction.sourceId, fiction.id),
                publishedFromMs = publishedTimes.firstOrNull(),
                publishedToMs = publishedTimes.lastOrNull(),
                tags = fiction.tags,
                description = fiction.description,
            ),
            chapters = chapters.map { it.toEpubChapter() },
        )
    }

    /**
     * Suggested name for the cache file + the SAF picker's default value.
     * Slugified title + timestamp keeps multiple exports of the same
     * fiction distinguishable in the user's Downloads folder.
     */
    private fun buildFileName(title: String): String {
        val slug = title.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(60)
            .ifBlank { "fiction" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "$slug-$stamp.epub"
    }

    private fun fetchCover(url: String): CoverImage? {
        val req = Request.Builder().url(url).build()
        coverClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            // Guess extension from URL path; fall back to "jpg" since
            // every source we ship serves JPEG covers and a wrong
            // extension just means a reader app substitutes its own
            // media-type sniffer.
            val ext = url.substringAfterLast('.', "jpg").substringBefore('?')
                .lowercase(Locale.US)
                .let { if (it.length in 2..5) it else "jpg" }
            return CoverImage(bytes = bytes, extension = ext)
        }
    }

    private fun Chapter.toEpubChapter(): EpubChapter {
        // Prefer htmlBody for fidelity; fall back to plainBody wrapped in
        // <p> per double-newline so reader apps still see paragraph
        // breaks. Empty/null bodies surface as the placeholder note from
        // EpubWriter.writeChapter.
        val html = htmlBody
        val plain = plainBody
        val body: String = when {
            !html.isNullOrBlank() -> html
            !plain.isNullOrBlank() -> plain.split("\n\n")
                .filter { it.isNotBlank() }
                .joinToString("") { "<p>${escapeXml(it.trim())}</p>" }
            else -> ""
        }
        return EpubChapter(
            id = id,
            title = title.ifBlank { "Chapter ${index + 1}" },
            htmlBody = body,
            authorNote = notesAuthor,
            authorNotePosition = when (notesAuthorPosition) {
                NotePosition.BEFORE -> AuthorNotePosition.BEFORE
                NotePosition.AFTER, null -> AuthorNotePosition.AFTER
            },
        )
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
