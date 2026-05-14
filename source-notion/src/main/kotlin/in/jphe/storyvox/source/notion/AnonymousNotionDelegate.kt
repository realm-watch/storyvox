package `in`.jphe.storyvox.source.notion

import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.source.notion.config.NotionConfigState
import `in`.jphe.storyvox.source.notion.config.NotionDefaults
import `in`.jphe.storyvox.source.notion.net.NotionChunkResponse
import `in`.jphe.storyvox.source.notion.net.NotionRecordMap
import `in`.jphe.storyvox.source.notion.net.NotionUnofficialApi
import `in`.jphe.storyvox.source.notion.net.block
import `in`.jphe.storyvox.source.notion.net.contentIds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #393 — anonymous-mode read path.
 *
 * **Design (v0.5.25)**: Browse → Notion surfaces a **single tile** for
 * the configured root page (TechEmpower.org by default). The fiction's
 * chapter list is a structural overview of the top-level sections —
 * one chapter per logical area of the site, not one fiction per page.
 *
 * For TechEmpower the chapters are:
 *  1. **Guides** — content of the root page itself ("Welcome to
 *     TechEmpower.org" + the bridge text linking to each individual
 *     guide). Internal headings inside the root page split into
 *     sub-headings within the chapter body, preserved as `<h2>`/`<h3>`
 *     in the HTML view; TTS narrates them inline. The 8 individual
 *     guide pages stay reachable through their authored links inside
 *     the body, but they aren't *separate fictions* — keeping them as
 *     one chapter matches JP's "single fiction, multi-chapter" call.
 *  2. **Resources** — overview of the Resources database. Renders as
 *     a flat HTML list of row titles + brief descriptions (queried via
 *     `queryCollection` once per fictionDetail). The full database has
 *     ~80 rows; rendering all of them keeps the chapter narratable
 *     without forcing the user to drill into each row.
 *  3. **About** — content of the About page.
 *  4. **Donate** — content of the Donate page.
 *
 * Each chapter's body is built by walking the underlying Notion page's
 * blocks through [renderPageBody], which respects `alive:false`
 * tombstones, projects `header`/`sub_header`/`text`/`bulleted_list`
 * to HTML, and skips embeds.
 *
 * The chapter map is keyed by section name (stable across rebuilds);
 * configurable in [NotionDefaults.techempowerChapters] so a future
 * iteration can extend the surface (Non-discrimination policy, etc.)
 * without rewriting the delegate.
 */
