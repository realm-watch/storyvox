package `in`.jphe.storyvox.source.github.manifest

/**
 * Merged result of book.toml + storyvox.json + (optional) bare-repo
 * fallback. The single shape downstream consumers
 * ([`in`.jphe.storyvox.source.github.GitHubSource.fictionDetail],
 * arriving in step 3d-detail-and-chapter) read from.
 *
 * Field origins, in priority order:
 *  - `title` ← `book.toml [book].title` → repo name (kebab-case → Title Case)
 *  - `author` ← first of `book.toml [book].authors` → repo owner login
 *  - `description` ← `book.toml [book].description` → null
 *  - `coverPath` ← `storyvox.json.cover` → null
 *  - `tags` ← `storyvox.json.tags` → empty
 *  - `status` ← `storyvox.json.status` → null (caller decides default)
 *  - `language` ← `book.toml [book].language` → null
 *  - `srcDir` ← `book.toml [book].src` → "src"
 *  - `chapters` ← parsed `SUMMARY.md` → bare-repo numbered-file fallback
 *  - `narratorVoiceId` ← `storyvox.json.narrator_voice_id` → null
 *  - `honeypotSelectors` ← `storyvox.json.honeypot_selectors` → empty
 */
internal data class BookManifest(
    val title: String,
    val author: String,
    val description: String? = null,
    val coverPath: String? = null,
    val tags: List<String> = emptyList(),
    /** Raw curator string ("ongoing"/"completed"/"hiatus"/"dropped"); caller maps. */
    val status: String? = null,
    val language: String? = null,
    val srcDir: String = "src",
    val chapters: List<ManifestChapter> = emptyList(),
    val narratorVoiceId: String? = null,
    val honeypotSelectors: List<String> = emptyList(),
)

/** A single chapter entry derived from SUMMARY.md or the bare-repo
 *  fallback. `path` is repo-relative (no leading slash). */
internal data class ManifestChapter(
    val title: String,
    val path: String,
)
