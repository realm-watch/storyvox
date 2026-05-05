package `in`.jphe.storyvox.data.repository

import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.data.db.entity.Fiction
import `in`.jphe.storyvox.data.repository.playback.LibraryItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only library accessor used by the playback layer to populate Auto's
 * "Library" rail. The richer write surface (add/remove, download mode,
 * pinned voice) lives on [FictionRepository].
 */
interface LibraryRepository {
    suspend fun snapshot(): List<LibraryItem>
}

@Singleton
class LibraryRepositoryImpl @Inject constructor(
    private val dao: FictionDao,
) : LibraryRepository {
    override suspend fun snapshot(): List<LibraryItem> =
        dao.librarySnapshot().map(Fiction::toLibraryItem)
}

private fun Fiction.toLibraryItem(): LibraryItem = LibraryItem(
    id = id,
    title = title,
    author = author,
    coverUrl = coverUrl,
)
