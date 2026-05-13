package `in`.jphe.storyvox.data.repository

import `in`.jphe.storyvox.data.db.dao.FictionShelfDao
import `in`.jphe.storyvox.data.db.entity.FictionShelf
import `in`.jphe.storyvox.data.db.entity.Shelf
import `in`.jphe.storyvox.data.source.model.FictionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read/write surface for the predefined-shelves feature (issue #116).
 *
 * UI talks only to this interface — never to [FictionShelfDao] directly —
 * so the mapping from persisted enum-name strings (the DB shape) to
 * [Shelf] (the type-safe shape) lives in one place.
 */
interface ShelfRepository {

    /**
     * Fictions sitting on [shelf], newest-library-add first. Only includes
     * rows where `fiction.inLibrary = 1` (a fiction the user removed from
     * Library shouldn't haunt its shelves).
     */
    fun observeByShelf(shelf: Shelf): Flow<List<FictionSummary>>

    /**
     * The set of shelves a fiction currently sits on. Stable order
     * matching [Shelf.ALL] so toggling a chip doesn't reshuffle the row.
     */
    fun observeShelvesForFiction(fictionId: String): Flow<Set<Shelf>>

    /** One-shot variant of [observeShelvesForFiction] for non-flow call sites. */
    suspend fun shelvesForFiction(fictionId: String): Set<Shelf>

    /** Add the fiction to a shelf. Idempotent — toggling on an already-on row is a no-op. */
    suspend fun add(fictionId: String, shelf: Shelf)

    /** Remove the fiction from a shelf. Idempotent. */
    suspend fun remove(fictionId: String, shelf: Shelf)

    /** Convenience — flip the membership bit. */
    suspend fun toggle(fictionId: String, shelf: Shelf) {
        if (shelf in shelvesForFiction(fictionId)) remove(fictionId, shelf) else add(fictionId, shelf)
    }

    /** Drop every shelf membership for a fiction (called when removing from library). */
    suspend fun clearForFiction(fictionId: String)
}

@Singleton
class ShelfRepositoryImpl @Inject constructor(
    private val shelfDao: FictionShelfDao,
) : ShelfRepository {

    override fun observeByShelf(shelf: Shelf): Flow<List<FictionSummary>> =
        shelfDao.observeByShelf(shelf.name).map { rows -> rows.map { it.toSummary() } }

    override fun observeShelvesForFiction(fictionId: String): Flow<Set<Shelf>> =
        shelfDao.observeShelvesForFiction(fictionId).map { names ->
            // mapNotNull tolerates unknown names — see `Shelf.fromName` kdoc.
            // Sort by enum order so the manage-sheet toggles render in
            // Reading / Read / Wishlist order regardless of insertion order.
            names.mapNotNull(Shelf::fromName).toSortedSet(compareBy { it.ordinal })
        }

    override suspend fun shelvesForFiction(fictionId: String): Set<Shelf> = withContext(Dispatchers.IO) {
        shelfDao.shelvesForFiction(fictionId).mapNotNull(Shelf::fromName).toSet()
    }

    override suspend fun add(fictionId: String, shelf: Shelf) = withContext(Dispatchers.IO) {
        shelfDao.insert(
            FictionShelf(
                fictionId = fictionId,
                shelf = shelf.name,
                addedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun remove(fictionId: String, shelf: Shelf) = withContext(Dispatchers.IO) {
        shelfDao.remove(fictionId, shelf.name)
    }

    override suspend fun clearForFiction(fictionId: String) = withContext(Dispatchers.IO) {
        shelfDao.clearForFiction(fictionId)
    }
}