@Singleton
internal class AnonymousNotionDelegate @Inject constructor(
    private val api: NotionUnofficialApi,
) {

    /**
     * The single Browse tile. Returns one [FictionSummary] for the
     * configured root page; page > 1 returns empty (no pagination).
     * The tile is built from the root page's loadPageChunk response
     * (title + cover image come from the page block itself).
     */
    suspend fun popular(
        state: NotionConfigState,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> {
        if (page > 1) {
            return FictionResult.Success(ListPage(items = emptyList(), page = page, hasNext = false))
        }
        val rootResult = api.loadPageChunk(state.rootPageId)
        val root = when (rootResult) {
            is FictionResult.Success -> rootResult.value
            is FictionResult.Failure -> return rootResult
        }
        val tile = buildRootSummary(state, root)
            ?: return FictionResult.NotFound(
                "Notion root page has no readable title: ${state.rootPageId}",
            )
        return FictionResult.Success(
            ListPage(items = listOf(tile), page = 1, hasNext = false),
        )
    }

    /**
     * Fiction detail for the single Browse tile. Builds the chapter
     * list from [NotionDefaults.techempowerChapters] (or a derived
     * generic chapter list for non-TechEmpower root pages — out of
     * scope for v0.5.25; for now we treat any root as TechEmpower-
     * shaped). We do *not* fetch each chapter's body here — only the
     * titles and chapter ids. The body fetch happens lazily in
     * [chapter] when the user taps a chapter.
     */
    suspend fun fictionDetail(
        state: NotionConfigState,
        fictionId: String,
    ): FictionResult<FictionDetail> {
        val pageId = fictionId.toPageId()
            ?: return FictionResult.NotFound("Notion fiction id not recognized: $fictionId")
        // Validate that the fiction id matches the configured root —
        // anonymous mode only exposes one fiction at a time.
        val rootResult = api.loadPageChunk(state.rootPageId)
        val root = when (rootResult) {
            is FictionResult.Success -> rootResult.value
            is FictionResult.Failure -> return rootResult
        }
        val tile = buildRootSummary(state, root) ?: FictionSummary(
            id = fictionId,
            sourceId = SourceIds.NOTION,
            title = "TechEmpower.org",
            author = "TechEmpower",
            description = null,
            status = FictionStatus.ONGOING,
        )
        val chapterSpecs = chapterSpecsFor(state.rootPageId, pageId)
        val chapters = chapterSpecs.mapIndexed { idx, spec ->
            ChapterInfo(
                id = chapterIdFor(fictionId, idx),
                sourceChapterId = "section-$idx",
                index = idx,
                title = spec.title,
                publishedAt = null,
            )
        }
        return FictionResult.Success(
            FictionDetail(
                summary = tile.copy(chapterCount = chapters.size),
                chapters = chapters,
            ),
        )
    }

    /**
     * Render one chapter's body. Looks up the chapter's source page id
     * in [chapterSpecsFor], loads its blocks, and projects them to
     * HTML + plain text. The "Resources" chapter has special handling
     * because its underlying block is a collection (not a page) — we
     * call queryCollection and render the row titles as a list.
     */
    suspend fun chapter(
        state: NotionConfigState,
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val pageId = fictionId.toPageId()
            ?: return FictionResult.NotFound("Notion fiction id not recognized: $fictionId")
        val sectionIndex = chapterId.substringAfterLast("::section-", "")
            .toIntOrNull()
            ?: return FictionResult.NotFound("Notion chapter id not recognized: $chapterId")
        val specs = chapterSpecsFor(state.rootPageId, pageId)
        val spec = specs.getOrNull(sectionIndex)
            ?: return FictionResult.NotFound(
                "Section $sectionIndex not found for Notion fiction $pageId",
            )
        val info = ChapterInfo(
            id = chapterId,
            sourceChapterId = "section-$sectionIndex",
            index = sectionIndex,
            title = spec.title,
        )
        return when (spec) {
            is ChapterSpec.Page -> renderPageChapter(info, spec.pageId)
            is ChapterSpec.Collection -> renderCollectionChapter(info, spec)
        }
    }

    /**
     * Search — the anonymous-mode tile is single-fiction, so search
     * either matches the one tile's title/description or returns
     * nothing. Keeps the source consistent with the rest of Browse
     * (search returns a ListPage; the empty case is fine).
     */
    suspend fun search(
        state: NotionConfigState,
        term: String,
    ): FictionResult<ListPage<FictionSummary>> {
        val rootResult = api.loadPageChunk(state.rootPageId)
        val root = when (rootResult) {
            is FictionResult.Success -> rootResult.value
            is FictionResult.Failure -> return rootResult
        }
        val tile = buildRootSummary(state, root)
            ?: return FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))
        val termLc = term.trim().lowercase()
        val matches = termLc.isEmpty() ||
            tile.title.lowercase().contains(termLc) ||
            tile.description?.lowercase()?.contains(termLc) == true
        return FictionResult.Success(
            ListPage(
                items = if (matches) listOf(tile) else emptyList(),
                page = 1,
                hasNext = false,
            ),
        )
    }

    // ─── internals ────────────────────────────────────────────────────

    /** Build the FictionSummary for the configured root page. Used in
     *  both [popular] and [fictionDetail]. Null when the recordMap
     *  doesn't contain a usable title (signals "this page isn't shared
     *  publicly" or "Notion returned an empty chunk"). */
    private fun buildRootSummary(
        state: NotionConfigState,
        root: NotionChunkResponse,
    ): FictionSummary? {
        val block = root.recordMap.findBlock(state.rootPageId) ?: return null
        val title = readTitle(block) ?: return null
        val description = readDescriptionFromBlocks(root.recordMap, block)
        return FictionSummary(
            id = notionFictionId(state.rootPageId),
            sourceId = SourceIds.NOTION,
            title = title,
            author = "TechEmpower",
            description = description,
            coverUrl = readCoverUrl(block),
            tags = emptyList(),
            status = FictionStatus.ONGOING,
        )
    }

    /** Render a chapter that points at a Notion page block. */
    private suspend fun renderPageChapter(
        info: ChapterInfo,
        pageId: String,
    ): FictionResult<ChapterContent> {
        val chunkResult = api.loadPageChunk(pageId)
        val chunk = when (chunkResult) {
            is FictionResult.Success -> chunkResult.value
            is FictionResult.Failure -> return chunkResult
        }
        val pageBlock = chunk.recordMap.findBlock(pageId)
            ?: return FictionResult.NotFound("Notion page $pageId not in recordMap")
        val (html, plain) = renderPageBody(chunk.recordMap, pageBlock)
        return FictionResult.Success(
            ChapterContent(
                info = info,
                htmlBody = html,
                plainBody = plain,
            ),
        )
    }

    /** Render a chapter that points at a Notion collection (database).
     *  Queries the collection's first view and emits an HTML list of
     *  row titles. */
    private suspend fun renderCollectionChapter(
        info: ChapterInfo,
        spec: ChapterSpec.Collection,
    ): FictionResult<ChapterContent> {
        // Step 1: load the collection_view block to discover its
        // collection_id + view_ids. (Required because the unofficial
        // queryCollection needs both ids; the configured spec only has
        // the block id.)
        val chunkResult = api.loadPageChunk(spec.blockId)
        val chunk = when (chunkResult) {
            is FictionResult.Success -> chunkResult.value
            is FictionResult.Failure -> return chunkResult
        }
        val viewBlock = chunk.recordMap.findBlock(spec.blockId)
            ?: return FictionResult.NotFound("Collection block ${spec.blockId} not in recordMap")
        val collectionId = viewBlock.collectionId()
            ?: return FictionResult.NotFound("Collection ${spec.blockId} has no collection_id")
        val viewId = viewBlock.firstViewId()
            ?: return FictionResult.NotFound("Collection ${spec.blockId} has no view_ids")

        // Step 2: query the rows.
        val rowsResult = api.queryCollection(collectionId, viewId, limit = 200)
        val rowsResp = when (rowsResult) {
            is FictionResult.Success -> rowsResult.value
            is FictionResult.Failure -> return rowsResult
        }
        val rowTitles = collectRowTitles(rowsResp.recordMap)
        return FictionResult.Success(
            ChapterContent(
                info = info,
                htmlBody = renderRowsAsHtml(spec.title, rowTitles),
                plainBody = renderRowsAsPlain(spec.title, rowTitles),
            ),
        )
    }
}

