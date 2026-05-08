package `in`.jphe.storyvox.data.repository

import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import `in`.jphe.storyvox.data.repository.playback.PlaybackChapter
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read/write surface over chapters — both metadata (table-of-contents rows)
 * and bodies (downloaded HTML/plain text). Bodies arrive via
 * [ChapterDownloadWorker]; this repo schedules the work.
 */
interface ChapterRepository {

    fun observeChapters(fictionId: String): Flow<List<ChapterInfo>>

    /** Returns null until the body has been downloaded. */
    fun observeChapter(chapterId: String): Flow<ChapterContent?>

    fun observeDownloadState(fictionId: String): Flow<Map<String, ChapterDownloadState>>

    /** Schedule a single chapter download via WorkManager. */
    suspend fun queueChapterDownload(
        fictionId: String,
        chapterId: String,
        requireUnmetered: Boolean = true,
    )

    /** Schedule downloads for every not-yet-downloaded chapter (eager mode). */
    suspend fun queueAllMissing(
        fictionId: String,
        requireUnmetered: Boolean = true,
    )

    suspend fun markRead(chapterId: String, read: Boolean = true)

    /** Courtesy alias for the playback layer. Same effect as `markRead(true)`. */
    suspend fun markChapterPlayed(chapterId: String)

    /** Drop body bytes for chapters older than [keepLast]; keeps metadata rows. */
    suspend fun trimDownloadedBodies(fictionId: String, keepLast: Int)

    // ─── playback-layer accessors ─────────────────────────────────────────

    /**
     * Joined "everything the player needs to start a track" lookup —
     * chapter text + title + parent book's title + cover URL in one row.
     * Returns `null` when either the chapter row doesn't exist or its body
     * hasn't been downloaded yet.
     */
    suspend fun getChapter(id: String): PlaybackChapter?

    /** ID of the next chapter in reading order, or null at the end of the book. */
    suspend fun getNextChapterId(currentChapterId: String): String?

    /** ID of the previous chapter in reading order, or null at the start. */
    suspend fun getPreviousChapterId(currentChapterId: String): String?
}

@Singleton
class ChapterRepositoryImpl @Inject constructor(
    private val dao: ChapterDao,
    private val scheduler: ChapterDownloadScheduler,
) : ChapterRepository {

    override fun observeChapters(fictionId: String): Flow<List<ChapterInfo>> =
        dao.observeChapterInfosByFiction(fictionId).map { rows -> rows.map(::toInfo) }

    override fun observeChapter(chapterId: String): Flow<ChapterContent?> =
        dao.observe(chapterId).map { row ->
            if (row == null || row.htmlBody == null || row.plainBody == null) null
            else ChapterContent(
                info = row.toInfo(),
                htmlBody = row.htmlBody,
                plainBody = row.plainBody,
                notesAuthor = row.notesAuthor,
                notesAuthorPosition = row.notesAuthorPosition,
            )
        }

    override fun observeDownloadState(fictionId: String): Flow<Map<String, ChapterDownloadState>> =
        dao.observeDownloadStates(fictionId).map { rows ->
            rows.associate { it.id to it.downloadState }
        }

    override suspend fun queueChapterDownload(
        fictionId: String,
        chapterId: String,
        requireUnmetered: Boolean,
    ) {
        // Mark QUEUED *before* dispatch so observers see the pending state
        // immediately, even if the scheduler synchronously fast-paths.
        dao.setDownloadState(chapterId, ChapterDownloadState.QUEUED, System.currentTimeMillis(), null)
        scheduler.schedule(fictionId, chapterId, requireUnmetered)
    }

    override suspend fun queueAllMissing(fictionId: String, requireUnmetered: Boolean) {
        val missing = dao.missingForFiction(fictionId)
        for (chapter in missing) {
            queueChapterDownload(fictionId, chapter.id, requireUnmetered)
        }
    }

    override suspend fun markRead(chapterId: String, read: Boolean) {
        dao.setRead(chapterId, read, System.currentTimeMillis())
    }

    override suspend fun markChapterPlayed(chapterId: String) = markRead(chapterId, true)

    override suspend fun trimDownloadedBodies(fictionId: String, keepLast: Int) {
        dao.trimDownloadedBodies(fictionId, keepLast)
    }

    override suspend fun getChapter(id: String): PlaybackChapter? =
        dao.playbackChapter(id)?.let {
            // The DAO uses COALESCE on plainBody → '', so an undownloaded
            // chapter comes back with empty text. Treat that as "not yet
            // available" so the player doesn't try to speak silence.
            if (it.text.isEmpty()) null
            else PlaybackChapter(
                id = it.id,
                fictionId = it.fictionId,
                text = it.text,
                title = it.title,
                bookTitle = it.bookTitle,
                coverUrl = it.coverUrl,
            )
        }

    override suspend fun getNextChapterId(currentChapterId: String): String? =
        dao.nextChapterId(currentChapterId)

    override suspend fun getPreviousChapterId(currentChapterId: String): String? =
        dao.previousChapterId(currentChapterId)
}
