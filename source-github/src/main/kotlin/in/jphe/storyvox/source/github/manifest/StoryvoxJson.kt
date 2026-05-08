package `in`.jphe.storyvox.source.github.manifest

import `in`.jphe.storyvox.source.github.net.GitHubJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * Optional storyvox.json manifest at the repo root. Authored by the
 * fiction author (the curator/registry doesn't see this) — extends
 * mdbook's book.toml with metadata mdbook doesn't know about.
 *
 * Spec shape (docs/superpowers/specs/2026-05-06-github-source-design.md
 * lines 99-114):
 *
 * ```json
 * {
 *   "version": 1,
 *   "cover": "assets/cover.png",
 *   "tags": ["fantasy", "litrpg"],
 *   "status": "ongoing",
 *   "narrator_voice_id": "en-US-Andrew:DragonHDLatestNeural",
 *   "honeypot_selectors": []
 * }
 * ```
 *
 * Every field is optional; absence falls back to [BookManifest]
 * defaults at merge time.
 */
@Serializable
internal data class StoryvoxJson(
    @SerialName("version") val version: Int = 1,
    @SerialName("cover") val cover: String? = null,
    @SerialName("tags") val tags: List<String> = emptyList(),
    @SerialName("status") val status: String? = null,
    @SerialName("narrator_voice_id") val narratorVoiceId: String? = null,
    @SerialName("honeypot_selectors") val honeypotSelectors: List<String> = emptyList(),
)

internal object StoryvoxJsonParser {
    /** Parses [raw] storyvox.json. Returns null on unparseable input —
     *  caller falls back to defaults. */
    fun parse(raw: String): StoryvoxJson? = try {
        GitHubJson.decodeFromString(StoryvoxJson.serializer(), raw)
    } catch (_: SerializationException) {
        null
    }
}
