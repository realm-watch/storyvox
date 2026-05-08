package `in`.jphe.storyvox.playback.voice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Real-DataStore tests — no fakes, no mocks. We spin up a temp-file
 * preferences DataStore against a [TemporaryFolder] so the persistence
 * layer is exactly what production runs against. Robolectric isn't
 * needed: [PreferenceDataStoreFactory.create] takes a plain file
 * producer and works on the JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceFavoritesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var favorites: VoiceFavorites

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val file = File(tempFolder.newFolder(), "voice_favorites_v1.preferences_pb")
        store = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
        favorites = VoiceFavorites.forTesting(store)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `initial state is empty`() = runTest {
        assertEquals(emptySet<String>(), favorites.favoriteIds.first())
    }

    @Test
    fun `toggle pins a voice when absent`() = runTest {
        favorites.toggle("piper_lessac_en_US_high")
        assertEquals(
            setOf("piper_lessac_en_US_high"),
            favorites.favoriteIds.first(),
        )
    }

    @Test
    fun `toggle unpins a voice when present`() = runTest {
        favorites.toggle("piper_amy_en_US_medium")
        favorites.toggle("piper_amy_en_US_medium")
        assertEquals(emptySet<String>(), favorites.favoriteIds.first())
    }

    @Test
    fun `toggle is independent across voices`() = runTest {
        favorites.toggle("kokoro_heart_en_US_3")
        favorites.toggle("piper_glados_en_US_high")
        favorites.toggle("kokoro_bella_en_US_2")
        // Unpin one of them — the other two should remain.
        favorites.toggle("piper_glados_en_US_high")

        assertEquals(
            setOf("kokoro_heart_en_US_3", "kokoro_bella_en_US_2"),
            favorites.favoriteIds.first(),
        )
    }

    @Test
    fun `setFavorite true is idempotent`() = runTest {
        favorites.setFavorite("piper_cori_en_GB_high", favorite = true)
        favorites.setFavorite("piper_cori_en_GB_high", favorite = true)
        favorites.setFavorite("piper_cori_en_GB_high", favorite = true)
        assertEquals(
            setOf("piper_cori_en_GB_high"),
            favorites.favoriteIds.first(),
        )
    }

    @Test
    fun `setFavorite false on absent voice is a no-op`() = runTest {
        favorites.setFavorite("not_a_voice", favorite = false)
        assertEquals(emptySet<String>(), favorites.favoriteIds.first())
    }

    @Test
    fun `setFavorite false unpins`() = runTest {
        favorites.toggle("kokoro_aoede_en_US_1")
        assertTrue(favorites.favoriteIds.first().contains("kokoro_aoede_en_US_1"))

        favorites.setFavorite("kokoro_aoede_en_US_1", favorite = false)
        assertFalse(favorites.favoriteIds.first().contains("kokoro_aoede_en_US_1"))
    }

    @Test
    fun `flow emits each toggled state in order`() = runTest {
        // Capture the trajectory: empty → +A → +B → -A. Each call
        // settles before the next thanks to UnconfinedTestDispatcher.
        val seen = mutableListOf<Set<String>>()
        seen += favorites.favoriteIds.first()
        favorites.toggle("a")
        seen += favorites.favoriteIds.first()
        favorites.toggle("b")
        seen += favorites.favoriteIds.first()
        favorites.toggle("a")
        seen += favorites.favoriteIds.first()

        assertEquals(
            listOf(
                emptySet(),
                setOf("a"),
                setOf("a", "b"),
                setOf("b"),
            ),
            seen,
        )
    }
}