// ─── chapter spec ─────────────────────────────────────────────────────

/**
 * One chapter's structural description. Either backed by a single
 * Notion page or by a collection (database) — chapter rendering
 * branches on the variant.
 */
internal sealed class ChapterSpec {
    abstract val title: String

    data class Page(override val title: String, val pageId: String) : ChapterSpec()
    data class Collection(override val title: String, val blockId: String) : ChapterSpec()
}

/**
 * Resolve the chapter list for a given (rootPageId, fictionId) pair.
 *
 * Today we only know one root page — TechEmpower — so any fiction id
 * whose page id matches the TechEmpower root uses the hand-curated
 * chapter list from [NotionDefaults]. A future iteration can support
 * arbitrary roots by walking the root page's content[] and projecting
 * each child page/collection into a chapter automatically.
 *
 * Returns an empty list when the fiction id doesn't match the root —
 * which then surfaces as "Section N not found" at the chapter call
 * site, the right shape for "this fiction doesn't exist anymore".
 */
internal fun chapterSpecsFor(rootPageId: String, fictionPageId: String): List<ChapterSpec> {
    val compactRoot = rootPageId.replace("-", "")
    val compactFiction = fictionPageId.replace("-", "")
    if (compactFiction != compactRoot) return emptyList()
    return if (compactRoot == NotionDefaults.TECHEMPOWER_ROOT_PAGE_ID) {
        NotionDefaults.techempowerChapters
    } else {
        // Generic root: one chapter that renders the whole root page.
        // Reasonable fallback for arbitrary public Notion pages; the
        // chapter title falls back to the page's own title.
        listOf(ChapterSpec.Page(title = "Contents", pageId = compactRoot))
    }
}

// ─── recordMap helpers ────────────────────────────────────────────────

/** Find a block in a recordMap by id. Accepts hyphenated or compact
 *  ids and tries both forms (recordMap keys are hyphenated; storyvox's
 *  internal id is compact). */
