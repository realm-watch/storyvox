package `in`.jphe.storyvox.source.github.manifest

/**
 * Minimal TOML reader for the subset of `book.toml` (mdbook standard)
 * storyvox actually consumes. NOT a general-purpose TOML parser —
 * intentionally scoped to keep the source-github module dep-free.
 *
 * Recognised:
 *  - Section headers: `[book]`, `[output.html]` (subsection ignored).
 *  - Bare key = value pairs inside the active section.
 *  - String values: `"…"` with `\"`, `\\`, `\n`, `\t` escapes.
 *  - String arrays: `["a", "b", "c"]` — single-line only.
 *  - Comments: `# …` to end of line (outside strings).
 *  - Blank lines and whitespace are ignored.
 *
 * NOT recognised:
 *  - Multi-line strings (`"""…"""`).
 *  - Numbers, booleans, datetimes.
 *  - Inline tables.
 *  - Multi-line arrays.
 *  - Dotted keys.
 *
 * Unparseable lines fall through silently — book.toml authors using
 * mdbook features storyvox doesn't read shouldn't crash the import.
 */
internal data class BookToml(
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val description: String? = null,
    val language: String? = null,
    val src: String? = null,
)

internal object BookTomlParser {

    fun parse(raw: String): BookToml {
        var section: String? = null
        var title: String? = null
        var authors: List<String> = emptyList()
        var description: String? = null
        var language: String? = null
        var src: String? = null

        for (rawLine in raw.lineSequence()) {
            val line = stripComment(rawLine).trim()
            if (line.isEmpty()) continue

            // Section header.
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim()
                continue
            }

            // Only [book] keys interest us. Everything else (preprocessor,
            // output.*) we tolerate but skip.
            if (section != "book") continue

            val eq = line.indexOf('=')
            if (eq < 0) continue
            val key = line.substring(0, eq).trim()
            val rest = line.substring(eq + 1).trim()

            when (key) {
                "title" -> title = parseString(rest)
                "authors" -> authors = parseStringArray(rest)
                "description" -> description = parseString(rest)
                "language" -> language = parseString(rest)
                "src" -> src = parseString(rest)
                // book.toml may have other keys (multilingual, etc.) — skip.
            }
        }

        return BookToml(
            title = title,
            authors = authors,
            description = description,
            language = language,
            src = src,
        )
    }

    /**
     * Strip a trailing `# comment` from [line] without breaking
     * comment characters that appear inside quoted strings.
     */
    private fun stripComment(line: String): String {
        var inString = false
        var escaped = false
        for (i in line.indices) {
            val c = line[i]
            if (escaped) {
                escaped = false
                continue
            }
            when (c) {
                '\\' -> if (inString) escaped = true
                '"' -> inString = !inString
                '#' -> if (!inString) return line.substring(0, i)
            }
        }
        return line
    }

    /** `"hello"` → `hello`. Returns null on unparseable input. */
    private fun parseString(raw: String): String? {
        val s = raw.trim()
        if (s.length < 2 || !s.startsWith('"') || !s.endsWith('"')) return null
        return decodeEscapes(s.substring(1, s.length - 1))
    }

    /** `["a", "b"]` → `["a", "b"]`. Single-line only. */
    private fun parseStringArray(raw: String): List<String> {
        val s = raw.trim()
        if (!s.startsWith("[") || !s.endsWith("]")) return emptyList()
        val inner = s.substring(1, s.length - 1).trim()
        if (inner.isEmpty()) return emptyList()
        // Split by commas that aren't inside quoted strings.
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inString = false
        var escaped = false
        for (c in inner) {
            if (escaped) {
                current.append(c); escaped = false; continue
            }
            when {
                c == '\\' && inString -> { current.append(c); escaped = true }
                c == '"' -> { current.append(c); inString = !inString }
                c == ',' && !inString -> {
                    parts += current.toString().trim()
                    current.clear()
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) parts += current.toString().trim()
        return parts.mapNotNull { parseString(it) }
    }

    private fun decodeEscapes(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val next = s[i + 1]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    else -> { sb.append(c); sb.append(next) }
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }
}
