package `in`.jphe.storyvox.source.github.manifest

import org.json.JSONException
import org.json.JSONObject

/**
 * Minimal JSON reader for the subset of HonKit's `book.json` (and
 * legacy GitBook's same format) that storyvox needs. HonKit is the
 * actively-maintained fork of GitBook v2 — same file format, same
 * SUMMARY.md conventions. Storyvox-rendering treats HonKit and mdbook
 * repos identically once we extract the same five fields.
 *
 * Recognized fields (everything else ignored):
 *  - `title` — book title; falls back to repo name
 *  - `author` — single author OR `authors` array
 *  - `description` — short description
 *  - `language` — BCP-47 / ISO 639-1 locale
 *  - `structure.summary` — path to SUMMARY.md (default `SUMMARY.md`)
 *    Not the same as mdbook's `src` dir — HonKit's SUMMARY.md
 *    typically lives at the repo root, not under `src/`.
 *
 * Threat model: same as [BookTomlParser] — well-formed JSON written by
 * HonKit for HonKit. We don't defend against adversarial input;
 * malformed JSON → empty manifest (caller still gets a usable
 * BookManifest from fallback paths). Unlike the TOML parser this uses
 * `org.json` which is in the Android SDK — no extra dep.
 */
internal object BookJsonParser {

    /** Parse [text]. Returns a [BookJson] holding the extracted fields,
     *  or an empty [BookJson] on parse failure. */
    fun parse(text: String): BookJson {
        if (text.isBlank()) return BookJson()
        return try {
            val root = JSONObject(text)
            val title = root.optString("title").takeIf { it.isNotBlank() }
            val description = root.optString("description").takeIf { it.isNotBlank() }
            val language = root.optString("language").takeIf { it.isNotBlank() }
            // `author` (singular) OR `authors` (array). HonKit's docs use
            // both forms in different examples; storyvox just wants the
            // first author for the byline.
            val singleAuthor = root.optString("author").takeIf { it.isNotBlank() }
            val authorsArray = root.optJSONArray("authors")
            val firstAuthor = singleAuthor ?: authorsArray
                ?.takeIf { it.length() > 0 }
                ?.optString(0)
                ?.takeIf { it.isNotBlank() }
            // `structure.summary` is the HonKit-specific override for
            // where SUMMARY.md lives. Default is repo-root SUMMARY.md.
            val summaryPath = root.optJSONObject("structure")
                ?.optString("summary")
                ?.takeIf { it.isNotBlank() }
                ?: "SUMMARY.md"
            BookJson(
                title = title,
                description = description,
                language = language,
                author = firstAuthor,
                summaryPath = summaryPath,
            )
        } catch (_: JSONException) {
            BookJson()
        }
    }
}

/** Subset of HonKit's book.json that ManifestParser cares about.
 *  Shape mirrors [BookToml] so the merge logic stays simple. */
internal data class BookJson(
    val title: String? = null,
    val description: String? = null,
    val language: String? = null,
    val author: String? = null,
    /** Repo-relative path to SUMMARY.md. Defaults to `SUMMARY.md`
     *  (repo root), matching HonKit's default `structure.summary`. */
    val summaryPath: String = "SUMMARY.md",
)