internal fun NotionRecordMap.findBlock(rawId: String): JsonObject? {
    val hyphenated = NotionUnofficialApi.hyphenatePageId(rawId)
    val direct = block[hyphenated] ?: block[rawId]
    return direct?.block()
}

/** Read the block type discriminator. */
internal fun JsonObject.blockType(): String? =
    (this["type"] as? JsonPrimitive)?.contentOrNull

/** Read a Notion page block's title from its `properties.title` array. */
internal fun readTitle(block: JsonObject): String? {
    val props = block["properties"] as? JsonObject ?: return null
    val titleArr = props["title"] as? JsonArray ?: return null
    return joinDecorationArray(titleArr).ifBlank { null }
}

/** Pull a page block's cover URL from `format.page_cover`. */
internal fun readCoverUrl(block: JsonObject): String? {
    val fmt = block["format"] as? JsonObject ?: return null
    val cover = (fmt["page_cover"] as? JsonPrimitive)?.contentOrNull
    return cover?.takeIf { it.isNotBlank() }
}

/**
 * Read a one-line description for a page from the recordMap. Walks
 * the first few text-bearing blocks under the page to find one that
 * isn't whitespace; returns null if none exists.
 */
internal fun readDescriptionFromBlocks(
    rm: NotionRecordMap,
    pageBlock: JsonObject,
): String? {
    for (childId in pageBlock.contentIds().take(5)) {
        val child = rm.findBlock(childId) ?: continue
        val type = child.blockType()
        // Skip embedded subpages / collection_views / dividers in the
        // search for a description — they don't carry narratable text.
        if (type != "text" && type != "callout" && type != "quote") continue
        val text = plainTextOfBlock(child)
        if (text.isNotBlank()) return text.take(280)
    }
    return null
}

/** Pull collection_view's underlying collection id. */
internal fun JsonObject.collectionId(): String? =
    (this["collection_id"] as? JsonPrimitive)?.contentOrNull

/** Pull the first view id from a collection_view's view_ids array. */
internal fun JsonObject.firstViewId(): String? {
    val arr = this["view_ids"] as? JsonArray ?: return null
    val first = arr.firstOrNull() as? JsonPrimitive ?: return null
    return first.contentOrNull
}

/**
 * Concatenate a Notion decoration array into plain text. Each entry is
 * `[plain_text, decoration[]?]`; we take element 0.
 */
internal fun joinDecorationArray(arr: JsonArray): String {
    val sb = StringBuilder()
    for (entry in arr) {
        val inner = entry as? JsonArray ?: continue
        val first = inner.firstOrNull() as? JsonPrimitive ?: continue
        val s = first.contentOrNull ?: continue
        sb.append(s)
    }
    return sb.toString()
}

/**
 * Pull a block's plain-text content. For text/header/quote/callout
 * blocks the content lives at `properties.title` (Notion's name for
 * the inline text of any block, regardless of type — confusing but
 * stable). For other blocks we return "".
 */
internal fun plainTextOfBlock(block: JsonObject): String {
    val props = block["properties"] as? JsonObject ?: return ""
    val title = props["title"] as? JsonArray ?: return ""
    return joinDecorationArray(title)
}

/** Render a Notion unofficial-API block to HTML. Mirrors the official
 *  toHtml() but maps the v3 block types (`header`, `sub_header`,
 *  `sub_sub_header`, `text`, `bulleted_list`, `numbered_list`, ...).
 *
 *  Unlike the old per-chapter split, here we emit `<h1>` for `header`
 *  too — the single-fiction model preserves internal heading_1s as
 *  inline section headers in the chapter body. */
