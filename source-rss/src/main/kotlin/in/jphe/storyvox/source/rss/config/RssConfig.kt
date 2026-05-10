package `in`.jphe.storyvox.source.rss.config

import kotlinx.coroutines.flow.Flow

/**
 * Issue #236 — abstraction over the RSS-source's persistent feed
 * list. The implementation lives in `:app` (DataStore) so the source
 * module stays free of Android Preferences plumbing; this interface
 * is what the source consumes.
 *
 * Each [RssSubscription] is one feed URL the user has added. The
 * fictionId for a feed in storyvox is derived from a stable hash of
 * the URL — the Hilt `Map<String, FictionSource>` keys on the
 * source id, then the per-fiction id keys on the URL hash so two
 * feeds added in different orders don't collide.
 */
interface RssConfig {
    /** Hot stream of the user's current feed list. */
    val subscriptions: Flow<List<RssSubscription>>

    /** Synchronous snapshot for code paths that can't suspend. */
    suspend fun snapshot(): List<RssSubscription>

    /** Add a feed by URL. No-op if the URL is already subscribed. */
    suspend fun addFeed(url: String)

    /** Remove a feed by its derived [RssSubscription.fictionId]. */
    suspend fun removeFeed(fictionId: String)
}

/**
 * One persisted feed subscription. [fictionId] is what storyvox uses
 * everywhere — it's a stable hash of the URL, so the same feed has
 * the same id across re-launches.
 */
data class RssSubscription(
    val fictionId: String,
    val url: String,
)

/**
 * Issue #236 — derive the persistent fictionId from the URL. Stable
 * across re-launches; collision-resistant for the small N (a few
 * hundred feeds at most per user) that storyvox cares about. We
 * don't need cryptographic strength — `String.hashCode` plus a
 * length prefix is enough to keep IDs short and unambiguous.
 */
fun fictionIdForFeedUrl(url: String): String {
    val canonical = url.trim().lowercase()
    val hash = canonical.hashCode().toUInt().toString(16).padStart(8, '0')
    return "rss:$hash"
}
