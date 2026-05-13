package `in`.jphe.storyvox.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.source.wikipedia.config.WikipediaConfig
import `in`.jphe.storyvox.source.wikipedia.config.WikipediaConfigState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.wikipediaDataStore: DataStore<Preferences> by preferencesDataStore(name = "storyvox_wikipedia")

private object WikipediaKeys {
    /** Wikipedia language code (`en`, `de`, `ja`, `simple`, ...).
     *  Resolves the Wikimedia REST host: `<lang>.wikipedia.org`. */
    val LANGUAGE_CODE = stringPreferencesKey("pref_wikipedia_language_code")
}

/**
 * Issue #377 — production [WikipediaConfig]. Stores just the language
 * code in a dedicated DataStore (no secrets — Wikipedia is read-only
 * and public). Same pattern as [OutlineConfigImpl] minus the encrypted
 * API-token leg.
 */
@Singleton
class WikipediaConfigImpl(
    private val store: DataStore<Preferences>,
) : WikipediaConfig {

    @Inject constructor(
        @ApplicationContext context: Context,
    ) : this(context.wikipediaDataStore)

    override val state: Flow<WikipediaConfigState> = store.data
        .map { prefs ->
            WikipediaConfigState(
                languageCode = prefs[WikipediaKeys.LANGUAGE_CODE]
                    ?: WikipediaConfigState.DEFAULT_LANGUAGE_CODE,
            )
        }
        .distinctUntilChanged()

    override suspend fun current(): WikipediaConfigState = WikipediaConfigState(
        languageCode = store.data.first()[WikipediaKeys.LANGUAGE_CODE]
            ?: WikipediaConfigState.DEFAULT_LANGUAGE_CODE,
    )

    suspend fun setLanguageCode(code: String) {
        val trimmed = code.trim().lowercase()
        if (trimmed.isBlank()) {
            store.edit { it.remove(WikipediaKeys.LANGUAGE_CODE) }
        } else {
            store.edit { it[WikipediaKeys.LANGUAGE_CODE] = trimmed }
        }
    }
}