internal fun blockToHtml(rm: NotionRecordMap, block: JsonObject): String {
    val type = block.blockType() ?: return ""
    val raw = plainTextOfBlock(block)
    val text = htmlEscape(raw)
    return when (type) {
        "header" -> if (text.isBlank()) "" else "<h1>$text</h1>"
        "sub_header" -> if (text.isBlank()) "" else "<h2>$text</h2>"
        "sub_sub_header" -> if (text.isBlank()) "" else "<h3>$text</h3>"
        "header_4" -> if (text.isBlank()) "" else "<h4>$text</h4>"
        "text" -> if (text.isBlank()) "" else "<p>$text</p>"
        "bulleted_list" -> if (text.isBlank()) "" else "<li>$text</li>"
        "numbered_list" -> if (text.isBlank()) "" else "<li>$text</li>"
        "quote" -> if (text.isBlank()) "" else "<blockquote>$text</blockquote>"
        "callout" -> if (text.isBlank()) "" else "<aside>$text</aside>"
        "code" -> if (text.isBlank()) "" else "<pre><code>$text</code></pre>"
        "divider" -> "<hr/>"
        "to_do" -> if (text.isBlank()) "" else "<p>$text</p>"
        "toggle" -> if (text.isBlank()) "" else "<p>$text</p>"
        // Embedded page links (child pages, mentions) — emit a
        // bridge paragraph so the chapter narrates "See also: <Title>"
        // rather than going silent on the page's internal navigation.
        // We can't render the linked content here (would balloon the
        // chapter), but a visible reference is better than nothing.
        "page" -> {
            val pageTitle = htmlEscape(readTitle(block).orEmpty())
            if (pageTitle.isBlank()) "" else "<p><strong>$pageTitle</strong></p>"
        }
        else -> ""
    }
}

/** Plain-text projection of a block, for TTS. Mirrors [blockToHtml]
 *  but strips markup; headings are read aloud as plain text. */
internal fun blockToPlain(block: JsonObject): String {
    val type = block.blockType() ?: return ""
    return when (type) {
        "divider" -> ""
        "page" -> readTitle(block).orEmpty()
        else -> plainTextOfBlock(block)
    }
}

/**
 * Render a single page's content as one chapter body. Returns a pair
 * (htmlBody, plainBody). All `alive:false` tombstones are filtered.
 * Page blocks embedded in the content (sub-pages) render as bridge
 * paragraphs ("<strong>Sub-page title</strong>") rather than being
 * expanded inline.
 */
internal fun renderPageBody(
    rm: NotionRecordMap,
    pageBlock: JsonObject,
): Pair<String, String> {
    val html = StringBuilder()
    val plain = StringBuilder()
    for (id in pageBlock.contentIds()) {
        val block = rm.findBlock(id) ?: continue
        val alive = (block["alive"] as? JsonPrimitive)?.booleanOrNull
        if (alive == false) continue
        val htmlPart = blockToHtml(rm, block)
        if (htmlPart.isNotEmpty()) {
            if (html.isNotEmpty()) html.append('\n')
            html.append(htmlPart)
        }
        val plainPart = blockToPlain(block)
        if (plainPart.isNotEmpty()) {
            if (plain.isNotEmpty()) plain.append("\n\n")
            plain.append(plainPart)
        }
    }
    return html.toString().trim() to plain.toString().trim()
}

/**
 * Pull row titles out of a queryCollection recordMap. Every `page`
 * block in the recordMap is a row; we project to (id, title) and
 * filter to titled rows.
 */
internal fun collectRowTitles(rm: NotionRecordMap): List<String> {
    val out = mutableListOf<String>()
    for ((_, env) in rm.block) {
        val block = env.block() ?: continue
        if (block.blockType() != "page") continue
        val alive = (block["alive"] as? JsonPrimitive)?.booleanOrNull
        if (alive == false) continue
        val title = readTitle(block) ?: continue
        out.add(title)
    }
    out.sort()
    return out
}

/** HTML rendering of a collection chapter — header + ordered list of
 *  row titles. */
internal fun renderRowsAsHtml(chapterTitle: String, titles: List<String>): String {
    if (titles.isEmpty()) {
        return "<p>${htmlEscape(chapterTitle)} is empty.</p>"
    }
    val sb = StringBuilder()
    sb.append("<p>")
    sb.append(htmlEscape("TechEmpower's $chapterTitle database has ${titles.size} entries:"))
    sb.append("</p>\n<ul>\n")
    for (title in titles) {
        sb.append("<li>").append(htmlEscape(title)).append("</li>\n")
    }
    sb.append("</ul>")
    return sb.toString()
}

/** Plain-text rendering of a collection chapter — short intro + a
 *  newline-separated list of titles. Reads aloud as a clean list. */
internal fun renderRowsAsPlain(chapterTitle: String, titles: List<String>): String {
    if (titles.isEmpty()) return "$chapterTitle is empty."
    val intro = "TechEmpower's $chapterTitle database has ${titles.size} entries."
    return intro + "\n\n" + titles.joinToString("\n")
}
