package `in`.jphe.storyvox.source.royalroad.net

import `in`.jphe.storyvox.source.royalroad.model.RoyalRoadIds
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Issue #238 — minimal robots.txt parser + cache for Royal Road.
 *
 * RR's robots.txt at the time of writing (#238) only restricts the
 * voting and review-list paths for the wildcard user-agent — none of
 * which storyvox fetches today. Honoring it is essentially free
 * behavior change AND a meaningful posture move: weakens any future
 * "you're a bot circumventing access controls" framing in a ToS
 * dispute.
 *
 * Parser scope: standard RFC 9309 subset.
 *  - `User-agent:` blocks (longest-prefix wins; falls back to `*`).
 *  - `Allow:` and `Disallow:` directives (longest-prefix-match wins
 *    within the block).
 *  - `Crawl-delay:` (seconds, applied to inter-request spacing).
 *  - Comments `#`, blank lines.
 *
 * Out of scope: Sitemap, Host, Clean-param, regex pattern matching,
 * `*` and `$` glob characters in path patterns. RR doesn't use any
 * of these in its current robots.txt; if it ever does, the parser
 * gracefully degrades to "treat the unknown directive as a literal
 * substring" rather than throwing.
 */
@Singleton
internal class RobotsCache @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    private val gate = Mutex()
    @Volatile
    private var cached: CachedRobots? = null

    /** Returns the parsed rules, fetching + caching on first call.
     *  TTL is 24h — robots.txt is meant to be cacheable, and a
     *  fresh fetch on every call would itself be a bot-y access
     *  pattern. */
    private suspend fun rules(): RobotsRules = gate.withLock {
        val now = System.currentTimeMillis()
        cached?.let { c ->
            if (now - c.fetchedAt < TTL_MS) return@withLock c.rules
        }
        val rules = fetchOrEmpty()
        cached = CachedRobots(rules = rules, fetchedAt = now)
        rules
    }

    /** True when [url] is allowed under storyvox's user-agent group.
     *  Defaults to allow on parse failure / fetch error so a
     *  network blip doesn't lock us out of every page. */
    suspend fun isAllowed(url: String): Boolean {
        val path = pathOf(url) ?: return true
        return rules().isAllowed(path)
    }

    /** Crawl-delay in milliseconds for storyvox's user-agent group,
     *  or null when unset. Consumed by [RateLimitedClient] to
     *  raise the inter-request floor when present. */
    suspend fun crawlDelayMs(): Long? = rules().crawlDelaySeconds?.let { it * 1_000L }

    private fun pathOf(url: String): String? = runCatching {
        val u = java.net.URI(url)
        // robots.txt is host-scoped — RR's robots.txt only governs
        // requests to royalroad.com. Out-of-host URLs are unaffected.
        if (u.host?.contains("royalroad.com") != true) return null
        u.rawPath.ifEmpty { "/" }
    }.getOrNull()

    private suspend fun fetchOrEmpty(): RobotsRules = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${RoyalRoadIds.BASE_URL}/robots.txt")
            .header("User-Agent", RoyalRoadIds.USER_AGENT)
            .get()
            .build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext RobotsRules.ALLOW_ALL
                val body = resp.body?.string() ?: return@withContext RobotsRules.ALLOW_ALL
                RobotsParser.parse(body, RoyalRoadIds.USER_AGENT)
            }
        } catch (_: IOException) {
            RobotsRules.ALLOW_ALL
        }
    }

    private data class CachedRobots(val rules: RobotsRules, val fetchedAt: Long)

    companion object {
        private const val TTL_MS = 24 * 60 * 60 * 1_000L
    }
}

/**
 * Parsed robots.txt rules scoped to one user-agent. Built by
 * [RobotsParser.parse] — picks the longest-matching User-agent
 * block (case-insensitive substring), falls back to the `*` block.
 */
internal data class RobotsRules(
    val rules: List<PathRule>,
    val crawlDelaySeconds: Long? = null,
) {
    /** Longest-prefix-match wins. An [Allow] rule shadows a
     *  [Disallow] rule when both match and the [Allow] prefix is
     *  longer (RFC 9309 §2.2.3). Empty rule list → allow. */
    fun isAllowed(path: String): Boolean {
        var best: PathRule? = null
        for (rule in rules) {
            if (path.startsWith(rule.pattern)) {
                if (best == null || rule.pattern.length > best.pattern.length) {
                    best = rule
                }
            }
        }
        return best?.allow ?: true
    }

    companion object {
        val ALLOW_ALL = RobotsRules(rules = emptyList(), crawlDelaySeconds = null)
    }
}

internal data class PathRule(val pattern: String, val allow: Boolean)

internal object RobotsParser {
    fun parse(text: String, userAgent: String): RobotsRules {
        // Group by user-agent. We track each block's UA + collected
        // rules + crawl-delay. After parsing, pick the block whose
        // UA best matches storyvox's user-agent string.
        val blocks = mutableListOf<Block>()
        var current: Block? = null
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()

            when (key) {
                "user-agent" -> {
                    // RFC 9309: consecutive User-agent lines belong to
                    // the same block until a non-UA directive arrives.
                    if (current?.justSawDirective == true) {
                        blocks += current!!.copy()
                        current = null
                    }
                    if (current == null) current = Block(userAgents = mutableListOf(), justSawDirective = false)
                    current!!.userAgents.add(value.lowercase())
                }
                "disallow" -> {
                    current?.let { it.rules += PathRule(value, allow = false); it.justSawDirective = true }
                }
                "allow" -> {
                    current?.let { it.rules += PathRule(value, allow = true); it.justSawDirective = true }
                }
                "crawl-delay" -> {
                    current?.let { it.crawlDelaySeconds = value.toLongOrNull(); it.justSawDirective = true }
                }
                else -> {
                    // Sitemap, Host, etc. — out of scope; treat as a
                    // directive marker so the next User-agent starts a
                    // new block.
                    current?.justSawDirective = true
                }
            }
        }
        current?.let { blocks += it }

        // Score each block against storyvox's user-agent.
        // Score = max-length user-agent that is a substring of the UA;
        // `*` scores 0 (lowest priority). Pick the highest-scoring.
        val ua = userAgent.lowercase()
        var bestBlock: Block? = null
        var bestScore = -1
        for (block in blocks) {
            for (blockUa in block.userAgents) {
                val score = when {
                    blockUa == "*" -> 0
                    ua.contains(blockUa) -> blockUa.length
                    else -> -1
                }
                if (score > bestScore) {
                    bestScore = score
                    bestBlock = block
                }
            }
        }
        return bestBlock?.let {
            RobotsRules(rules = it.rules.toList(), crawlDelaySeconds = it.crawlDelaySeconds)
        } ?: RobotsRules.ALLOW_ALL
    }

    private data class Block(
        val userAgents: MutableList<String>,
        var justSawDirective: Boolean,
        val rules: MutableList<PathRule> = mutableListOf(),
        var crawlDelaySeconds: Long? = null,
    )
}
