package `in`.jphe.storyvox.data.source

/**
 * Pure-JVM URL → (sourceId, fictionId) dispatcher used by the
 * paste-anything add-fiction flow. Lives in :core-data so source modules
 * stay leaves of the dependency graph; each pattern is owned here, not
 * lifted from the source modules.
 *
 * Returns null when no pattern matches. Recognised-but-unsupported
 * sources (GitHub today) still return a [Match] — the repository layer
 * decides whether to honour or reject the routing.
 */
object UrlRouter {

    private const val ROYALROAD = "royalroad"
    private const val GITHUB = "github"

    /** `https://www.royalroad.com/fiction/{id}` and `/fiction/{id}/.../chapter/...`. */
    private val ROYALROAD_PATTERN = Regex(
        """^https?://(?:www\.)?royalroad\.com/fiction/(\d+)(?:/.*)?$""",
        RegexOption.IGNORE_CASE,
    )

    /** `https://github.com/{owner}/{repo}` and `/tree/{branch}` etc. */
    private val GITHUB_HTTPS_PATTERN = Regex(
        """^https?://github\.com/([\w.-]+)/([\w.-]+?)(?:\.git)?(?:/.*)?$""",
        RegexOption.IGNORE_CASE,
    )

    /** Short form `github:owner/repo` or bare `owner/repo` (no scheme, no slashes elsewhere). */
    private val GITHUB_SHORT_PATTERN = Regex(
        """^(?:github:)?([\w.-]+)/([\w.-]+?)(?:\.git)?$""",
    )

    data class Match(val sourceId: String, val fictionId: String)

    fun route(input: String): Match? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        ROYALROAD_PATTERN.matchEntire(trimmed)?.let { m ->
            return Match(ROYALROAD, m.groupValues[1])
        }

        GITHUB_HTTPS_PATTERN.matchEntire(trimmed)?.let { m ->
            return Match(GITHUB, "$GITHUB:${m.groupValues[1].lowercase()}/${m.groupValues[2].lowercase()}")
        }

        // Reject ambiguous short form on URLs that look like a full path
        // (anything containing `://` or more than one `/`). The short
        // pattern only matches `owner/repo` cleanly.
        if ("://" !in trimmed && trimmed.count { it == '/' } == 1) {
            GITHUB_SHORT_PATTERN.matchEntire(trimmed)?.let { m ->
                return Match(GITHUB, "$GITHUB:${m.groupValues[1].lowercase()}/${m.groupValues[2].lowercase()}")
            }
        }

        return null
    }
}
