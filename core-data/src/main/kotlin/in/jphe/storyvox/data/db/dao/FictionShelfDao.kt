package `in`.jphe.storyvox.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import `in`.jphe.storyvox.data.db.entity.Fiction
import `in`.jphe.storyvox.data.db.entity.FictionShelf
import kotlinx.coroutines.flow.Flow

/**
 * Read/write surface for the `fiction_shelf` junction table (issue #116).
 *
 * - Reading lists (chip-filtered Library view) join through this table and
 *   only return library rows so that an item that was unlibraried but is
 *   still referenced by an orphan junction row (shouldn't happen — CASCADE
 *   covers it — but the join is the durable defence) doesn't show up.
 * - The membership-toggle path is a plain insert/delete pair; idempotent
 *   inserts via [OnConflictStrategy.REPLACE] keep "add the same book to the
 *   same shelf twice" a no-op rather than a crash.
 */
@Dao
interface FictionShelfDao {

    // ── Observers ────────────────────────────────────────────────────────

    /**
     * Library fictions on a given shelf, newest-first by addedToLibraryAt
     * (the same ordering as `FictionDao.observeLibrary`). Filtering
     * `f.inLibrary = 1` keeps "shelved but un-libraried" off the chip
     * results — the user removed the book from their library, so they
     * shouldn't see it on a shelf either.
     */
    @Query(
        """
        SELECT f.* FROM fiction f
        INNER JOIN fiction_shelf fs ON fs.fictionId = f.id
        WHERE fs.shelf = :shelfName AND f.inLibrary = 1
        ORDER BY f.addedToLibraryAt DESC
        """,
    )
    fun observeByShelf(shelfName: String): Flow<List<Fiction>>

    /** Shelf membership for a fiction, as the persisted enum-name strings. */
    @Query("SELECT shelf FROM fiction_shelf WHERE fictionId = :fictionId")
    fun observeShelvesForFiction(fictionId: String): Flow<List<String>>

    /** One-shot variant — used by repository code paths that aren't flow-backed. */
    @Query("SELECT shelf FROM fiction_shelf WHERE fictionId = :fictionId")
    suspend fun shelvesForFiction(fictionId: String): List<String>

    // ── Mutators ─────────────────────────────────────────────────────────

    /**
     * Insert is REPLACE so adding the same (fictionId, shelf) pair twice is
     * a no-op refresh of [FictionShelf.addedAt] rather than a crash. UI is
     * idempotent — flipping a toggle while the underlying state is already
     * matching shouldn't break.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: FictionShelf)

    @Query("DELETE FROM fiction_shelf WHERE fictionId = :fictionId AND shelf = :shelfName")
    suspend fun remove(fictionId: String, shelfName: String)

    /** Drop every shelf membership for a fiction — e.g. on library removal. */
    @Query("DELETE FROM fiction_shelf WHERE fictionId = :fictionId")
    suspend fun clearForFiction(fictionId: String)
}
