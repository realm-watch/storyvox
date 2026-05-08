package `in`.jphe.storyvox.playback.voice

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * User-customizable voice favourites. Backed by its own preferences
 * DataStore (`voice_favorites_v1`) so it can ride alongside, but stay
 * decoupled from, [VoiceManager]'s own `voices_settings` store —
 * favourites are pure UI ergonomics and shouldn't share schema fate
 * with installed-state metadata.
 *
 * The schema is intentionally tiny: a single `Set<String>` of voice
 * ids. Order isn't stored; rendering code sorts the favourites
 * alongside any other tier-grouping rules.
 *
 * **Why a separate file/store?** Two reasons:
 * 1. Migration safety — when [VoiceManager] needed an int8→fp32 id
 *    rewrite the migration touched both `INSTALLED_IDS` and
 *    `ACTIVE_ID` in one transaction. Adding favourites to that store
 *    would force any future `voices_settings` migration to consider
 *    favourites too. Easier to keep them apart.
 * 2. Test isolation — favourites can be unit-tested with their own
 *    in-memory `DataStore` without spinning up the full VoiceManager.
 */
private val Context.voiceFavoritesStore: DataStore<Preferences> by preferencesDataStore(
    name = "voice_favorites_v1",
)

private object FavoriteKeys {
    val IDS = stringSetPreferencesKey("favorite_voice_ids")
}

@Singleton
class VoiceFavorites private constructor(
    private val store: DataStore<Preferences>,
) {
    /** Hilt entry point. Uses [Context.voiceFavoritesStore]. */
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.voiceFavoritesStore)

    /** Reactive set of favourited voice ids. Empty when nothing pinned. */
    val favoriteIds: Flow<Set<String>> = store.data.map { prefs ->
        prefs[FavoriteKeys.IDS].orEmpty()
    }

    /** Idempotent toggle — pins the id if absent, unpins if present. */
    suspend fun toggle(voiceId: String) {
        store.edit { prefs ->
            val current = prefs[FavoriteKeys.IDS].orEmpty()
            prefs[FavoriteKeys.IDS] = if (voiceId in current) {
                current - voiceId
            } else {
                current + voiceId
            }
        }
    }

    suspend fun setFavorite(voiceId: String, favorite: Boolean) {
        store.edit { prefs ->
            val current = prefs[FavoriteKeys.IDS].orEmpty()
            prefs[FavoriteKeys.IDS] = if (favorite) current + voiceId else current - voiceId
        }
    }

    companion object {
        /** Test-only factory — bypass `@ApplicationContext` and inject
         *  a hand-rolled DataStore (typically a temp-file `dataStoreFile`
         *  in a JUnit `TemporaryFolder`). */
        fun forTesting(store: DataStore<Preferences>): VoiceFavorites =
            VoiceFavorites(store)
    }
}
