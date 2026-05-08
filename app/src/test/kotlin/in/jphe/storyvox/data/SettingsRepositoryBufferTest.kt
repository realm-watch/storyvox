package `in`.jphe.storyvox.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import `in`.jphe.storyvox.data.auth.SessionHydrator
import `in`.jphe.storyvox.data.auth.SessionState
import `in`.jphe.storyvox.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import `in`.jphe.storyvox.feature.api.BUFFER_DEFAULT_CHUNKS
import `in`.jphe.storyvox.feature.api.BUFFER_MAX_CHUNKS
import `in`.jphe.storyvox.feature.api.BUFFER_MIN_CHUNKS
import `in`.jphe.storyvox.feature.api.BUFFER_RECOMMENDED_MAX_CHUNKS
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Real-DataStore tests for the issue #84 buffer slider persistence.
 *
 * Spins up a temp-file `PreferenceDataStoreFactory` against a JUnit
 * [TemporaryFolder] so the persistence layer is exactly what production
 * runs against — same approach as `VoiceFavoritesTest`. Stubs Auth /
 * SessionHydrator since those aren't exercised by the buffer code path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryBufferTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var repo: SettingsRepositoryUiImpl

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val file = File(tempFolder.newFolder(), "storyvox_settings.preferences_pb")
        store = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
        repo = SettingsRepositoryUiImpl(
            store = store,
            auth = FakeAuth(),
            hydrator = FakeHydrator(),
            palaceConfig = makeFakePalaceConfig(tempFolder.newFolder("palace_ds"), scope),
            palaceApi = makeFakePalaceApi(),
            // Test-only LlmCredentialsStore — bypasses encrypted prefs.
            // The buffer-related tests don't touch AI fields, so a
            // no-op store is fine.
            llmCreds = `in`.jphe.storyvox.llm.LlmCredentialsStore.forTesting(),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `default buffer is the documented default`() = runTest {
        assertEquals(BUFFER_DEFAULT_CHUNKS, repo.currentBufferChunks())
        assertEquals(BUFFER_DEFAULT_CHUNKS, repo.settings.first().playbackBufferChunks)
    }

    @Test
    fun `setPlaybackBufferChunks persists and re-emits on the flow`() = runTest {
        repo.setPlaybackBufferChunks(64)
        assertEquals(64, repo.currentBufferChunks())
        assertEquals(64, repo.settings.first().playbackBufferChunks)
    }

    @Test
    fun `setPlaybackBufferChunks accepts the recommended-max tick value`() = runTest {
        repo.setPlaybackBufferChunks(BUFFER_RECOMMENDED_MAX_CHUNKS)
        assertEquals(BUFFER_RECOMMENDED_MAX_CHUNKS, repo.currentBufferChunks())
    }

    @Test
    fun `setPlaybackBufferChunks accepts values past the recommended max`() = runTest {
        // Issue #84 — the slider intentionally goes past the conservative tick.
        // Persistence must not clamp at the recommended max; only the absolute
        // mechanical bounds apply.
        val past = BUFFER_RECOMMENDED_MAX_CHUNKS * 8
        repo.setPlaybackBufferChunks(past)
        assertEquals(past, repo.currentBufferChunks())
        assertTrue(
            "expected stored value past recommended max, got ${repo.currentBufferChunks()}",
            repo.currentBufferChunks() > BUFFER_RECOMMENDED_MAX_CHUNKS,
        )
    }

    @Test
    fun `setPlaybackBufferChunks clamps below the minimum`() = runTest {
        repo.setPlaybackBufferChunks(0)
        assertEquals(BUFFER_MIN_CHUNKS, repo.currentBufferChunks())
    }

    @Test
    fun `setPlaybackBufferChunks clamps above the mechanical maximum`() = runTest {
        repo.setPlaybackBufferChunks(BUFFER_MAX_CHUNKS * 10)
        assertEquals(BUFFER_MAX_CHUNKS, repo.currentBufferChunks())
    }

    @Test
    fun `setPlaybackBufferChunks accepts the mechanical maximum exactly`() = runTest {
        repo.setPlaybackBufferChunks(BUFFER_MAX_CHUNKS)
        assertEquals(BUFFER_MAX_CHUNKS, repo.currentBufferChunks())
    }

    private class FakeAuth : AuthRepository {
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

    private class FakeHydrator : SessionHydrator {
        override fun hydrate(cookies: Map<String, String>) = Unit
        override fun clear() = Unit
    }

    /**
     * Real [PalaceConfigImpl] backed by a temp DataStore + an in-memory
     * SharedPreferences stub. The buffer-test fixture doesn't exercise
     * the palace state, but the repo's settings flow joins on it via
     * combine(), so we need a flow that emits at least once.
     */
    private fun makeFakePalaceConfig(
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
     * Real [PalaceDaemonApi] over an OkHttpClient + the same fake config.
     * No HTTP is exercised by the buffer tests; the dep is there because
     * the repo signature requires it.
     */
    private fun makeFakePalaceApi(): `in`.jphe.storyvox.source.mempalace.net.PalaceDaemonApi =
        `in`.jphe.storyvox.source.mempalace.net.PalaceDaemonApi(
            httpClient = okhttp3.OkHttpClient(),
            config = object : `in`.jphe.storyvox.source.mempalace.config.PalaceConfig {
                override val state: kotlinx.coroutines.flow.Flow<
                    `in`.jphe.storyvox.source.mempalace.config.PalaceConfigState
                > = kotlinx.coroutines.flow.flowOf(
                    `in`.jphe.storyvox.source.mempalace.config.PalaceConfigState("", ""),
                )
                override suspend fun current() =
                    `in`.jphe.storyvox.source.mempalace.config.PalaceConfigState("", "")
            },
        )

    /** Minimal SharedPreferences stub — only `getString` is reached by
     *  the palace code paths the test fixture touches. */
    private class FakeSecrets : android.content.SharedPreferences {
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
        override fun edit(): android.content.SharedPreferences.Editor =
            object : android.content.SharedPreferences.Editor {
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
            l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            l: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }
}
