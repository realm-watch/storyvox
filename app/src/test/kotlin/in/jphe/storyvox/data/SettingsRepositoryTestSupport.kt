package `in`.jphe.storyvox.data

import android.content.SharedPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import `in`.jphe.storyvox.data.auth.SessionHydrator
import `in`.jphe.storyvox.data.auth.SessionState
import `in`.jphe.storyvox.data.repository.AuthRepository
import `in`.jphe.storyvox.source.mempalace.config.PalaceConfig
import `in`.jphe.storyvox.source.mempalace.config.PalaceConfigState
import `in`.jphe.storyvox.source.mempalace.net.PalaceDaemonApi
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient

/**
 * Shared test fixtures for the three [SettingsRepositoryUiImpl] tests
 * (Buffer, Modes, PunctuationPause). Each test spins up its own temp-file
 * DataStore for the settings store; the palace surface is required by
 * the constructor since #79 but isn't exercised by these tests, so we
 * stub it with a real [PalaceConfigImpl] over a separate temp DataStore
 * + an in-memory [SharedPreferences] stub, and wire a [PalaceDaemonApi]
 * at a never-touched config flow.
 *
 * Extracting these here (instead of inlining identical helpers in three
 * places) keeps the surface stable as the [SettingsRepositoryUi] interface
 * grows — when a future contract member needs a new fake, one edit covers
 * every test fixture. Mirrors the rationale behind `Hilt`-shared test
 * doubles in this codebase.
 */
internal class FakeAuth : AuthRepository {
    private val state = MutableStateFlow<SessionState>(SessionState.Anonymous)
    override val sessionState: StateFlow<SessionState> = state.asStateFlow()
    override suspend fun captureSession(
        cookieHeader: String,
        userDisplayName: String?,
        userId: String?,
        expiresAt: Long?,
    ) = Unit
    override suspend fun clearSession() = Unit
    override suspend fun cookieHeader(): String? = null
    override suspend fun verifyOrExpire(): SessionState = SessionState.Anonymous
}

internal class FakeHydrator : SessionHydrator {
    override fun hydrate(cookies: Map<String, String>) = Unit
    override fun clear() = Unit
}

/**
 * Real [PalaceConfigImpl] backed by a temp DataStore + an in-memory
 * SharedPreferences stub. The settings-tests don't exercise palace
 * state, but the repo's `settings` flow joins on it via combine(), so
 * we need a flow that emits at least once.
 */
internal fun makeFakePalaceConfig(
    dir: File,
    scope: CoroutineScope,
): PalaceConfigImpl {
    val palaceStore = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { File(dir, "storyvox_palace.preferences_pb") },
    )
    return PalaceConfigImpl(palaceStore, FakeSecrets())
}

/**
 * Real [PalaceDaemonApi] over an OkHttpClient + a fake config that emits
 * an empty [PalaceConfigState]. No HTTP is exercised by the settings
 * tests; the dep is there because the repo signature requires it.
 */
internal fun makeFakePalaceApi(): PalaceDaemonApi =
    PalaceDaemonApi(
        httpClient = OkHttpClient(),
        config = object : PalaceConfig {
            override val state: Flow<PalaceConfigState> = flowOf(PalaceConfigState("", ""))
            override suspend fun current() = PalaceConfigState("", "")
        },
    )

/**
 * Minimal SharedPreferences stub — only `getString` / `edit` are reached
 * by the palace code paths the test fixtures touch.
 */
internal class FakeSecrets : SharedPreferences {
    private val map = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String, defValue: String?): String? =
        map[key] as? String ?: defValue
    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = (map[key] as? Int) ?: defValue
    override fun getLong(key: String, defValue: Long): Long = (map[key] as? Long) ?: defValue
    override fun getFloat(key: String, defValue: Float): Float =
        (map[key] as? Float) ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        (map[key] as? Boolean) ?: defValue
    override fun contains(key: String): Boolean = key in map
    override fun edit(): SharedPreferences.Editor =
        object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = apply { map[key] = value }
            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ) = apply { map[key] = values }
            override fun putInt(key: String, value: Int) = apply { map[key] = value }
            override fun putLong(key: String, value: Long) = apply { map[key] = value }
            override fun putFloat(key: String, value: Float) = apply { map[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { map[key] = value }
            override fun remove(key: String) = apply { map.remove(key) }
            override fun clear() = apply { map.clear() }
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
    override fun registerOnSharedPreferenceChangeListener(
        l: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        l: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}
