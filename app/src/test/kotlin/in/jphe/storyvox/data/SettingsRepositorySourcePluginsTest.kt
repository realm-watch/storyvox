package `in`.jphe.storyvox.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import `in`.jphe.storyvox.data.source.SourceIds
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Plugin-seam Phase 3 (#384) — real-DataStore tests for the registry-
 * driven `setSourcePluginEnabled` + `sourcePluginsEnabled` round-trip
 * on [SettingsRepositoryUiImpl].
 *
 * Verifies:
 *  - Round-tripping toggles for every in-tree plugin id lands in the
 *    persisted JSON map.
 *  - The one-shot [SourcePluginsMapMigration] seeds the JSON map from
 *    the legacy `pref_source_*_enabled` boolean keys on first read.
 *  - Subsequent toggles do NOT dual-write into the legacy keys (Phase
 *    3 removed the dual-write).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositorySourcePluginsTest {

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
            llmCreds = `in`.jphe.storyvox.llm.LlmCredentialsStore.forTesting(),
            githubAuth = FakeGitHubAuth(),
            teamsAuth = fakeTeamsAuth(),
            rssConfig = makeFakeRssConfig(tempFolder.newFolder("rss_ds"), scope),
            epubConfig = makeFakeEpubConfig(tempFolder.newFolder("epub_ds"), scope),
            outlineConfig = makeFakeOutlineConfig(tempFolder.newFolder("outline_ds"), scope),
            wikipediaConfig = makeFakeWikipediaConfig(tempFolder.newFolder("wiki_ds"), scope),
            notionConfig = makeFakeNotionConfig(tempFolder.newFolder("notion_ds"), scope),
            discordConfig = makeFakeDiscordConfig(tempFolder.newFolder("discord_ds"), scope),
            discordGuildDirectory = makeFakeDiscordGuildDirectory(),
            suggestedFeedsRegistry = SuggestedFeedsRegistry(),
            azureCreds = makeFakeAzureCredentials(),
            azureClient = makeFakeAzureClient(),
            azureRoster = makeFakeAzureRoster(),
            googleTokenSource = `in`.jphe.storyvox.llm.auth.GoogleOAuthTokenSource(okhttp3.OkHttpClient()),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `setSourcePluginEnabled round-trips for every in-tree plugin id`() = runTest {
        val allIds = listOf(
            SourceIds.ROYAL_ROAD, SourceIds.GITHUB, SourceIds.MEMPALACE,
            SourceIds.RSS, SourceIds.EPUB, SourceIds.OUTLINE,
            SourceIds.GUTENBERG, SourceIds.AO3, SourceIds.STANDARD_EBOOKS,
            SourceIds.WIKIPEDIA, SourceIds.WIKISOURCE,
            // Issue #417 — :source-kvmr generalized to :source-radio.
            // RADIO is the new canonical id; KVMR is kept as a one-cycle
            // migration alias and stays in the round-trip set so the
            // dual-key persistence shape is exercised.
            SourceIds.RADIO, SourceIds.KVMR,
            SourceIds.NOTION, SourceIds.HACKERNEWS, SourceIds.ARXIV,
            SourceIds.PLOS, SourceIds.DISCORD,
        )
        assertEquals(18, allIds.size)

        // Toggle each off then on, in order — verify both states
        // land in the persisted map.
        for (id in allIds) {
            repo.setSourcePluginEnabled(id, enabled = false)
            assertEquals(
                "Expected $id = false after setSourcePluginEnabled(false)",
                false,
                repo.settings.first().sourcePluginsEnabled[id],
            )
            repo.setSourcePluginEnabled(id, enabled = true)
            assertEquals(
                "Expected $id = true after setSourcePluginEnabled(true)",
                true,
                repo.settings.first().sourcePluginsEnabled[id],
            )
        }
    }

    @Test
    fun `legacy SOURCE_XXX_ENABLED keys seed the JSON map on first read`() = runTest {
        // Pre-seed legacy keys for three sources WITHOUT writing the
        // JSON map — exactly the upgrade-from-v0.5.30 state.
        val legacyRoyalRoad = booleanPreferencesKey("pref_source_royalroad_enabled")
        val legacyGitHub = booleanPreferencesKey("pref_source_github_enabled")
        val legacyKvmr = booleanPreferencesKey("pref_source_kvmr_enabled")
        store.edit { prefs ->
            prefs[legacyRoyalRoad] = false
            prefs[legacyGitHub] = true
            prefs[legacyKvmr] = false
        }

        // First read of `repo.settings` triggers the migration, which
        // seeds the JSON map from the legacy keys. Verify the
        // user's pre-migration toggle state survives.
        val first = repo.settings.first()
        assertEquals(false, first.sourcePluginsEnabled[SourceIds.ROYAL_ROAD])
        assertEquals(true, first.sourcePluginsEnabled[SourceIds.GITHUB])
        assertEquals(false, first.sourcePluginsEnabled[SourceIds.KVMR])
    }

    @Test
    fun `setSourcePluginEnabled does not dual-write into legacy boolean keys (Phase 3)`() = runTest {
        // Phase 1/2 dual-wrote each toggle into the matching legacy
        // SOURCE_XXX_ENABLED key. Phase 3 stopped doing that. This
        // test pins the new behaviour: after toggling via the
        // registry entry-point, the legacy key stays absent (or at
        // whatever value the migration seeded it to — null here for
        // a fresh install).
        val legacyHackerNews = booleanPreferencesKey("pref_source_hackernews_enabled")
        repo.setSourcePluginEnabled(SourceIds.HACKERNEWS, enabled = true)

        val prefs = store.data.first()
        assertNull(
            "Phase 3: legacy SOURCE_HACKERNEWS_ENABLED should not be written by setSourcePluginEnabled",
            prefs[legacyHackerNews],
        )
        // ...but the JSON map should reflect the toggle.
        assertEquals(
            true,
            repo.settings.first().sourcePluginsEnabled[SourceIds.HACKERNEWS],
        )
    }

    @Test
    fun `unknown plugin ids still land in the map for forward-compat`() = runTest {
        // An out-of-tree plugin pre-populating its toggle state before
        // its @SourcePlugin annotation lands should round-trip.
        repo.setSourcePluginEnabled("future-backend", enabled = true)
        assertEquals(true, repo.settings.first().sourcePluginsEnabled["future-backend"])
    }

    @Test
    fun `independent toggles do not affect each other`() = runTest {
        repo.setSourcePluginEnabled(SourceIds.NOTION, enabled = false)
        repo.setSourcePluginEnabled(SourceIds.WIKIPEDIA, enabled = true)
        val snapshot = repo.settings.first().sourcePluginsEnabled

        assertEquals(false, snapshot[SourceIds.NOTION])
        assertEquals(true, snapshot[SourceIds.WIKIPEDIA])
        // No cross-contamination — these are two independent keys.
        assertTrue(snapshot.containsKey(SourceIds.NOTION))
        assertTrue(snapshot.containsKey(SourceIds.WIKIPEDIA))
    }
}
