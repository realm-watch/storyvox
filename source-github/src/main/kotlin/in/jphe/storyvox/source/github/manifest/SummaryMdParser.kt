package `in`.jphe.storyvox.source.github.manifest

/**
 * Parses mdbook's `SUMMARY.md` to extract the chapter list. The
 * mdbook standard is:
 *
 * ```markdown
 * # Summary
 * - [Chapter Title](path/to/chapter.md)
 * - [Another One](path/to/other.md)
 * ```
 *
 * Indented entries denote subsections — we treat them as chapters
 * for now; storyvox doesn't yet have a part/section UI.
 *
 * Lines that don't match the bullet-link pattern are skipped. The
 * `# Summary` heading itself is ignored; same for any prefix
 * paragraph headings the author may add.
 */
internal object SummaryMdParser {

    private val LINK_PATTERN = Regex("""^\s*[-*]\s*\[(.+?)\]\(([^)]+)\)\s*$""")

    fun parse(raw: String): List<ManifestChapter> {
        val out = mutableListOf<ManifestChapter>()
        for (line in raw.lineSequence()) {
            val m = LINK_PATTERN.find(line) ?: continue
            val title = m.groupValues[1].trim()
            val path = m.groupValues[2].trim().trimStart('/')
            if (title.isNotEmpty() && path.isNotEmpty()) {
                out += ManifestChapter(title = title, path = path)
            }
        }
        return out
    }
}
