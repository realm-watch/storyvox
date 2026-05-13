package `in`.jphe.storyvox.sync.coordinator

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tombstonesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "storyvox_sync_tombstones",
)

/**
 * Read-only / write seam over a per-domain tombstone log. Extracted so
 * tests can substitute an in-memory implementation without needing a
 * full Android context — the production [TombstoneStore] is the only
 * non-test implementation.
 */
interface TombstonesAccess {
    fun observe(domain: String): Flow<Set<String>>
    suspend fun snapshot(domain: String): Set<String>
    suspend fun add(domain: String, id: String)
    suspend fun addAll(domain: String, ids: Collection<String>)
    suspend fun forget(domain: String, id: String)
    suspend fun clear(domain: String)
}

/**
 * Per-domain tombstone log. Records deletions so a synced device can
 * propagate "I removed X" to the cloud (and from the cloud to every
 * other device on next pull).
 *
 * Why a separate store and not just `Set<id>` in InstantDB: a set without
 * a tombstone log races with a "re-add" — if device A removes X, device B
 * is offline, B re-adds X, then both sync, the union of local + remote
 * would put X back. Tombstones break that tie deterministically: removal
 * is sticky until a successful sync confirms server-side deletion, at
 * which point the tombstone is forgotten.
 *
 * Stored in DataStore (not Room) because the access pattern is
 * "small set per domain, read-write-mostly" — DataStore's preferences
 * proto gives us a Flow for free and is the lighter dep.
 *
 * Storage layout: one stringSet key per domain, with key
 * `tomb.<domain>.<id>`. We bucket per-domain so a domain can clear its
 * own tombstones without touching others.
 */
@Singleton
class TombstoneStore @Inject constructor(
    @ApplicationContext context: Context,
) : TombstonesAccess {
    private val store = context.tombstonesDataStore

    override fun observe(domain: String): Flow<Set<String>> = store.data.map { prefs ->
        prefs[domainKey(domain)] ?: emptySet()
    }

    override suspend fun snapshot(domain: String): Set<String> =
        observe(domain).first()

    override suspend fun add(domain: String, id: String) {
        store.edit { prefs ->
            val key = domainKey(domain)
            val current = prefs[key] ?: emptySet()
            prefs[key] = current + id
        }
    }

    override suspend fun addAll(domain: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        store.edit { prefs ->
            val key = domainKey(domain)
            val current = prefs[key] ?: emptySet()
            prefs[key] = current + ids
        }
    }

    override suspend fun forget(domain: String, id: String) {
        store.edit { prefs ->
            val key = domainKey(domain)
            val current = prefs[key] ?: return@edit
            val next = current - id
            if (next.isEmpty()) prefs.remove(key) else prefs[key] = next
        }
    }

    override suspend fun clear(domain: String) {
        store.edit { prefs -> prefs.remove(domainKey(domain)) }
    }

    private fun domainKey(domain: String) = stringSetPreferencesKey("tomb.$domain")
}

/** Helper extension so [TombstoneStore] can be constructed in tests
 *  without the full DataStore — useful for ConflictPolicies tests that
 *  exercise the union-with-tombstones path. */
fun emptyTombstones(): Set<String> = emptySet()
