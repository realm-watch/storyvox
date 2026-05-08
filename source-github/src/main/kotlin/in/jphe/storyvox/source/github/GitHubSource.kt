package `in`.jphe.storyvox.source.github

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.source.github.manifest.BookManifest
import `in`.jphe.storyvox.source.github.manifest.ManifestChapter
import `in`.jphe.storyvox.source.github.manifest.ManifestParser
import `in`.jphe.storyvox.source.github.model.GhRepo
import `in`.jphe.storyvox.source.github.model.decodedText
import `in`.jphe.storyvox.source.github.net.GitHubApi
import `in`.jphe.storyvox.source.github.net.GitHubApiResult
import `in`.jphe.storyvox.source.github.registry.Registry
import `in`.jphe.storyvox.source.github.registry.RegistryEntry
import `in`.jphe.storyvox.source.github.registry.toSummary
import `in`.jphe.storyvox.source.github.render.MarkdownChapterRenderer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * GitHub [FictionSource]. Fully wired in step 3d-detail-and-chapter:
 *
 *  - **Browse** (popular/latestUpdates/byGenre/genres): backed by the
 *    curated [Registry] (step 3c).
 *  - **Detail** (`fictionDetail`): fetches `book.toml`, `storyvox.json`,
 *    `SUMMARY.md` from the repo + repo metadata, runs them through
 *    [ManifestParser] (step 3d-manifest), and maps to [FictionDetail].
 *    Falls back to repo `chapters/` or `src/` directory listings when
 *    no `SUMMARY.md` is present (the bare-repo path).
 *  - **Chapter** (`chapter`): fetches the file's base64 body from
 *    `/contents`, decodes, runs through [MarkdownChapterRenderer]
 *    (step 3d-markdown).
 *  - **Search**: deferred to step 3-search (spec sequence step 8).
 *  - **Auth-gated** (followsList, setFollowed): deferred to step 3f.
 *
 * Hilt binding lives in [`in`.jphe.storyvox.source.github.di
 * .GitHubBindings] — `@IntoMap @StringKey(SourceIds.GITHUB)`. Active
 * as of this PR; `addByUrl(github URL)` flows end-to-end through the
 * multi-source map (#35) → `sourceFor(SourceIds.GITHUB)` →
 * `fictionDetail` → `upsertDetail`.
 */
@Singleton
internal class GitHubSource @Inject constructor(
    private val api: GitHubApi,
    private val registry: Registry,
    private val markdownRenderer: MarkdownChapterRenderer,
) : FictionSource {

    override val id: String = SourceIds.GITHUB
    override val displayName: String = "GitHub"

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        registryPage(page) { entries ->
            entries.sortedByDescending { it.featured }
        }

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        registryPage(page) { entries ->
            entries.sortedByDescending { it.addedAt.orEmpty() }
        }

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        registryPage(page) { entries ->
            val needle = genre.trim().lowercase()
            if (needle.isBlank()) entries
            else entries.filter { it.tags.any { tag -> tag.equals(needle, ignoreCase = true) } }
        }

    override suspend fun genres(): FictionResult<List<String>> {
        return when (val r = registry.entries()) {
            is FictionResult.Success -> FictionResult.Success(
                r.value.flatMap { it.tags }
                    .map { it.lowercase() }
                    .distinct()
                    .sorted(),
            )
            is FictionResult.Failure -> r
        }
    }

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        // Compose the GitHub search query: pin to fiction-shaped topics
        // so we don't dredge generic repos, then append the user's
        // search term verbatim. GitHub topic OR-syntax is `topic:a
        // OR topic:b` — covers a few synonym tags at once. RR-shaped
        // SearchQuery filter fields (genres, tags, statuses,
        // requireWarnings, etc.) don't translate to GitHub today and
        // are ignored.
        //
        // The GitHub filter sheet (step 8c) composes its own
        // qualifier-laden query (stars:, language:, pushed:, sort:,
        // and possibly its own topic:) and stuffs it into
        // SearchQuery.term. Skip our default topic prefix when the
        // term already contains a `topic:` qualifier so we don't
        // double-up — the filter layer is more authoritative when
        // it's chosen to override.
        val term = query.term.trim()
        val gh = buildString {
            if (!term.contains("topic:", ignoreCase = true)) {
                append("(topic:fiction OR topic:fanfiction OR topic:webnovel)")
                if (term.isNotEmpty()) append(' ')
            }
            if (term.isNotEmpty()) append(term)
        }

        return when (val r = api.searchRepositories(gh, page = query.page)) {
            is GitHubApiResult.Success -> {
                val items = r.value.items.map { it.toFictionSummary() }
                FictionResult.Success(
                    ListPage(
                        items = items,
                        page = query.page,
                        // GitHub search caps at 1000 results across all
                        // pages; signal end-of-list when items < per_page
                        // OR we've reached the cap.
                        hasNext = items.isNotEmpty() && items.size >= 20 && query.page < 50,
                    ),
                )
            }
            is GitHubApiResult.NotFound -> FictionResult.Success(
                ListPage(items = emptyList(), page = query.page, hasNext = false),
            )
            is GitHubApiResult.RateLimited -> FictionResult.RateLimited(
                retryAfter = r.retryAfterSeconds?.let { it.toDuration(DurationUnit.SECONDS) },
            )
            is GitHubApiResult.HttpError -> FictionResult.NetworkError(
                message = "GitHub error ${r.code}: ${r.message}",
            )
            is GitHubApiResult.NetworkError -> FictionResult.NetworkError(
                message = "Could not reach GitHub",
                cause = r.cause,
            )
            is GitHubApiResult.ParseError -> FictionResult.NetworkError(
                message = "Malformed search response",
                cause = r.cause,
            )
        }
    }

    /**
     * Map a GitHub repo into the cross-source [FictionSummary]. Cover
     * URL is intentionally null — the manifest's storyvox.json.cover
     * lives in the repo content, not the API response, so search
     * results don't have it. The user opens the fiction → fictionDetail
     * resolves the manifest → the detail card gets the cover. Tags
     * fall back to GitHub topics; the manifest's storyvox.json.tags
     * (if any) overrides that on the detail page.
     */
    private fun GhRepo.toFictionSummary(): FictionSummary = FictionSummary(
        id = "${SourceIds.GITHUB}:${fullName.lowercase()}",
        sourceId = SourceIds.GITHUB,
        title = name,
        author = owner.login,
        coverUrl = null,
        description = description,
        tags = topics,
        status = if (archived) FictionStatus.COMPLETED else FictionStatus.ONGOING,
        chapterCount = null,
        rating = null,
    )

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val coords = parseFictionId(fictionId)
            ?: return FictionResult.NotFound(message = "Not a GitHub fiction id: $fictionId")
        val (owner, repo) = coords

        // Existence + metadata. NotFound here means the repo doesn't
        // exist; surface verbatim so the caller's add-by-URL flow can
        // tell the user.
        val ghRepo: GhRepo = when (val r = api.getRepo(owner, repo)) {
            is GitHubApiResult.Success -> r.value
            is GitHubApiResult.NotFound -> return FictionResult.NotFound(message = r.message)
            is GitHubApiResult.RateLimited -> return FictionResult.RateLimited(
                retryAfter = r.retryAfterSeconds?.let { it.toDuration(DurationUnit.SECONDS) },
            )
            is GitHubApiResult.HttpError -> return FictionResult.NetworkError(
                message = "GitHub error ${r.code}: ${r.message}",
            )
            is GitHubApiResult.NetworkError -> return FictionResult.NetworkError(
                message = "Could not reach GitHub",
                cause = r.cause,
            )
            is GitHubApiResult.ParseError -> return FictionResult.NetworkError(
                message = "Malformed repo response",
                cause = r.cause,
            )
        }

        val branch = ghRepo.defaultBranch

        // Manifest candidates. Each is best-effort: a 404 just means
        // the author didn't author that file. Anything else (rate
        // limit, network) is a hard failure — we propagate it via the
        // [OptionalText.failureOrNull] field so the caller short-
        // circuits with the right `FictionResult.Failure` variant.
        val bookTomlOpt = fetchOptionalText(owner, repo, "book.toml", branch)
        bookTomlOpt.failureOrNull?.let { return it }
        val storyvoxJsonOpt = fetchOptionalText(owner, repo, "storyvox.json", branch)
        storyvoxJsonOpt.failureOrNull?.let { return it }
        val srcDirGuess = guessSrcDir(bookTomlOpt.text)
        val summaryMdOpt = fetchOptionalText(owner, repo, "$srcDirGuess/SUMMARY.md", branch)
        summaryMdOpt.failureOrNull?.let { return it }

        val bookToml = bookTomlOpt.text
        val storyvoxJson = storyvoxJsonOpt.text
        val summaryMd = summaryMdOpt.text

        val bareRepoPaths = if (summaryMd.isNullOrBlank()) {
            listBareRepoPaths(owner, repo, branch, srcDirGuess)
        } else {
            emptyList()
        }

        val manifest = ManifestParser.parse(
            fictionId = fictionId,
            bookToml = bookToml,
            storyvoxJson = storyvoxJson,
            summaryMd = summaryMd,
            bareRepoPaths = bareRepoPaths,
        )

        return FictionResult.Success(toFictionDetail(fictionId, ghRepo, manifest))
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val coords = parseFictionId(fictionId)
            ?: return FictionResult.NotFound(message = "Not a GitHub fiction id: $fictionId")
        val (owner, repo) = coords
        // Chapter id format per spec line 141: `<fictionId>:<path>`.
        val path = chapterId.removePrefix("$fictionId:").trimStart('/')
        if (path.isEmpty() || path == chapterId) {
            return FictionResult.NotFound(message = "Malformed chapter id: $chapterId")
        }

        return when (val r = api.getContent(owner, repo, path)) {
            is GitHubApiResult.Success -> {
                val text = r.value.decodedText()
                    ?: return FictionResult.NetworkError(
                        message = "Chapter at $path was not a base64-encoded file",
                    )
                val info = ChapterInfo(
                    id = chapterId,
                    sourceChapterId = path,
                    index = 0, // caller sets ordering from FictionDetail.chapters
                    title = path.substringAfterLast('/').removeSuffix(".md"),
                )
                FictionResult.Success(markdownRenderer.render(info, text))
            }
            is GitHubApiResult.NotFound -> FictionResult.NotFound(message = r.message)
            is GitHubApiResult.RateLimited -> FictionResult.RateLimited(
                retryAfter = r.retryAfterSeconds?.let { it.toDuration(DurationUnit.SECONDS) },
            )
            is GitHubApiResult.HttpError -> FictionResult.NetworkError(
                message = "GitHub error ${r.code}: ${r.message}",
            )
            is GitHubApiResult.NetworkError -> FictionResult.NetworkError(
                message = "Could not reach GitHub",
                cause = r.cause,
            )
            is GitHubApiResult.ParseError -> FictionResult.NetworkError(
                message = "Malformed chapter response",
                cause = r.cause,
            )
        }
    }

    override suspend fun followsList(
        @Suppress("UNUSED_PARAMETER") page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        throw NotImplementedError(STEP_3F_AUTH)

    override suspend fun setFollowed(
        @Suppress("UNUSED_PARAMETER") fictionId: String,
        @Suppress("UNUSED_PARAMETER") followed: Boolean,
    ): FictionResult<Unit> =
        throw NotImplementedError(STEP_3F_AUTH)

    /**
     * Cheap-poll revision token: head commit SHA on the repo's default
     * branch. The poll worker compares against the previously-stored
     * token and skips the heavier `fictionDetail` round-trip when they
     * match. Step 9 in the GitHub-source spec.
     *
     * Two API calls per check (`getRepo` for `default_branch` then
     * `/commits?sha={branch}&per_page=1` for the head SHA), against a
     * full `fictionDetail` of repo + book.toml + storyvox.json +
     * SUMMARY.md (4-5 calls + parsing). Net win even if no skip-eligible
     * fictions exist yet, because the parsing alone is the dominant
     * cost.
     *
     * Failures (network, rate-limit, 404) come back as the equivalent
     * `FictionResult.Failure` variants; the worker treats those as
     * "fall back to the full path" rather than aborting the whole poll.
     */
    override suspend fun latestRevisionToken(fictionId: String): FictionResult<String?> {
        val coords = parseFictionId(fictionId)
            ?: return FictionResult.NotFound(message = "Not a GitHub fiction id: $fictionId")
        val (owner, repo) = coords

        val branch = when (val r = api.getRepo(owner, repo)) {
            is GitHubApiResult.Success -> r.value.defaultBranch
            is GitHubApiResult.NotFound -> return FictionResult.NotFound(message = r.message)
            is GitHubApiResult.RateLimited -> return FictionResult.RateLimited(
                retryAfter = r.retryAfterSeconds?.let { it.toDuration(DurationUnit.SECONDS) },
            )
            is GitHubApiResult.NetworkError -> return FictionResult.NetworkError(
                message = "Could not reach GitHub",
                cause = r.cause,
            )
            is GitHubApiResult.HttpError -> return FictionResult.NetworkError(
                message = "GitHub error ${r.code}: ${r.message}",
            )
            is GitHubApiResult.ParseError -> return FictionResult.NetworkError(
                message = "Malformed repo response",
                cause = r.cause,
            )
        }

        return when (val r = api.getHeadCommit(owner, repo, branch)) {
            is GitHubApiResult.Success -> {
                // Empty list means the branch has no commits yet — treat
                // as "no revision known", caller falls back to the full
                // path. A real repo always has at least one commit.
                FictionResult.Success(r.value.firstOrNull()?.sha)
            }
            is GitHubApiResult.NotFound -> FictionResult.NotFound(message = r.message)
            is GitHubApiResult.RateLimited -> FictionResult.RateLimited(
                retryAfter = r.retryAfterSeconds?.let { it.toDuration(DurationUnit.SECONDS) },
            )
            is GitHubApiResult.NetworkError -> FictionResult.NetworkError(
                message = "Could not reach GitHub",
                cause = r.cause,
            )
            is GitHubApiResult.HttpError -> FictionResult.NetworkError(
                message = "GitHub error ${r.code}: ${r.message}",
            )
            is GitHubApiResult.ParseError -> FictionResult.NetworkError(
                message = "Malformed commits response",
                cause = r.cause,
            )
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────

    /** Result wrapper for a candidate manifest file fetch. */
    private data class OptionalText(
        val text: String?,
        val failureOrNull: FictionResult.Failure?,
    )

    /**
     * Fetch a file's text body, treating 404 as "absent" (returns
     * null text + null failure). Other failures populate
     * [OptionalText.failureOrNull] so the caller can short-circuit.
     */
    private suspend fun fetchOptionalText(
        owner: String,
        repo: String,
        path: String,
        ref: String,
    ): OptionalText = when (val r = api.getContent(owner, repo, path, ref)) {
        is GitHubApiResult.Success -> OptionalText(r.value.decodedText(), null)
        is GitHubApiResult.NotFound -> OptionalText(null, null) // file just isn't there
        is GitHubApiResult.RateLimited -> OptionalText(
            null,
            FictionResult.RateLimited(
                retryAfter = r.retryAfterSeconds?.let { it.toDuration(DurationUnit.SECONDS) },
            ),
        )
        is GitHubApiResult.HttpError -> OptionalText(
            null,
            FictionResult.NetworkError(message = "GitHub error ${r.code}: ${r.message}"),
        )
        is GitHubApiResult.NetworkError -> OptionalText(
            null,
            FictionResult.NetworkError(message = "Could not reach GitHub", cause = r.cause),
        )
        is GitHubApiResult.ParseError -> OptionalText(
            null,
            FictionResult.NetworkError(message = "Malformed file response", cause = r.cause),
        )
    }

    /**
     * Best-effort `[book].src` extraction for the SUMMARY.md path
     * lookup. Re-parsed from book.toml here rather than threading it
     * through ManifestParser because we need it before the parser
     * runs (we feed it the SUMMARY contents as one of its inputs).
     * Default to `src` per mdbook convention.
     */
    private fun guessSrcDir(bookToml: String?): String {
        if (bookToml == null) return "src"
        val m = Regex("""(?m)^\s*src\s*=\s*"([^"]*)"\s*$""").find(bookToml) ?: return "src"
        return m.groupValues[1].ifBlank { "src" }
    }

    /**
     * Listing for the bare-repo fallback: try `chapters/` first, then
     * the manifest-claimed src dir. Returns an empty list on any
     * failure — bare-repo is itself a fallback, so a missing dir just
     * means "nothing to fall back to."
     */
    private suspend fun listBareRepoPaths(
        owner: String,
        repo: String,
        ref: String,
        srcDir: String,
    ): List<String> {
        val candidates = listOf("chapters", srcDir).distinct()
        for (dir in candidates) {
            val r = api.getContents(owner, repo, dir, ref)
            if (r is GitHubApiResult.Success) {
                val files = r.value.filter { it.type == "file" }.map { it.path }
                if (files.isNotEmpty()) return files
            }
        }
        return emptyList()
    }

    private fun toFictionDetail(
        fictionId: String,
        repo: GhRepo,
        manifest: BookManifest,
    ): FictionDetail = FictionDetail(
        summary = FictionSummary(
            id = fictionId,
            sourceId = SourceIds.GITHUB,
            title = manifest.title,
            author = manifest.author,
            // Cover URL is repo-relative in the manifest; resolve
            // against raw.githubusercontent for direct image fetch.
            coverUrl = manifest.coverPath?.let { rawUrl(repo, it) },
            description = manifest.description ?: repo.description,
            tags = manifest.tags.ifEmpty { repo.topics },
            status = parseStatus(manifest.status, repo),
            chapterCount = manifest.chapters.size,
            rating = null,
        ),
        chapters = manifest.chapters.toChapterInfos(fictionId),
        genres = manifest.tags,
        wordCount = null,
        views = null,
        followers = repo.stars.takeIf { it > 0 },
        lastUpdatedAt = null,
        authorId = repo.owner.login,
    )

    private fun List<ManifestChapter>.toChapterInfos(fictionId: String): List<ChapterInfo> =
        mapIndexed { index, ch ->
            ChapterInfo(
                id = "$fictionId:${ch.path}",
                sourceChapterId = ch.path,
                index = index,
                title = ch.title,
            )
        }

    private fun rawUrl(repo: GhRepo, path: String): String {
        val cleanPath = path.trimStart('/')
        return "https://raw.githubusercontent.com/${repo.fullName}/${repo.defaultBranch}/$cleanPath"
    }

    private fun parseStatus(raw: String?, repo: GhRepo): FictionStatus = when {
        raw?.equals("completed", ignoreCase = true) == true -> FictionStatus.COMPLETED
        raw?.equals("hiatus", ignoreCase = true) == true -> FictionStatus.HIATUS
        raw?.equals("dropped", ignoreCase = true) == true -> FictionStatus.DROPPED
        repo.archived -> FictionStatus.COMPLETED
        else -> FictionStatus.ONGOING
    }

    /** `github:owner/repo` → `(owner, repo)` or null. */
    private fun parseFictionId(fictionId: String): Pair<String, String>? {
        val stripped = fictionId.removePrefix("${SourceIds.GITHUB}:")
        if (stripped == fictionId) return null
        val slash = stripped.indexOf('/')
        if (slash <= 0 || slash == stripped.length - 1) return null
        return stripped.substring(0, slash) to stripped.substring(slash + 1)
    }

    private suspend fun registryPage(
        page: Int,
        transform: (List<RegistryEntry>) -> List<RegistryEntry>,
    ): FictionResult<ListPage<FictionSummary>> {
        if (page > 1) {
            return FictionResult.Success(
                ListPage(items = emptyList(), page = page, hasNext = false),
            )
        }
        return when (val r = registry.entries()) {
            is FictionResult.Success -> FictionResult.Success(
                ListPage(
                    items = transform(r.value).map { it.toSummary() },
                    page = 1,
                    hasNext = false,
                ),
            )
            is FictionResult.Failure -> r
        }
    }

    private companion object {
        const val STEP_3F_AUTH = "GitHub source auth-gated calls not implemented yet — lands in step 3f (optional PAT support)"
    }
}
