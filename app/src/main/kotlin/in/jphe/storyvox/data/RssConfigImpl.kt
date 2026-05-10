package `in`.jphe.storyvox.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.source.rss.config.RssConfig
import `in`.jphe.storyvox.source.rss.config.RssSubscription
import `in`.jphe.storyvox.source.rss.config.fictionIdForFeedUrl
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.rssDataStore: DataStore<Preferences> by preferencesDataStore(name = "storyvox_rss")

private object RssKeys {
    /** Pipe-separated list of feed URLs the user has added. Pipe is a
     *  safe delimiter — URL spec excludes `|` from any URL component
     *  without percent-encoding, so we never collide with a real URL.
     *  Empty / missing key = no subscriptions. */
    val FEEDS = stringPreferencesKey("pref_rss_feeds")
}

/**
 * Production [RssConfig] (issue #236) backed by a tiny dedicated
 * DataStore. Kept separate from [SettingsRepositoryUiImpl]'s
 * `storyvox_settings` so the RSS subscription set can grow without
 * churning that file's preference schema (same pattern as
 * [PalaceConfigImpl]).
 *
 * Storage format: pipe-separated URLs. Round-trip is just
 * `split('|').filter { it.isNotBlank() }`. Adding/removing rewrites
 * the whole field — it's a small list (typically <50 feeds) so a
 * full rewrite is cheaper than maintaining structured storage.
 */
@Singleton
class RssConfigImpl(
    private val store: DataStore<Preferences>,
) : RssConfig {

    @Inject constructor(@ApplicationContext context: Context) : this(context.rssDataStore)

    override val subscriptions: Flow<List<RssSubscription>> = store.data
        .map { prefs -> decode(prefs[RssKeys.FEEDS]) }
        .distinctUntilChanged()

    override suspend fun snapshot(): List<RssSubscription> =
        decode(store.data.first()[RssKeys.FEEDS])

    override suspend fun addFeed(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        store.edit { prefs ->
            val existing = decode(prefs[RssKeys.FEEDS])
            // De-dupe by canonical URL (lowercased, trimmed) to stop
            // accidental duplicates from copy-paste with trailing
            // whitespace or differing case in the host segment.
            val canonical = trimmed.lowercase()
            if (existing.any { it.url.lowercase() == canonical }) return@edit
            val updated = existing + RssSubscription(
                fictionId = fictionIdForFeedUrl(trimmed),
                url = trimmed,
            )
            prefs[RssKeys.FEEDS] = encode(updated)
        }
    }

    override suspend fun removeFeed(fictionId: String) {
        store.edit { prefs ->
            val existing = decode(prefs[RssKeys.FEEDS])
            val updated = existing.filterNot { it.fictionId == fictionId }
            if (updated.size == existing.size) return@edit
            prefs[RssKeys.FEEDS] = encode(updated)
        }
    }

    private fun encode(subs: List<RssSubscription>): String =
        subs.joinToString("|") { it.url }

    private fun decode(raw: String?): List<RssSubscription> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split('|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { url -> RssSubscription(fictionId = fictionIdForFeedUrl(url), url = url) }
    }
}
