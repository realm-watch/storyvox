package `in`.jphe.storyvox.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
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
 * Real-DataStore tests for the issue #98 Mode A / Mode B toggles.
 *
 * Same shape as [SettingsRepositoryBufferTest]: spins up a temp-file
 * DataStore so persistence is identical to production. Verifies the
 * defaults preserve v0.4.30 behavior (warmup wait + catch-up pause both
 * on by default), and that both setters round-trip through the
 * `UiSettings` flow + the `PlaybackModeConfig` snapshot/flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryModesTest {

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
            // Test-only LlmCredentialsStore (#81) — bypasses encrypted prefs.
            // Modes tests don't touch AI fields, so a no-op store is fine.
            llmCreds = `in`.jphe.storyvox.llm.LlmCredentialsStore.forTesting(),
            githubAuth = FakeGitHubAuth(),
            teamsAuth = fakeTeamsAuth(),
            rssConfig = makeFakeRssConfig(tempFolder.newFolder("rss_ds"), scope),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ---- Mode A — Warm-up Wait ----

    @Test
    fun `default warmup wait is on`() = runTest {
        // The default-on bias preserves v0.4.30's "show spinner while
        // engine warms up" behavior on first launch + on existing installs
        // that have no value persisted.
        assertEquals(true, repo.currentWarmupWait())
        assertEquals(true, repo.settings.first().warmupWait)
        assertEquals(true, repo.warmupWait.first())
    }

    @Test
    fun `setWarmupWait persists and re-emits on settings flow`() = runTest {
        repo.setWarmupWait(false)
        assertEquals(false, repo.currentWarmupWait())
        assertEquals(false, repo.settings.first().warmupWait)
        assertEquals(false, repo.warmupWait.first())
    }

    @Test
    fun `setWarmupWait round-trips both directions`() = runTest {
        repo.setWarmupWait(false)
        assertEquals(false, repo.currentWarmupWait())
        repo.setWarmupWait(true)
        assertEquals(true, repo.currentWarmupWait())
    }

    // ---- Mode B — Catch-up Pause ----

    @Test
    fun `default catchup pause is on`() = runTest {
        // Default-on preserves PR #77's pause-buffer-resume contract.
        assertEquals(true, repo.currentCatchupPause())
        assertEquals(true, repo.settings.first().catchupPause)
        assertEquals(true, repo.catchupPause.first())
    }

    @Test
    fun `setCatchupPause persists and re-emits on settings flow`() = runTest {
        repo.setCatchupPause(false)
        assertEquals(false, repo.currentCatchupPause())
        assertEquals(false, repo.settings.first().catchupPause)
        assertEquals(false, repo.catchupPause.first())
    }

    @Test
    fun `setCatchupPause round-trips both directions`() = runTest {
        repo.setCatchupPause(false)
        assertEquals(false, repo.currentCatchupPause())
        repo.setCatchupPause(true)
        assertEquals(true, repo.currentCatchupPause())
    }

    @Test
    fun `Mode A and Mode B persist independently`() = runTest {
        // Flipping Mode B must not affect Mode A and vice-versa. Catches
        // a regression where both keys end up sharing a Preferences key
        // (e.g. typo in Keys).
        repo.setWarmupWait(false)
        assertEquals(false, repo.currentWarmupWait())
        assertEquals(true, repo.currentCatchupPause())

        repo.setCatchupPause(false)
        assertEquals(false, repo.currentWarmupWait())
        assertEquals(false, repo.currentCatchupPause())

        repo.setWarmupWait(true)
        assertEquals(true, repo.currentWarmupWait())
        assertEquals(false, repo.currentCatchupPause())

        // Sanity: settings flow surfaces the same final state.
        val finalSettings = repo.settings.first()
        assertTrue(
            "expected warmupWait=true catchupPause=false, got $finalSettings",
            finalSettings.warmupWait && !finalSettings.catchupPause,
        )
    }

    // FakeAuth / FakeHydrator / palace fakes live in [SettingsRepositoryTestSupport].
}
