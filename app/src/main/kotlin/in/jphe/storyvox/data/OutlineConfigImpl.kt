package `in`.jphe.storyvox.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.source.outline.config.OutlineConfig
import `in`.jphe.storyvox.source.outline.config.OutlineConfigState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private val Context.outlineDataStore: DataStore<Preferences> by preferencesDataStore(name = "storyvox_outline")

private object OutlineKeys {
    /** Outline instance host (e.g. `outline.example.com` or
     *  `https://wiki.mycompany.com`). Plaintext — it's a network
     *  address, not a secret. Empty disables the source. */
    val HOST = stringPreferencesKey("pref_outline_host")
}

/** EncryptedSharedPreferences key for the Outline API token.
 *  Lives next to the palace + RR cookie in `storyvox.secrets`. */
internal const val OUTLINE_API_KEY_PREF = "outline.api_key"

/**
 * Production [OutlineConfig] (issue #245) — host in plaintext
 * DataStore, API token in encrypted SharedPreferences. Same pattern
 * as [PalaceConfigImpl].
 */
@Singleton
class OutlineConfigImpl(
    private val store: DataStore<Preferences>,
    private val secrets: SharedPreferences,
) : OutlineConfig {

    @Inject constructor(
        @ApplicationContext context: Context,
        secrets: SharedPreferences,
    ) : this(context.outlineDataStore, secrets)

    override val state: Flow<OutlineConfigState> = combine(
        store.data.map { it[OutlineKeys.HOST].orEmpty() }.distinctUntilChanged(),
        // SharedPreferences doesn't expose a Flow — re-read on host
        // change. The api key is edited via Settings on the same
        // dispatcher; in practice both fields move together.
        flowOf(Unit),
    ) { host, _ ->
        OutlineConfigState(
            host = host,
            apiKey = secrets.getString(OUTLINE_API_KEY_PREF, "") ?: "",
        )
    }.distinctUntilChanged()

    override suspend fun current(): OutlineConfigState = OutlineConfigState(
        host = store.data.first()[OutlineKeys.HOST].orEmpty(),
        apiKey = secrets.getString(OUTLINE_API_KEY_PREF, "") ?: "",
    )

    suspend fun setHost(host: String) {
        store.edit { it[OutlineKeys.HOST] = host.trim() }
    }

    fun setApiKey(apiKey: String) {
        secrets.edit().putString(OUTLINE_API_KEY_PREF, apiKey).apply()
    }

    suspend fun clear() {
        store.edit { it.remove(OutlineKeys.HOST) }
        secrets.edit().remove(OUTLINE_API_KEY_PREF).apply()
    }
}
