package `in`.jphe.storyvox.source.epub

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.source.epub.config.EpubConfig
import `in`.jphe.storyvox.source.epub.config.EpubFileEntry
import `in`.jphe.storyvox.source.epub.parse.EpubBook
import `in`.jphe.storyvox.source.epub.parse.EpubParseException
import `in`.jphe.storyvox.source.epub.parse.EpubParser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #235 — local EPUB files as a fiction backend. Each indexed
 * EPUB in the user-picked folder maps to one fiction; the spine
 * (`<itemref>` list in the OPF) is the chapter list.
 *
 * Pure user-content backend: zero network, zero ToS surface, zero
 * scraping. The user owns the .epub files; storyvox just renders
 * what's already on their device.
 *
 * Caching: the spine is parsed on every fictionDetail / chapter call.
 * For typical audiobook-length EPUBs (5-50 MB, dozens to hundreds of
 * spine items) that's a sub-second hit on modern hardware. If it ever
 * becomes a concern, an in-memory map keyed by `fictionId` + file
 * mtime would short-circuit the re-parse — same shape we use for
 * GitHub manifests via cheap-poll.
 */
@SourcePlugin(
    id = SourceIds.EPUB,
    displayName = "Local EPUB files",
    defaultEnabled = false,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = false,
)
@Singleton
internal class EpubSource @Inject constructor(
    private val config: EpubConfig,
) : FictionSource {

    override val id: String = SourceIds.EPUB
    override val displayName: String = "Local Books"

    // ─── browse ────────────────────────────────────────────────────────

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        listIndexedBooks()

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        listIndexedBooks()

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        // EPUBs don't carry genre metadata in the OPF in any
        // standardized form (BISAC subjects vary). Surfacing nothing
        // is more honest than fabricating buckets.
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim().lowercase()
        if (term.isEmpty()) return listIndexedBooks()
        val all = config.books()
        val filtered = all.filter { it.displayName.lowercase().contains(term) }
            .map { it.toSummary() }
        return FictionResult.Success(ListPage(items = filtered, page = 1, hasNext = false))
    }

    private suspend fun listIndexedBooks(): FictionResult<ListPage<FictionSummary>> {
        val all = config.books().map { it.toSummary() }
        return FictionResult.Success(ListPage(items = all, page = 1, hasNext = false))
    }

    // ─── detail ────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val entry = config.books().firstOrNull { it.fictionId == fictionId }
            ?: return FictionResult.NotFound("EPUB not indexed: $fictionId")

        val book = parseBook(entry) ?: return FictionResult.NetworkError(
            "Failed to read EPUB: ${entry.displayName}", null,
        )

        val chapters = book.chapters.map { ch ->
            ChapterInfo(
                id = chapterIdFor(fictionId, ch.id),
                sourceChapterId = ch.id,
                index = ch.index,
                title = ch.title,
                wordCount = ch.htmlBody.estimatedWordCount(),
            )
        }

        val summary = FictionSummary(
            id = fictionId,
            sourceId = SourceIds.EPUB,
            title = book.title.ifBlank { entry.displayName.removeSuffix(".epub") },
            author = book.author.ifBlank { "" },
            status = FictionStatus.COMPLETED, // EPUB = static file = no further chapters
            chapterCount = chapters.size,
        )
        return FictionResult.Success(
            FictionDetail(summary = summary, chapters = chapters),
        )
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val entry = config.books().firstOrNull { it.fictionId == fictionId }
            ?: return FictionResult.NotFound("EPUB not indexed: $fictionId")

        val book = parseBook(entry) ?: return FictionResult.NetworkError(
            "Failed to read EPUB: ${entry.displayName}", null,
        )

        val chapter = book.chapters.firstOrNull {
            chapterIdFor(fictionId, it.id) == chapterId
        } ?: return FictionResult.NotFound("Chapter not in EPUB: $chapterId")

        val info = ChapterInfo(
            id = chapterId,
            sourceChapterId = chapter.id,
            index = chapter.index,
            title = chapter.title,
            wordCount = chapter.htmlBody.estimatedWordCount(),
        )
        return FictionResult.Success(
            ChapterContent(
                info = info,
                htmlBody = chapter.htmlBody,
                plainBody = chapter.htmlBody.stripHtml(),
            ),
        )
    }

    // ─── auth-gated ─────────────────────────────────────────────────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        // No upstream "follows" for local files. Returning the indexed
        // list matches the user mental model: "the books I have."
        listIndexedBooks()

    override suspend fun setFollowed(
        fictionId: String,
        followed: Boolean,
    ): FictionResult<Unit> = FictionResult.Success(Unit)

    // ─── helpers ────────────────────────────────────────────────────────

    private suspend fun parseBook(entry: EpubFileEntry): EpubBook? {
        val bytes = config.readBookBytes(entry.uriString) ?: return null
        return try {
            EpubParser.parseFromBytes(bytes)
        } catch (_: EpubParseException) {
            null
        }
    }
}

/** Compose a stable chapter id from the fictionId + the per-EPUB
 *  spine idref. Keeps chapter ids unique across books. */
private fun chapterIdFor(fictionId: String, idref: String): String =
    "${fictionId}::${idref.hashCode().toUInt().toString(16)}"

private fun EpubFileEntry.toSummary(): FictionSummary = FictionSummary(
    id = fictionId,
    sourceId = SourceIds.EPUB,
    title = displayName.removeSuffix(".epub"),
    author = "",
    description = displayName,
    status = FictionStatus.COMPLETED,
)

/** Naive HTML strip mirroring RssSource — the EngineStreamingSource
 *  applies further normalization downstream. */
private fun String.stripHtml(): String =
    replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

/** Word count from raw HTML — strip tags, split on whitespace.
 *  Cheap heuristic for surfacing chapter length in the picker. */
private fun String.estimatedWordCount(): Int {
    val plain = stripHtml()
    if (plain.isEmpty()) return 0
    return plain.split(Regex("\\s+")).size
}
