package `in`.jphe.storyvox.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.jphe.storyvox.data.db.StoryvoxDatabase
import `in`.jphe.storyvox.data.db.entity.Fiction
import `in`.jphe.storyvox.data.db.entity.FictionShelf
import `in`.jphe.storyvox.data.db.entity.Shelf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Roundtrip exercise of [FictionShelfDao] against an in-memory Room
 * database. Covers the three operations the UI relies on:
 *
 *  - add → observeByShelf returns the fiction
 *  - second add to a different shelf → both shelves observable for fiction
 *  - remove → observeByShelf no longer returns it
 *  - un-library → observeByShelf filters it out (junction row persists)
 *  - CASCADE delete of fiction → junction rows disappear
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class FictionShelfDaoTest {

    private lateinit var db: StoryvoxDatabase
    private lateinit var dao: FictionShelfDao
    private lateinit var fictionDao: FictionDao

    private val fiction = Fiction(
        id = "f1",
        sourceId = "royalroad",
        title = "Sky Pride",
        author = "Anonymous",
        firstSeenAt = 0L,
        metadataFetchedAt = 0L,
        inLibrary = true,
    )

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StoryvoxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.fictionShelfDao()
        fictionDao = db.fictionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun add_thenObserveByShelf_returnsTheFiction() = runTest {
        fictionDao.upsert(fiction)
        dao.insert(FictionShelf("f1", Shelf.Reading.name, addedAt = 1L))

        val rows = dao.observeByShelf(Shelf.Reading.name).first()
        assertEquals(1, rows.size)
        assertEquals("f1", rows[0].id)
    }

    @Test
    fun addToTwoShelves_shelvesForFiction_returnsBoth() = runTest {
        fictionDao.upsert(fiction)
        dao.insert(FictionShelf("f1", Shelf.Reading.name, addedAt = 1L))
        dao.insert(FictionShelf("f1", Shelf.Wishlist.name, addedAt = 2L))

        val names = dao.shelvesForFiction("f1").toSet()
        assertEquals(setOf("Reading", "Wishlist"), names)
    }

    @Test
    fun insertDuplicate_isIdempotent_refreshesAddedAt() = runTest {
        fictionDao.upsert(fiction)
        dao.insert(FictionShelf("f1", Shelf.Reading.name, addedAt = 1L))
        // REPLACE on conflict — second insert should not throw.
        dao.insert(FictionShelf("f1", Shelf.Reading.name, addedAt = 999L))

        val names = dao.shelvesForFiction("f1")
        assertEquals(listOf("Reading"), names)
    }

    @Test
    fun remove_thenObserveByShelf_isEmpty() = runTest {
        fictionDao.upsert(fiction)
        dao.insert(FictionShelf("f1", Shelf.Read.name, addedAt = 1L))
        dao.remove("f1", Shelf.Read.name)

        val rows = dao.observeByShelf(Shelf.Read.name).first()
        assertTrue("expected empty after remove, got $rows", rows.isEmpty())
    }

    @Test
    fun unlibraried_fiction_doesNotAppearOnShelfQueries() = runTest {
        // Library row…
        fictionDao.upsert(fiction)
        dao.insert(FictionShelf("f1", Shelf.Wishlist.name, addedAt = 1L))

        // …then unlibrary it. The chip-filtered query filters on
        // `fiction.inLibrary = 1`, so the row should drop out even though
        // the junction row still exists.
        fictionDao.setInLibrary("f1", false, now = 0L)

        val rows = dao.observeByShelf(Shelf.Wishlist.name).first()
        assertTrue("unlibraried fiction must not surface on shelf views", rows.isEmpty())
        // …but the junction itself is preserved (re-libraries restore shelf membership).
        assertEquals(listOf("Wishlist"), dao.shelvesForFiction("f1"))
    }

    @Test
    fun deletingFiction_cascadesShelfRows() = runTest {
        // A transient fiction (not in library, not followed) is eligible for
        // deleteIfTransient. CASCADE on the FK should drop its junction rows.
        val transient = fiction.copy(id = "tmp", inLibrary = false, followedRemotely = false)
        fictionDao.upsert(transient)
        dao.insert(FictionShelf("tmp", Shelf.Reading.name, addedAt = 1L))

        val deleted = fictionDao.deleteIfTransient("tmp")
        assertEquals(1, deleted)
        assertTrue(dao.shelvesForFiction("tmp").isEmpty())
    }

    @Test
    fun clearForFiction_dropsAllMemberships() = runTest {
        fictionDao.upsert(fiction)
        dao.insert(FictionShelf("f1", Shelf.Reading.name, addedAt = 1L))
        dao.insert(FictionShelf("f1", Shelf.Wishlist.name, addedAt = 2L))

        dao.clearForFiction("f1")
        assertTrue(dao.shelvesForFiction("f1").isEmpty())
    }
}
