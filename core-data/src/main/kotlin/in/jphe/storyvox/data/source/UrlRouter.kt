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

    /**
     * `https://gist.github.com/{user}/{id}` and the user-less variant
     * `https://gist.github.com/{id}` GitHub still accepts. The id is
     * a hex blob; the optional revision suffix `/<sha>` is tolerated
     * but discarded — gists have no inter-revision identity in the
     * fiction sense, so we always resolve to the head id.
     */
    private val GITHUB_GIST_PATTERN = Regex(
        """^https?://gist\.github\.com/(?:([\w.-]+)/)?([0-9a-f]+)(?:/[0-9a-f]+)?/?$""",
        RegexOption.IGNORE_CASE,
    )

    /** Short form `gist:<id>` (no scheme). Bare hex isn't accepted —
     *  too easy to collide with arbitrary user input that happens to
     *  be hex. The explicit prefix anchors intent. */
    private val GITHUB_GIST_SHORT_PATTERN = Regex(
        """^gist:([0-9a-f]+)$""",
        RegexOption.IGNORE_CASE,
    )

    data class Match(val sourceId: String, val fictionId: String)

    fun route(input: String): Match? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        ROYALROAD_PATTERN.matchEntire(trimmed)?.let { m ->
            return Match(SourceIds.ROYAL_ROAD, m.groupValues[1])
        }

        // Gist URLs must be matched before the GitHub repo pattern —
        // both live on github.com, but only the gist subdomain matters
        // here; the repo pattern is anchored on `github.com` (no
        // subdomain), so the order is actually safe either way.
        GITHUB_GIST_PATTERN.matchEntire(trimmed)?.let { m ->
            val gistId = m.groupValues[2].lowercase()
            return Match(SourceIds.GITHUB, "${SourceIds.GITHUB}:gist:$gistId")
        }

        GITHUB_HTTPS_PATTERN.matchEntire(trimmed)?.let { m ->
            return Match(SourceIds.GITHUB, "${SourceIds.GITHUB}:${m.groupValues[1].lowercase()}/${m.groupValues[2].lowercase()}")
        }

        GITHUB_GIST_SHORT_PATTERN.matchEntire(trimmed)?.let { m ->
            return Match(SourceIds.GITHUB, "${SourceIds.GITHUB}:gist:${m.groupValues[1].lowercase()}")
        }

        // Reject ambiguous short form on URLs that look like a full path
        // (anything containing `://` or more than one `/`). The short
        // pattern only matches `owner/repo` cleanly.
        if ("://" !in trimmed && trimmed.count { it == '/' } == 1) {
            GITHUB_SHORT_PATTERN.matchEntire(trimmed)?.let { m ->
                return Match(SourceIds.GITHUB, "${SourceIds.GITHUB}:${m.groupValues[1].lowercase()}/${m.groupValues[2].lowercase()}")
            }
        }

        return null
    }
}
