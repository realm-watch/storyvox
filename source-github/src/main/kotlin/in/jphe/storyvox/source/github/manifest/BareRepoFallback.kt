package `in`.jphe.storyvox.source.github.manifest

/**
 * Last-resort chapter list when neither `book.toml` + `SUMMARY.md`
 * nor an explicit author manifest is present. Picks markdown files
 * from a `chapters/` or `src/` directory whose filename matches
 * `^\d+[-_].*\.md$`, sorts by the leading number, and uses the
 * filename stem as the chapter title.
 *
 * Example match table:
 *
 * | Path                      | Match | Index | Title-from-stem        |
 * |---------------------------|-------|-------|-------------------------|
 * | chapters/01-intro.md      | yes   | 1     | "intro"                 |
 * | chapters/02_brass-gate.md | yes   | 2     | "brass gate"            |
 * | chapters/foo.md           | no    | —     | —                       |
 * | src/03-fall.md            | yes   | 3     | "fall"                  |
 * | src/index.md              | no    | —     | —                       |
 *
 * Caller can supply a `headingResolver` that reads a file's
 * `# Heading` first line to override the stem-derived title; this
 * keeps the parser pure (no IO) while still allowing 3d-detail to
 * call `getContent` per chapter for the prettier title.
 */
internal object BareRepoFallback {

    private val NUMBERED_MD = Regex("""^(\d+)[-_](.+)\.md$""", RegexOption.IGNORE_CASE)

    /**
     * @param paths repo-relative file paths (from a directory tree
     *   listing — typically `chapters/` or `src/`).
     * @param headingResolver optional reader that returns the
     *   `# Heading` text of a chapter file, or null if unavailable.
     *   When null, falls back to the kebab-case-to-title-case stem.
     */
    fun chaptersFrom(
        paths: List<String>,
        headingResolver: ((path: String) -> String?)? = null,
    ): List<ManifestChapter> {
        return paths
            .mapNotNull { path ->
                val filename = path.substringAfterLast('/')
                val m = NUMBERED_MD.matchEntire(filename) ?: return@mapNotNull null
                val index = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val stem = m.groupValues[2]
                Triple(index, path, stem)
            }
            .sortedBy { it.first }
            .map { (_, path, stem) ->
                val resolved = headingResolver?.invoke(path)?.takeIf { it.isNotBlank() }
                val title = resolved ?: stem.replace('-', ' ').replace('_', ' ').titleCase()
                ManifestChapter(title = title, path = path)
            }
    }

    /** Repo name → human title. `the-archmage-coefficient` → `The Archmage Coefficient`. */
    fun titleFromRepoName(repo: String): String =
        repo.replace('-', ' ').replace('_', ' ').titleCase()

    private fun String.titleCase(): String =
        split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                word[0].uppercaseChar() + word.substring(1)
            }
}
