package `in`.jphe.storyvox.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.source.notion.config.NotionConfig
import `in`.jphe.storyvox.source.notion.config.NotionConfigState
import `in`.jphe.storyvox.source.notion.config.NotionDefaults
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.notionDataStore: DataStore<Preferences> by preferencesDataStore(name = "storyvox_notion")

private object NotionKeys {
    /** Notion database id — the database the source treats as the
     *  fiction catalog. Stored as the user enters it (trimmed); the
     *  Notion API accepts both hyphenated UUID and 32-hex forms. */
    val DATABASE_ID = stringPreferencesKey("pref_notion_database_id")
}

/** EncryptedSharedPreferences key for the Notion integration token.
 *  Lives next to the palace + outline + RR cookie tokens in
 *  `storyvox.secrets`. */
internal const val NOTION_API_TOKEN_PREF = "notion.api_token"

/**
 * Issue #233 — production [NotionConfig]. Database id in plaintext
 * DataStore (it's a public-ish identifier, not a secret — Notion DB
 * IDs are URL-visible to anyone who has the share link); integration
 * token in EncryptedSharedPreferences alongside the other source tokens.
 *
 * Same shape as [OutlineConfigImpl] — the parallel structure makes
 * the secrets store one consistent surface across :source-mempalace,
 * :source-outline, and :source-notion.
 *
 * Defaults to [NotionDefaults.TECHEMPOWER_DATABASE_ID] (#390) when no
 * persisted value is present — fresh installs land pointed at the
 * techempower.org content database without configuration. Existing
 * users with a stored value keep it; only the fallback changed.
 */
@Singleton
class NotionConfigImpl(
    private val store: DataStore<Preferences>,
    private val secrets: SharedPreferences,
) : NotionConfig {

    @Inject constructor(
        @ApplicationContext context: Context,
        secrets: SharedPreferences,
    ) : this(context.notionDataStore, secrets)

    /**
     * Tick bumped whenever [setApiToken] runs so the [state] flow
     * re-emits with the fresh token value. SharedPreferences doesn't
     * expose a Flow on its own — same pattern as `OutlineConfigImpl`
     * uses for the API-key leg, except we use a MutableStateFlow tick
     * so the combine below sees the change.
     */
    private val secretsTick = MutableStateFlow(0L)

    override val state: Flow<NotionConfigState> = combine(
        store.data.map { it[NotionKeys.DATABASE_ID].orEmpty() }.distinctUntilChanged(),
        secretsTick,
    ) { storedDbId, _ ->
        NotionConfigState(
            databaseId = if (storedDbId.isBlank()) NotionDefaults.TECHEMPOWER_DATABASE_ID else storedDbId,
            apiToken = secrets.getString(NOTION_API_TOKEN_PREF, "") ?: "",
        )
    }.distinctUntilChanged()

    override suspend fun current(): NotionConfigState {
        val storedDbId = store.data.first()[NotionKeys.DATABASE_ID].orEmpty()
        return NotionConfigState(
            databaseId = if (storedDbId.isBlank()) NotionDefaults.TECHEMPOWER_DATABASE_ID else storedDbId,
            apiToken = secrets.getString(NOTION_API_TOKEN_PREF, "") ?: "",
        )
    }

    /**
     * Persist the database id. Trims whitespace. Empty input wipes
     * the stored value so the state flow falls back to the bundled
     * default — `Clear` behaviour without a separate `clearDatabaseId()`
     * method on the public interface.
     */
    suspend fun setDatabaseId(id: String) {
        val trimmed = id.trim()
        if (trimmed.isBlank()) {
            store.edit { it.remove(NotionKeys.DATABASE_ID) }
        } else {
            store.edit { it[NotionKeys.DATABASE_ID] = trimmed }
        }
    }

    /**
     * Persist the integration token. Null/blank clears the store
     * entry so the source returns AuthRequired on subsequent calls.
     * Bumps [secretsTick] so the state flow re-emits without an
     * explicit Settings re-fetch.
     */
    fun setApiToken(token: String?) {
        if (token.isNullOrBlank()) {
            secrets.edit().remove(NOTION_API_TOKEN_PREF).apply()
        } else {
            secrets.edit().putString(NOTION_API_TOKEN_PREF, token.trim()).apply()
        }
        secretsTick.value = secretsTick.value + 1
    }

    /** True when a non-empty token is stored. Drives the UI's
     *  `notionTokenConfigured: Boolean` projection. */
    fun isTokenConfigured(): Boolean =
        !secrets.getString(NOTION_API_TOKEN_PREF, "").isNullOrBlank()

    /** Wipe both database id + token — Settings "Forget Notion" path
     *  (no UI affordance yet; available for diagnostics + tests). */
    suspend fun clear() {
        store.edit { it.remove(NotionKeys.DATABASE_ID) }
        secrets.edit().remove(NOTION_API_TOKEN_PREF).apply()
        secretsTick.value = secretsTick.value + 1
    }
}
