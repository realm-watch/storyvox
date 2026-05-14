package `in`.jphe.storyvox.source.ao3

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
import `in`.jphe.storyvox.source.ao3.di.Ao3Cache
import `in`.jphe.storyvox.source.ao3.net.Ao3Api
import `in`.jphe.storyvox.source.ao3.net.Ao3AtomFeed
import `in`.jphe.storyvox.source.ao3.net.Ao3FeedEntry
import `in`.jphe.storyvox.source.epub.parse.EpubBook
import `in`.jphe.storyvox.source.epub.parse.EpubParseException
import `in`.jphe.storyvox.source.epub.parse.EpubParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #381 — Archive of Our Own as a fiction backend.
 *
 * Two-phase model, mirroring [GutenbergSource][in.jphe.storyvox.source.gutenberg.GutenbergSource]:
 *
 *  - **Catalog** — per-tag Atom feeds at
 *    `https://archiveofourown.org/tags/<tag>/feed.atom`. Each entry
 *    carries title + author + summary + tags + work id, enough to
 *    populate the Browse grid without a detail fetch.
 *
 *  - **Content** — per-work EPUB downloads at
 *    `/downloads/<work_id>/<slug>.epub`. The bytes go straight to
 *    [EpubParser]; chapter rendering then walks the EPUB spine the
 *    same way Gutenberg / local EPUB do (#235 / #237).
 *
 * AO3 doesn't expose a unified catalog — discovery is fundamentally
 * per-tag. v1 ships a curated handful of fandoms in the Browse
 * picker (see [CURATED_TAGS]); when no genre is selected the source
 * defaults to a broad "Original Work" feed so the picker has
 * something to render on first open. Users wanting other fandoms
 * use Search (deferred — AO3's search is HTML-only and we don't
 * want any scraping in v1) or a future tag-picker UI.
 *
 * Legal posture: AO3 is run by the Organization for Transformative
 * Works, a 501(c)(3). Their ToS draws a hard line at commercial
 * scraping and paid-access apps. storyvox is free, open-source, and
 * uses only the two official surfaces above — no HTML scraping, no
 * paywall, no ads. The User-Agent identifies us with a contact URL so
 * OTW Ops can route any concerns to a real address.
 *
 * Caveat carried in the issue: some "Archive Warning: Choose Not to
 * Use Warnings" works require a logged-in session for the EPUB
 * download. We surface those as [FictionResult.AuthRequired] today
 * and let the UI render a friendly "sign-in required" empty state.
 * Sign-in is a deliberate follow-up (#381 mentions threading the
 * AUTH_WEBVIEW pattern from #211 through).
 */
@SourcePlugin(
    id = SourceIds.AO3,
    displayName = "Archive of Our Own",
    defaultEnabled = false,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = true,
)
@Singleton
internal class Ao3Source @Inject constructor(
    private val api: Ao3Api,
    @Ao3Cache private val cacheDir: File,
) : FictionSource {

    override val id: String = SourceIds.AO3
    override val displayName: String = "Archive of Our Own"

    /**
     * In-memory cache of parsed EpubBook keyed by fictionId. AO3
     * works range from drabbles (10 KB) to multi-million-word novels
     * (50+ MB EPUBs); cache hits return the parsed spine without
     * re-running EpubParser, which can take a noticeable hit on the
     * larger end. Per-process; rebuilds from the on-disk `.epub`
     * after a process restart.
     */
    private val parsedCache = mutableMapOf<String, EpubBook>()

    // ─── browse ────────────────────────────────────────────────────────

    /**
     * No global "popular" surface on AO3 — works are popular *within*
     * tags. v1 maps Popular = the default-tag feed (Original Work).
     * The genre row picks specific fandoms; when the user has selected
     * a fandom via [byGenre], that takes over and this never fires.
     */
    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        feedAsListPage(DEFAULT_TAG, page)

    /**
     * Same surface as [popular] — the Atom feed is sorted by recency,
     * not popularity. AO3 doesn't expose a "popular this week"
     * endpoint without HTML scraping, which v1 explicitly opts out of.
     * Both tabs surface the same recent-first feed; this keeps the UI
     * coherent (Browse → AO3 always shows fresh content) without
     * pretending we have a popularity signal we don't.
     */
    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        feedAsListPage(DEFAULT_TAG, page)

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        feedAsListPage(genre, page)

    /**
     * Search is deferred per the issue spec — AO3's `/works/search`
     * returns an HTML listing only (no Atom or JSON equivalent), and
     * v1 commits to zero scraping. Return an empty page so the UI
     * renders the no-results empty state cleanly; a future PR
     * (tracked in the #381 follow-up list) can either revisit HTML
     * parsing or layer search over the per-tag feeds.
     */
    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    /**
     * Curated tag list for the Browse genre picker. Six fandoms hand-
     * picked for breadth — the v1 surface intentionally avoids the
     * full AO3 tag taxonomy (a million-plus tags don't fit in a
     * picker). A follow-up issue will let the user add their own
     * tags; until then this list is the surface.
     */
    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(CURATED_TAGS)

    // ─── detail ────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val workId = parseAo3Id(fictionId)
            ?: return FictionResult.NotFound("Not an AO3 fictionId: $fictionId")

        // Ensure the EPUB is on disk + parsed so we can return a real
        // chapter list. detail()'s contract is "no chapter bodies"
        // but we need the spine for the ChapterInfo[] regardless —
        // same flow Gutenberg uses. Unlike Gutenberg we don't have a
        // separate catalog API to fall back on for metadata; the
        // EPUB itself carries the canonical title/author.
        val parsed = when (val r = ensureParsed(fictionId, workId)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }

        val summary = FictionSummary(
            id = fictionId,
            sourceId = SourceIds.AO3,
            title = parsed.title.ifBlank { "AO3 work $workId" },
            author = parsed.author,
            // EpubBook doesn't expose a synopsis field — AO3 EPUBs
            // carry the work's summary in the first spine item
            // (the "Preface" page) which would require an extra
            // parse pass to extract. v1 leaves description null
            // and lets the user read the summary inline as the
            // first chapter, matching the AO3 web experience.
            description = null,
            coverUrl = null, // AO3 EPUBs ship a generic cover; skip.
            tags = emptyList(),
            // AO3 works can be ongoing or completed; the EPUB itself
            // doesn't reliably distinguish (the work-page HTML carries
            // the "Status: Completed" flag but we don't fetch that
            // in v1). Conservative default = Ongoing — the UI's
            // "may update" affordance is more truthful than a wrong
            // "Completed" badge.
            status = FictionStatus.ONGOING,
            chapterCount = parsed.chapters.size,
        )
        val chapters = parsed.chapters.map { ch ->
            ChapterInfo(
                id = chapterIdFor(fictionId, ch.index),
                sourceChapterId = ch.id,
                index = ch.index,
                title = ch.title,
            )
        }
        return FictionResult.Success(FictionDetail(summary = summary, chapters = chapters))
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val parsed = parsedCache[fictionId]
            ?: return when (val r = reparseFromDisk(fictionId)) {
                is FictionResult.Success -> chapter(fictionId, chapterId)
                is FictionResult.Failure -> r
            }
        val idx = chapterIndexFrom(chapterId)
            ?: return FictionResult.NotFound("Malformed chapter id: $chapterId")
        val ch = parsed.chapters.getOrNull(idx)
            ?: return FictionResult.NotFound("Chapter $idx out of range (have ${parsed.chapters.size})")
        val info = ChapterInfo(
            id = chapterId,
            sourceChapterId = ch.id,
            index = ch.index,
            title = ch.title,
        )
        return FictionResult.Success(
            ChapterContent(
                info = info,
                htmlBody = ch.htmlBody,
                plainBody = ch.htmlBody.stripTags(),
            ),
        )
    }

    // ─── auth-gated ────────────────────────────────────────────────────

    /**
     * AO3 does have a per-user "Subscriptions" surface, but it's
     * sign-in-only and v1 ships anonymous-only. Return an empty page
     * so the Follows tab renders the empty state instead of an
     * AuthRequired error — sign-in is the follow-up.
     */
    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.Success(Unit)

    // ─── helpers ───────────────────────────────────────────────────────

    private suspend fun feedAsListPage(
        tag: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> {
        return when (val r = api.tagFeed(tag, page)) {
            is FictionResult.Success -> FictionResult.Success(r.value.toListPage(page))
            is FictionResult.Failure -> r
        }
    }

    /**
     * Idempotent EPUB acquire-and-parse. First call for a work
     * downloads + parses + caches; subsequent calls return the cached
     * EpubBook. Surfaces network and parse failures separately so the
     * caller can show the right error copy.
     */
    private suspend fun ensureParsed(
        fictionId: String,
        workId: Long,
    ): FictionResult<EpubBook> {
        parsedCache[fictionId]?.let { return FictionResult.Success(it) }
        val onDisk = epubFileFor(fictionId)
        if (onDisk.exists() && onDisk.length() > 0L) {
            return reparseFromDisk(fictionId)
        }
        val bytes = when (val r = api.downloadEpub(workId)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        withContext(Dispatchers.IO) {
            onDisk.parentFile?.mkdirs()
            onDisk.writeBytes(bytes)
        }
        val parsed = try {
            withContext(Dispatchers.IO) { EpubParser.parseFromBytes(bytes) }
        } catch (e: EpubParseException) {
            return FictionResult.NetworkError("Could not parse AO3 EPUB: ${e.message}", e)
        }
        parsedCache[fictionId] = parsed
        return FictionResult.Success(parsed)
    }

    private suspend fun reparseFromDisk(fictionId: String): FictionResult<EpubBook> {
        val onDisk = epubFileFor(fictionId)
        if (!onDisk.exists()) {
            return FictionResult.NotFound("No cached EPUB for $fictionId")
        }
        val bytes = withContext(Dispatchers.IO) { onDisk.readBytes() }
        val parsed = try {
            withContext(Dispatchers.IO) { EpubParser.parseFromBytes(bytes) }
        } catch (e: EpubParseException) {
            return FictionResult.NetworkError("Cached AO3 EPUB unparseable: ${e.message}", e)
        }
        parsedCache[fictionId] = parsed
        return FictionResult.Success(parsed)
    }

    private fun epubFileFor(fictionId: String): File {
        val id = parseAo3Id(fictionId) ?: 0L
        return File(cacheDir, "$id.epub")
    }

    companion object {
        /**
         * Curated fandom tags for the v1 Browse picker. Six picks
         * chosen for breadth — heavyweight commercial fandoms
         * (Marvel, Star Wars, HP) plus the catch-all "Original Work"
         * and a long-form genre canon (Sherlock Holmes). Keep the
         * list short on purpose: AO3 has a million-plus tags and a
         * picker has to make a choice. Users wanting more
         * specificity get the tag-picker follow-up.
         *
         * Strings here are the AO3 tag canonicals (URL-encoded by
         * the API layer). Hyphens and ampersands are preserved
         * verbatim — those are part of the tag, not punctuation.
         */
        val CURATED_TAGS: List<String> = listOf(
            "Marvel Cinematic Universe",
            "Harry Potter - J. K. Rowling",
            "Star Wars - All Media Types",
            "Original Work",
            "Sherlock Holmes & Related Fandoms - All Media Types",
            "Good Omens (TV)",
        )

        /**
         * Default tag for the Popular / NewReleases tabs when the
         * user hasn't picked a fandom from the genre row. "Original
         * Work" is the broadest fandom-neutral tag — works tagged
         * here are mostly long-form prose written for AO3 directly
         * rather than fanfic of a specific media property, which
         * pairs well with the audiobook use case and avoids
         * pre-picking a fandom on the user's behalf.
         */
        const val DEFAULT_TAG: String = "Original Work"
    }
}

/** `ao3:12345` → `12345`; returns null on malformed input. */
private fun parseAo3Id(fictionId: String): Long? =
    fictionId.substringAfter("ao3:", missingDelimiterValue = "")
        .takeIf { it.isNotEmpty() }
        ?.toLongOrNull()

/** Compose chapter id = `${fictionId}::${spineIdx}` so chapter
 *  lookups can recover the spine index without a separate map.
 *  Mirrors the OutlineSource / GutenbergSource pattern. */
private fun chapterIdFor(fictionId: String, spineIndex: Int): String =
    "${fictionId}::${spineIndex}"

private fun chapterIndexFrom(chapterId: String): Int? =
    chapterId.substringAfterLast("::", missingDelimiterValue = "")
        .takeIf { it.isNotEmpty() }
        ?.toIntOrNull()

/**
 * Map one Atom feed to a storyvox ListPage. The Atom feed itself
 * doesn't advertise a `hasNext` — AO3 silently truncates at the
 * configured page count and returns an empty entries list past
 * the end. We treat any full-looking page (20 entries, AO3's
 * fixed page size) as having a next; a short page terminates.
 */
private fun Ao3AtomFeed.toListPage(page: Int): ListPage<FictionSummary> =
    ListPage(
        items = entries.map { it.toSummary() },
        page = page,
        hasNext = entries.size >= AO3_FEED_PAGE_SIZE,
    )

/** AO3's per-tag Atom feed is hardcoded to 20 entries per page. */
private const val AO3_FEED_PAGE_SIZE = 20

private fun Ao3FeedEntry.toSummary(): FictionSummary =
    FictionSummary(
        id = "ao3:$workId",
        sourceId = SourceIds.AO3,
        title = title.ifBlank { "AO3 work $workId" },
        author = authorDisplay,
        coverUrl = null, // AO3 has no per-work cover image API.
        description = summary?.stripTags(),
        tags = tags,
        // AO3 feeds don't carry a completion flag — works are
        // assumed ongoing until the user opens them (the EPUB's
        // metadata may carry "Status: Completed"). Conservative
        // default Ongoing — see [Ao3Source.fictionDetail] for the
        // same rationale.
        status = FictionStatus.ONGOING,
    )

/** Cheap HTML→plaintext for the AO3 summary blocks. The feed
 *  wraps summaries in `<p>` and sometimes inline `<a>`/`<em>`;
 *  the engine receives the visible text without the tag noise. */
private fun String.stripTags(): String =
    Regex("<[^>]+>").replace(this, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
