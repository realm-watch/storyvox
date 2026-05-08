package `in`.jphe.storyvox.source.github.manifest

/**
 * Top-level manifest assembler. Step 3d-detail-and-chapter will pass
 * the raw bytes (or null when absent) for each candidate file plus
 * the bare-repo file listing; this assembler merges them per the
 * priority order documented on [BookManifest].
 *
 * Inputs are all optional — a bare repo with only numbered chapter
 * files still yields a valid (if minimal) manifest. The fictionId
 * (`github:owner/repo`) provides the fallback title and author.
 */
internal object ManifestParser {

    /**
     * @param fictionId stable id of the form `github:owner/repo`. Used to
     *   derive fallback `title` (from repo) and `author` (from owner)
     *   when manifests are absent.
     * @param bookToml raw bytes of `book.toml` at repo root, or null.
     * @param storyvoxJson raw bytes of `storyvox.json` at repo root, or null.
     * @param summaryMd raw bytes of `SUMMARY.md` (under `book.toml`'s `src`
     *   directory, default `src`), or null.
     * @param bareRepoPaths repo-relative file paths considered for the bare-
     *   repo fallback. Only consulted when [summaryMd] is null or yields
     *   no chapters.
     * @param headingResolver optional `# Heading` extractor — see
     *   [BareRepoFallback.chaptersFrom].
     */
    fun parse(
        fictionId: String,
        bookToml: String? = null,
        storyvoxJson: String? = null,
        summaryMd: String? = null,
        bareRepoPaths: List<String> = emptyList(),
        headingResolver: ((path: String) -> String?)? = null,
    ): BookManifest {
        val (owner, repo) = splitFictionId(fictionId)
        val toml = bookToml?.let { BookTomlParser.parse(it) } ?: BookToml()
        val sv = storyvoxJson?.let { StoryvoxJsonParser.parse(it) }

        val title = toml.title?.takeIf { it.isNotBlank() }
            ?: BareRepoFallback.titleFromRepoName(repo)
        val author = toml.authors.firstOrNull()?.takeIf { it.isNotBlank() } ?: owner
        val srcDir = toml.src?.takeIf { it.isNotBlank() } ?: "src"

        val summaryChapters = summaryMd?.let { SummaryMdParser.parse(it) }.orEmpty()
        val chapters = if (summaryChapters.isNotEmpty()) {
            summaryChapters
        } else {
            BareRepoFallback.chaptersFrom(bareRepoPaths, headingResolver)
        }

        return BookManifest(
            title = title,
            author = author,
            description = toml.description,
            coverPath = sv?.cover,
            tags = sv?.tags.orEmpty(),
            status = sv?.status,
            language = toml.language,
            srcDir = srcDir,
            chapters = chapters,
            narratorVoiceId = sv?.narratorVoiceId,
            honeypotSelectors = sv?.honeypotSelectors.orEmpty(),
        )
    }

    /** `github:owner/repo` → `(owner, repo)`. Falls back to `("unknown", id)`
     *  if the id doesn't match the expected shape (defensive — caller is
     *  expected to pass a valid id). */
    private fun splitFictionId(id: String): Pair<String, String> {
        val stripped = id.removePrefix("github:")
        val slash = stripped.indexOf('/')
        if (slash <= 0 || slash == stripped.length - 1) return "unknown" to stripped
        return stripped.substring(0, slash) to stripped.substring(slash + 1)
    }
}
