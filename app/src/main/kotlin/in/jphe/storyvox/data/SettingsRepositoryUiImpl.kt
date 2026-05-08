package `in`.jphe.storyvox.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.data.auth.SessionHydrator
import `in`.jphe.storyvox.data.repository.AuthRepository
import `in`.jphe.storyvox.feature.api.PunctuationPause
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.feature.api.UiSettings
import `in`.jphe.storyvox.feature.api.UiSigil
import `in`.jphe.storyvox.sigil.Sigil
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "storyvox_settings")

private object Keys {
    val DEFAULT_SPEED = floatPreferencesKey("pref_default_speed")
    val DEFAULT_PITCH = floatPreferencesKey("pref_default_pitch")
    val DEFAULT_VOICE_ID = stringPreferencesKey("pref_default_voice_id")
    val THEME_OVERRIDE = stringPreferencesKey("pref_theme_override")
    val DOWNLOAD_WIFI_ONLY = booleanPreferencesKey("pref_download_wifi_only")
    val POLL_INTERVAL_HOURS = intPreferencesKey("pref_poll_interval_hours")
    val SIGNED_IN = booleanPreferencesKey("pref_signed_in")
    /** Issue #90 — three-stop selector for inter-sentence silence. Stored
     *  as the enum name (`OFF`/`NORMAL`/`LONG`) for forward-compat if we
     *  add stops later (matches THEME_OVERRIDE encoding). Default = NORMAL
     *  preserves pre-#90 audiobook cadence on first launch + on existing
     *  installs that have no value persisted. */
    val PUNCTUATION_PAUSE = stringPreferencesKey("pref_punctuation_pause")
}

@Singleton
class SettingsRepositoryUiImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: AuthRepository,
    private val hydrator: SessionHydrator,
) : SettingsRepositoryUi {

    private val store = context.settingsDataStore

    override val settings: Flow<UiSettings> = store.data.map { prefs ->
        UiSettings(
            ttsEngine = "VoxSherpa",
            defaultVoiceId = prefs[Keys.DEFAULT_VOICE_ID],
            defaultSpeed = prefs[Keys.DEFAULT_SPEED] ?: 1.0f,
            defaultPitch = prefs[Keys.DEFAULT_PITCH] ?: 1.0f,
            themeOverride = prefs[Keys.THEME_OVERRIDE]?.let { runCatching { ThemeOverride.valueOf(it) }.getOrNull() }
                ?: ThemeOverride.System,
            downloadOnWifiOnly = prefs[Keys.DOWNLOAD_WIFI_ONLY] ?: true,
            pollIntervalHours = prefs[Keys.POLL_INTERVAL_HOURS] ?: 6,
            isSignedIn = prefs[Keys.SIGNED_IN] ?: false,
            punctuationPause = prefs[Keys.PUNCTUATION_PAUSE]
                ?.let { runCatching { PunctuationPause.valueOf(it) }.getOrNull() }
                ?: PunctuationPause.Normal,
            sigil = Sigil.current.let {
                UiSigil(
                    name = it.name,
                    realm = it.realm,
                    hash = it.hash,
                    branch = it.branch,
                    dirty = it.dirty,
                    built = it.built,
                    repo = it.repo,
                    versionName = it.versionName,
                )
            },
        )
    }

    override suspend fun setTheme(override: ThemeOverride) {
        store.edit { it[Keys.THEME_OVERRIDE] = override.name }
    }

    override suspend fun setDefaultSpeed(speed: Float) {
        store.edit { it[Keys.DEFAULT_SPEED] = speed.coerceIn(0.5f, 3.0f) }
    }

    override suspend fun setDefaultPitch(pitch: Float) {
        store.edit { it[Keys.DEFAULT_PITCH] = pitch.coerceIn(0.5f, 2.0f) }
    }

    override suspend fun setDefaultVoice(voiceId: String?) {
        store.edit {
            if (voiceId == null) it.remove(Keys.DEFAULT_VOICE_ID)
            else it[Keys.DEFAULT_VOICE_ID] = voiceId
        }
    }

    override suspend fun setDownloadOnWifiOnly(enabled: Boolean) {
        store.edit { it[Keys.DOWNLOAD_WIFI_ONLY] = enabled }
    }

    override suspend fun setPollIntervalHours(hours: Int) {
        store.edit { it[Keys.POLL_INTERVAL_HOURS] = hours.coerceIn(1, 24) }
    }

    override suspend fun setPunctuationPause(mode: PunctuationPause) {
        store.edit { it[Keys.PUNCTUATION_PAUSE] = mode.name }
    }

    override suspend fun signIn() {
        // Just flips the persisted UI flag; the cookie capture is owned by
        // AuthViewModel (it writes to AuthRepository + SessionHydrator and
        // calls signIn() here last). Keeping signIn idempotent so callers
        // can flip it without side effects on the auth store.
        store.edit { it[Keys.SIGNED_IN] = true }
    }

    override suspend fun signOut() {
        // Tear down all three stores: the encrypted cookie header in
        // AuthRepository, the live OkHttp jar via SessionHydrator, and the
        // DataStore flag that drives the Settings UI.
        auth.clearSession()
        hydrator.clear()
        store.edit { it[Keys.SIGNED_IN] = false }
    }
}
