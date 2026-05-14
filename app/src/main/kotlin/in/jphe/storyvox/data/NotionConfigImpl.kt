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
import `in`.jphe.storyvox.source.notion.config.NotionMode
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
     *  fiction catalog in [NotionMode.OFFICIAL_PAT]. Stored as the user
     *  enters it (trimmed); the Notion API accepts both hyphenated UUID
     *  and 32-hex forms. */
    val DATABASE_ID = stringPreferencesKey("pref_notion_database_id")

    /** Issue #393 — root page id for [NotionMode.ANONYMOUS_PUBLIC].
     *  Defaults to [NotionDefaults.TECHEMPOWER_ROOT_PAGE_ID] when unset
     *  so fresh installs read TechEmpower's public Notion tree without
     *  configuration. Users can override to point at any public Notion
     *  page via Settings. */
    val ROOT_PAGE_ID = stringPreferencesKey("pref_notion_root_page_id")
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
        store.data.map { prefs ->
            prefs[NotionKeys.DATABASE_ID].orEmpty() to prefs[NotionKeys.ROOT_PAGE_ID].orEmpty()
        }.distinctUntilChanged(),
        secretsTick,
    ) { (storedDbId, storedRootId), _ ->
        val token = secrets.getString(NOTION_API_TOKEN_PREF, "") ?: ""
        // Issue #393 — mode is implicit: a non-blank token means the
        // user wants the PAT-driven workspace path; blank → anonymous
        // public reader. Same shape across `state` and `current()`.
        val mode = if (token.isNotBlank()) NotionMode.OFFICIAL_PAT else NotionMode.ANONYMOUS_PUBLIC
        NotionConfigState(
            mode = mode,
            databaseId = if (storedDbId.isBlank()) NotionDefaults.TECHEMPOWER_DATABASE_ID else storedDbId,
            rootPageId = if (storedRootId.isBlank()) NotionDefaults.TECHEMPOWER_ROOT_PAGE_ID else storedRootId,
            apiToken = token,
        )
    }.distinctUntilChanged()

    override suspend fun current(): NotionConfigState {
        val prefs = store.data.first()
        val storedDbId = prefs[NotionKeys.DATABASE_ID].orEmpty()
        val storedRootId = prefs[NotionKeys.ROOT_PAGE_ID].orEmpty()
        val token = secrets.getString(NOTION_API_TOKEN_PREF, "") ?: ""
        val mode = if (token.isNotBlank()) NotionMode.OFFICIAL_PAT else NotionMode.ANONYMOUS_PUBLIC
        return NotionConfigState(
            mode = mode,
            databaseId = if (storedDbId.isBlank()) NotionDefaults.TECHEMPOWER_DATABASE_ID else storedDbId,
            rootPageId = if (storedRootId.isBlank()) NotionDefaults.TECHEMPOWER_ROOT_PAGE_ID else storedRootId,
            apiToken = token,
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

    /**
     * Persist the anonymous-mode root page id. Trims whitespace. Empty
     * input wipes the stored value so the state flow falls back to
     * [NotionDefaults.TECHEMPOWER_ROOT_PAGE_ID]. Accepts hyphenated or
     * compact 32-hex forms — the unofficial API client hyphenates
     * before each call.
     */
    suspend fun setRootPageId(id: String) {
        val trimmed = id.trim()
        if (trimmed.isBlank()) {
            store.edit { it.remove(NotionKeys.ROOT_PAGE_ID) }
        } else {
            store.edit { it[NotionKeys.ROOT_PAGE_ID] = trimmed }
        }
    }

    /** Wipe database id, root page id, and token — Settings "Forget
     *  Notion" path (no UI affordance yet; available for diagnostics +
     *  tests). After this call the source falls back to the bundled
     *  TechEmpower defaults in anonymous mode. */
    suspend fun clear() {
        store.edit {
            it.remove(NotionKeys.DATABASE_ID)
            it.remove(NotionKeys.ROOT_PAGE_ID)
        }
        secrets.edit().remove(NOTION_API_TOKEN_PREF).apply()
        secretsTick.value = secretsTick.value + 1
    }
}
