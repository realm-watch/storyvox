package `in`.jphe.storyvox.playback.cache

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * User-facing quota for the PCM cache, persisted via DataStore.
 *
 * **Default: 2 GB** (per spec — sized for ~30 chapters of Piper-high
 * Sky Pride, which is the binge-listening case JP measured).
 *
 * PR-G's Settings UI overwrites this value via [setQuotaBytes]. PR-C
 * only ships the read path with a hard-coded default; if no Settings
 * UI ever lands, the cache simply never grows past 2 GB.
 *
 * The quota is enforced by [PcmCache.evictTo]; the config object
 * itself does no enforcement.
 */
@Singleton
class PcmCacheConfig(
    private val store: DataStore<Preferences>,
) {
    /** Hilt entry point — pulls the production DataStore from the app
     *  context. The primary constructor takes the store directly so
     *  JVM unit tests (no Robolectric) can swap in a
     *  `PreferenceDataStoreFactory.create(file)`-backed instance
     *  against a `TemporaryFolder`. Same seam as
     *  [SettingsRepositoryUiImpl]'s @Inject constructor. */
    @Inject constructor(
        @ApplicationContext context: Context,
    ) : this(context.pcmCacheConfigStore)

    suspend fun quotaBytes(): Long =
        store.data.map { it[QUOTA_KEY] ?: DEFAULT_QUOTA_BYTES }.first()

    suspend fun setQuotaBytes(bytes: Long) {
        // Floor at 100 MB so a misconfigured Settings UI doesn't lock
        // the user into a no-cache mode by mistake. "Unlimited" is
        // represented by Long.MAX_VALUE; UI knob in PR-G picks the
        // discrete options.
        val clamped = bytes.coerceAtLeast(100L * 1024 * 1024)
        store.edit { prefs -> prefs[QUOTA_KEY] = clamped }
    }

    private companion object {
        val QUOTA_KEY = longPreferencesKey("pcm_cache_quota_bytes")

        /** 2 GB. Spec calls this out as the default — sized to fit ~30
         *  chapters of Piper-high audiobook PCM. */
        const val DEFAULT_QUOTA_BYTES: Long = 2L * 1024 * 1024 * 1024
    }
}

private val Context.pcmCacheConfigStore: DataStore<Preferences> by preferencesDataStore(
    name = "pcm_cache_config",
)
