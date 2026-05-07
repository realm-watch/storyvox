package `in`.jphe.storyvox.data.repository

import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.ChapterDownloadStateRow
import `in`.jphe.storyvox.data.db.dao.ChapterInfoRow
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.data.db.dao.PlaybackChapterRow
import `in`.jphe.storyvox.data.db.dao.UnreadChapterRow
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import `in`.jphe.storyvox.data.db.entity.DownloadMode
import `in`.jphe.storyvox.data.db.entity.Fiction
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.FictionSourceEvent
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FictionRepositoryImplTest {

    // -- Fixtures ----------------------------------------------------------------

    private fun summary(id: String, sourceId: String = "rr", title: String = id) =
        FictionSummary(id = id, sourceId = sourceId, title = title, author = "auth-$id")

    private fun detail(id: String, sourceId: String = "rr", chapterCount: Int = 1) =
        FictionDetail(
            summary = summary(id, sourceId, title = "title-$id"),
            chapters = (0 until chapterCount).map {
                ChapterInfo(
                    id = "$id-c$it",
                    sourceChapterId = "src-$it",
                    index = it,
                    title = "chapter $it",
                )
            },
            wordCount = 1234,
        )

    // -- Fake DAOs ---------------------------------------------------------------

    /**
     * Hand-rolled fake. Backs the public DAO surface used by FictionRepositoryImpl
     * with a MutableMap; observe() is a MutableStateFlow keyed by id so Flow
     * consumers see the latest value. Only the methods the repository touches
     * are real; the rest are stubbed to TODO() so any future unintended caller
     * surfaces immediately.
     */
    private class FakeFictionDao : FictionDao {
        val rows = mutableMapOf<String, Fiction>()
        private val observers = mutableMapOf<String, MutableStateFlow<Fiction?>>()
        private val library = MutableStateFlow<List<Fiction>>(emptyList())
        private val followsRemote = MutableStateFlow<List<Fiction>>(emptyList())
        val callLog = mutableListOf<String>()

        private fun rebuildDerived() {
            library.value = rows.values
                .filter { it.inLibrary }
                .sortedByDescending { it.addedToLibraryAt ?: 0L }
            followsRemote.value = rows.values
                .filter { it.followedRemotely }
                .sortedByDescending { it.lastUpdatedAt ?: 0L }
        }

        private fun publish(id: String) {
            observers[id]?.value = rows[id]
            rebuildDerived()
        }

        override fun observe(id: String): Flow<Fiction?> =
            observers.getOrPut(id) { MutableStateFlow(rows[id]) }

        override suspend fun get(id: String): Fiction? {
            callLog += "get($id)"
            return rows[id]
        }

        override suspend fun getMany(ids: List<String>): List<Fiction> =
            ids.mapNotNull { rows[it] }

        override fun observeLibrary(): Flow<List<Fiction>> = library

        override suspend fun librarySnapshot(): List<Fiction> = library.value

        override fun observeFollowsRemote(): Flow<List<Fiction>> = followsRemote

        override suspend fun followsSnapshot(): List<Fiction> = followsRemote.value

        override suspend fun insertIfNew(fiction: Fiction): Long {
            if (rows.putIfAbsent(fiction.id, fiction) == null) {
                callLog += "insertIfNew(${fiction.id})"
                publish(fiction.id)
                return 1L
            }
            return -1L
        }

        override suspend fun upsert(fiction: Fiction) {
            callLog += "upsert(${fiction.id})"
            rows[fiction.id] = fiction
            publish(fiction.id)
        }

        override suspend fun update(fiction: Fiction) {
            rows[fiction.id] = fiction
            publish(fiction.id)
        }

        override suspend fun setInLibrary(id: String, inLibrary: Boolean, now: Long) {
            callLog += "setInLibrary($id, $inLibrary, $now)"
            val r = rows[id] ?: return
            rows[id] = r.copy(
                inLibrary = inLibrary,
                addedToLibraryAt = if (inLibrary) now else r.addedToLibraryAt,
            )
            publish(id)
        }

        override suspend fun setFollowedRemote(id: String, followed: Boolean) {
            callLog += "setFollowedRemote($id, $followed)"
            val r = rows[id] ?: return
            rows[id] = r.copy(followedRemotely = followed)
            publish(id)
        }

        override suspend fun setDownloadMode(id: String, mode: DownloadMode?) {
            callLog += "setDownloadMode($id, $mode)"
            val r = rows[id] ?: return
            rows[id] = r.copy(downloadMode = mode)
            publish(id)
        }

        override suspend fun setPinnedVoice(id: String, voiceId: String?, locale: String?) {
            callLog += "setPinnedVoice($id, $voiceId, $locale)"
            val r = rows[id] ?: return
            rows[id] = r.copy(pinnedVoiceId = voiceId, pinnedVoiceLocale = locale)
            publish(id)
        }

        override suspend fun touchMetadata(id: String, now: Long) {
            val r = rows[id] ?: return
            rows[id] = r.copy(metadataFetchedAt = now)
        }

        override suspend fun upsertAllPreservingUserState(fictions: List<Fiction>) {
            callLog += "upsertAllPreservingUserState(${fictions.map { it.id }})"
            for (incoming in fictions) {
                val existing = rows[incoming.id]
                val merged = if (existing == null) incoming else incoming.copy(
                    inLibrary = existing.inLibrary,
                    addedToLibraryAt = existing.addedToLibraryAt,
                    followedRemotely = existing.followedRemotely,
                    downloadMode = existing.downloadMode,
                    pinnedVoiceId = existing.pinnedVoiceId,
                    pinnedVoiceLocale = existing.pinnedVoiceLocale,
                    notesEverSeen = existing.notesEverSeen,
                    firstSeenAt = existing.firstSeenAt,
                )
                rows[merged.id] = merged
                publish(merged.id)
            }
        }

        override suspend fun deleteIfTransient(id: String): Int {
            callLog += "deleteIfTransient($id)"
            val r = rows[id] ?: return 0
            return if (!r.inLibrary && !r.followedRemotely) {
                rows.remove(id)
                publish(id)
                1
            } else {
                0
            }
        }
    }

    /** Hand-rolled fake for ChapterDao. Repository touches a small subset. */
    private class FakeChapterDao : ChapterDao {
        val rows = mutableMapOf<String, Chapter>()
        private val infoFeeds = mutableMapOf<String, MutableStateFlow<List<ChapterInfoRow>>>()
        val callLog = mutableListOf<String>()

        private fun fictionRows(fictionId: String): List<ChapterInfoRow> =
            rows.values
                .filter { it.fictionId == fictionId }
                .sortedBy { it.index }
                .map {
                    ChapterInfoRow(
                        id = it.id,
                        sourceChapterId = it.sourceChapterId,
                        index = it.index,
                        title = it.title,
                        publishedAt = it.publishedAt,
                        wordCount = it.wordCount,
                    )
                }

        private fun publishInfo(fictionId: String) {
            infoFeeds[fictionId]?.value = fictionRows(fictionId)
        }

        override fun observeChapterInfosByFiction(fictionId: String): Flow<List<ChapterInfoRow>> =
            infoFeeds.getOrPut(fictionId) { MutableStateFlow(fictionRows(fictionId)) }

        override fun observe(id: String): Flow<Chapter?> = MutableStateFlow(rows[id])

        override suspend fun get(id: String): Chapter? {
            callLog += "get($id)"
            return rows[id]
        }

        override fun observeDownloadStates(fictionId: String): Flow<List<ChapterDownloadStateRow>> =
            MutableStateFlow(emptyList())

        override suspend fun missingForFiction(fictionId: String): List<Chapter> =
            rows.values.filter {
                it.fictionId == fictionId && it.downloadState == ChapterDownloadState.NOT_DOWNLOADED
            }.sortedBy { it.index }

        override suspend fun maxIndex(fictionId: String): Int? =
            rows.values.filter { it.fictionId == fictionId }.maxOfOrNull { it.index }

        override suspend fun nextChapterId(currentId: String): String? = null
        override suspend fun previousChapterId(currentId: String): String? = null
        override suspend fun playbackChapter(id: String): PlaybackChapterRow? = null
        override suspend fun unreadChapters(limit: Int): List<UnreadChapterRow> = emptyList()
        override suspend fun setRead(id: String, read: Boolean, now: Long) {}
        override suspend fun trimDownloadedBodies(fictionId: String, keepLast: Int) {}
        override suspend fun setDownloadState(
            id: String,
            state: ChapterDownloadState,
            now: Long,
            error: String?,
        ) {}
        override suspend fun setBody(
            id: String,
            html: String,
            plain: String,
            checksum: String,
            notesAuthor: String?,
            notesAuthorPosition: String?,
            now: Long,
        ) {}

        override suspend fun upsert(chapter: Chapter) {
            callLog += "upsert(${chapter.id})"
            rows[chapter.id] = chapter
            publishInfo(chapter.fictionId)
        }

        override suspend fun upsertAll(chapters: List<Chapter>) {
            callLog += "upsertAll(${chapters.map { it.id }})"
            chapters.forEach { rows[it.id] = it }
            chapters.map { it.fictionId }.toSet().forEach(::publishInfo)
        }
    }

    // -- Fake source -------------------------------------------------------------

    /**
     * Programmable FictionSource: the test sets `nextX` before exercising the
     * repository call, then asserts on the recorded call log + result mapping.
     * Defaults to a generic NetworkError so an un-stubbed call fails loudly.
     */
    private class FakeSource(override val id: String = "rr") : FictionSource {
        override val displayName: String = "Fake $id"

        var detailResult: FictionResult<FictionDetail> =
            FictionResult.NetworkError("not stubbed")
        var followsListResult: FictionResult<ListPage<FictionSummary>> =
            FictionResult.NetworkError("not stubbed")
        var setFollowedResult: FictionResult<Unit> =
            FictionResult.NetworkError("not stubbed")
        var popularResult: FictionResult<ListPage<FictionSummary>> =
            FictionResult.NetworkError("not stubbed")
        var latestResult: FictionResult<ListPage<FictionSummary>> =
            FictionResult.NetworkError("not stubbed")
        var byGenreResult: FictionResult<ListPage<FictionSummary>> =
            FictionResult.NetworkError("not stubbed")
        var searchResult: FictionResult<ListPage<FictionSummary>> =
            FictionResult.NetworkError("not stubbed")
        var genresResult: FictionResult<List<String>> =
            FictionResult.NetworkError("not stubbed")
        var chapterResult: FictionResult<ChapterContent> =
            FictionResult.NetworkError("not stubbed")

        val callLog = mutableListOf<String>()

        override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> {
            callLog += "popular($page)"; return popularResult
        }
        override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> {
            callLog += "latest($page)"; return latestResult
        }
        override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> {
            callLog += "byGenre($genre,$page)"; return byGenreResult
        }
        override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
            callLog += "search(${query.term})"; return searchResult
        }
        override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
            callLog += "fictionDetail($fictionId)"; return detailResult
        }
        override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> {
            callLog += "chapter($fictionId,$chapterId)"; return chapterResult
        }
        override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> {
            callLog += "followsList($page)"; return followsListResult
        }
        override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> {
            callLog += "setFollowed($fictionId,$followed)"; return setFollowedResult
        }
        override suspend fun genres(): FictionResult<List<String>> {
            callLog += "genres"; return genresResult
        }
        override fun events(): Flow<FictionSourceEvent> = kotlinx.coroutines.flow.emptyFlow()
    }

    // -- repo() ------------------------------------------------------------------

    private fun repo(
        sources: Map<String, FictionSource> = mapOf("rr" to FakeSource("rr")),
        fictionDao: FakeFictionDao = FakeFictionDao(),
        chapterDao: FakeChapterDao = FakeChapterDao(),
    ) = Triple(
        FictionRepositoryImpl(sources, fictionDao, chapterDao),
        fictionDao,
        chapterDao,
    )

    // -- sourceFor() routing -----------------------------------------------------

    @Test fun `single bound source is used as fallback when sourceId is null`() = runTest {
        val src = FakeSource("rr").apply {
            popularResult = FictionResult.Success(ListPage(emptyList(), 1, false))
        }
        val (r, _, _) = repo(sources = mapOf("rr" to src))
        val result = r.browsePopular(1)
        assertTrue(result is FictionResult.Success)
        assertEquals(listOf("popular(1)"), src.callLog)
    }

    @Test fun `multi-bound source routes by row's sourceId on refreshDetail`() = runTest {
        val rr = FakeSource("rr").apply {
            detailResult = FictionResult.Success(detail("123", sourceId = "rr"))
        }
        val gh = FakeSource("github").apply {
            detailResult = FictionResult.Success(detail("123", sourceId = "github"))
        }
        val (r, fictionDao, _) = repo(sources = mapOf("rr" to rr, "github" to gh))
        // Pre-seed the row so refreshDetail's lookup finds the sourceId.
        fictionDao.rows["123"] = Fiction(
            id = "123", sourceId = "github", title = "", author = "",
            firstSeenAt = 0L, metadataFetchedAt = 0L,
        )

        val result = r.refreshDetail("123")
        assertTrue(result is FictionResult.Success)
        assertEquals(listOf("fictionDetail(123)"), gh.callLog)
        assertEquals(emptyList<String>(), rr.callLog)
    }

    @Test fun `multi-bound with missing row falls through to error on null sourceId`() = runTest {
        val rr = FakeSource("rr")
        val gh = FakeSource("github")
        val (r, _, _) = repo(sources = mapOf("rr" to rr, "github" to gh))

        val ex = try {
            // observeFiction doesn't hit sourceFor(); refreshDetail does, but
            // with an absent row passes null → with multi-source bound and no
            // singleOrNull match, sourceFor() throws.
            r.refreshDetail("404")
            null
        } catch (t: IllegalStateException) {
            t
        }
        assertNotNull("expected IllegalStateException for unknown sourceId in multi-bound map", ex)
    }

    // -- browse / search / genres ------------------------------------------------

    @Test fun `browsePopular caches successful pages via upsertAllPreservingUserState`() = runTest {
        val src = FakeSource("rr").apply {
            popularResult = FictionResult.Success(
                ListPage(items = listOf(summary("a"), summary("b")), page = 1, hasNext = true),
            )
        }
        val (r, fictionDao, _) = repo(sources = mapOf("rr" to src))

        val result = r.browsePopular(1)
        assertTrue(result is FictionResult.Success)
        assertTrue(fictionDao.callLog.any { it.startsWith("upsertAllPreservingUserState") })
        assertEquals(2, fictionDao.rows.size)
    }

    @Test fun `browsePopular does NOT write on Failure`() = runTest {
        val src = FakeSource("rr").apply {
            popularResult = FictionResult.NetworkError("boom")
        }
        val (r, fictionDao, _) = repo(sources = mapOf("rr" to src))

        val result = r.browsePopular(1)
        assertTrue(result is FictionResult.Failure)
        assertTrue(
            "no upsert should occur on failure",
            fictionDao.callLog.none { it.startsWith("upsertAllPreservingUserState") },
        )
        assertEquals(0, fictionDao.rows.size)
    }

    @Test fun `browseLatest, browseByGenre, search all flow through cacheListing`() = runTest {
        val src = FakeSource("rr").apply {
            latestResult = FictionResult.Success(ListPage(listOf(summary("a")), 1, false))
            byGenreResult = FictionResult.Success(ListPage(listOf(summary("b")), 1, false))
            searchResult = FictionResult.Success(ListPage(listOf(summary("c")), 1, false))
        }
        val (r, fictionDao, _) = repo(sources = mapOf("rr" to src))

        r.browseLatest(1)
        r.browseByGenre("fantasy", 1)
        r.search(SearchQuery(term = "x"))

        assertEquals(setOf("a", "b", "c"), fictionDao.rows.keys)
    }

    @Test fun `genres() passes through source result without caching`() = runTest {
        val src = FakeSource("rr").apply {
            genresResult = FictionResult.Success(listOf("fantasy", "litrpg"))
        }
        val (r, fictionDao, _) = repo(sources = mapOf("rr" to src))

        val result = r.genres()
        assertTrue(result is FictionResult.Success)
        assertEquals(listOf("fantasy", "litrpg"), (result as FictionResult.Success).value)
        assertEquals(emptyList<String>(), fictionDao.callLog) // no DB writes
    }

    // -- refreshDetail -----------------------------------------------------------

    @Test fun `refreshDetail success writes detail and chapters`() = runTest {
        val src = FakeSource("rr").apply {
            detailResult = FictionResult.Success(detail("99", chapterCount = 3))
        }
        val (r, fictionDao, chapterDao) = repo(sources = mapOf("rr" to src))

        val result = r.refreshDetail("99")
        assertTrue(result is FictionResult.Success)
        assertNotNull(fictionDao.rows["99"])
        assertEquals(3, chapterDao.rows.size)
    }

    @Test fun `refreshDetail failure does NOT touch the DB`() = runTest {
        val src = FakeSource("rr").apply {
            detailResult = FictionResult.NotFound()
        }
        val (r, fictionDao, chapterDao) = repo(sources = mapOf("rr" to src))

        val result = r.refreshDetail("99")
        assertTrue(result is FictionResult.NotFound)
        assertEquals(0, fictionDao.rows.size)
        assertEquals(0, chapterDao.rows.size)
    }

    @Test fun `refreshDetail preserves existing chapter body bytes on merge`() = runTest {
        val src = FakeSource("rr").apply {
            detailResult = FictionResult.Success(detail("99", chapterCount = 2))
        }
        val (r, _, chapterDao) = repo(sources = mapOf("rr" to src))
        // Pre-seed chapter 0 with downloaded body.
        chapterDao.rows["99-c0"] = Chapter(
            id = "99-c0", fictionId = "99", sourceChapterId = "src-0",
            index = 0, title = "old title", htmlBody = "<p>body</p>",
            plainBody = "body", bodyChecksum = "abc",
            bodyFetchedAt = 1234L,
            downloadState = ChapterDownloadState.DOWNLOADED,
            userMarkedRead = true, firstReadAt = 5678L,
        )

        r.refreshDetail("99")

        val merged = chapterDao.rows["99-c0"]!!
        assertEquals("body bytes preserved", "body", merged.plainBody)
        assertEquals("checksum preserved", "abc", merged.bodyChecksum)
        assertEquals("downloaded state preserved", ChapterDownloadState.DOWNLOADED, merged.downloadState)
        assertEquals("read flag preserved", true, merged.userMarkedRead)
        assertEquals("first-read timestamp preserved", 5678L, merged.firstReadAt)
        // Title from the fresh detail page wins (mutable upstream metadata).
        assertEquals("chapter 0", merged.title)
    }

    // -- refreshRemoteFollows ---------------------------------------------------

    @Test fun `refreshRemoteFollows upserts incoming and clears gone-from-remote`() = runTest {
        val src = FakeSource("rr").apply {
            followsListResult = FictionResult.Success(
                ListPage(listOf(summary("keep"), summary("new")), 1, false),
            )
        }
        val (r, fictionDao, _) = repo(sources = mapOf("rr" to src))
        // Pre-seed rows: "keep" already followedRemotely (should stay),
        // "gone" previously followedRemotely (should clear).
        fictionDao.rows["keep"] = summary("keep").toEntity(0L).copy(followedRemotely = true)
        fictionDao.rows["gone"] = summary("gone").toEntity(0L).copy(followedRemotely = true)

        val result = r.refreshRemoteFollows()
        assertTrue(result is FictionResult.Success)
        assertEquals(true, fictionDao.rows["keep"]?.followedRemotely)
        assertEquals(true, fictionDao.rows["new"]?.followedRemotely)
        assertEquals(false, fictionDao.rows["gone"]?.followedRemotely)
    }

    @Test fun `refreshRemoteFollows AuthRequired propagates without DB writes`() = runTest {
        val src = FakeSource("rr").apply {
            followsListResult = FictionResult.AuthRequired()
        }
        val (r, fictionDao, _) = repo(sources = mapOf("rr" to src))

        val result = r.refreshRemoteFollows()
        assertTrue(result is FictionResult.AuthRequired)
        assertEquals(0, fictionDao.rows.size)
    }

    @Test fun `refreshRemoteFollows preserves richer detail-page fields on merge`() = runTest {
        val src = FakeSource("rr").apply {
            // Incoming row has blank title — repository must keep existing.
            followsListResult = FictionResult.Success(
                ListPage(
                    listOf(summary("keep").copy(title = "", coverUrl = null, tags = emptyList())),
                    1, false,
                ),
            )
        }
        val (r, fictionDao, _) = repo(sources = mapOf("rr" to src))
        fictionDao.rows["keep"] = summary("keep").toEntity(0L).copy(
            title = "Existing Title",
            coverUrl = "https://existing.cover",
            tags = listOf("fantasy", "litrpg"),
            description = "Detail-page description that follows-page never had.",
        )

        r.refreshRemoteFollows()

        val merged = fictionDao.rows["keep"]!!
        assertEquals("Existing Title", merged.title)
        assertEquals("https://existing.cover", merged.coverUrl)
        assertEquals(listOf("fantasy", "litrpg"), merged.tags)
        assertEquals(
            "Detail-page description that follows-page never had.",
            merged.description,
        )
        assertEquals(true, merged.followedRemotely)
    }

    // -- addToLibrary / removeFromLibrary ----------------------------------------

    @Test fun `addToLibrary refreshes when row missing then sets inLibrary`() = runTest {
        val src = FakeSource("rr").apply {
            detailResult = FictionResult.Success(detail("new", chapterCount = 1))
        }
        val (r, fictionDao, _) = repo(sources = mapOf("rr" to src))

        r.addToLibrary("new")

        // refreshDetail wrote the row first (via upsertDetail), then setInLibrary.
        val log = fictionDao.callLog
        val refreshIdx = log.indexOfFirst { it.startsWith("upsert(new)") }
        val setLibIdx = log.indexOfFirst { it.startsWith("setInLibrary(new") }
        assertTrue("upsert must precede setInLibrary", refreshIdx in 0 until setLibIdx)
        assertEquals(true, fictionDao.rows["new"]?.inLibrary)
    }

    @Test fun `addToLibrary skips refresh when row already exists`() = runTest {
        val src = FakeSource("rr") // detailResult left as default error
        val (r, fictionDao, _) = repo(sources = mapOf("rr" to src))
        fictionDao.rows["existing"] = summary("existing").toEntity(0L)

        r.addToLibrary("existing")

        assertTrue(
            "fictionDetail must not be called when row already exists",
            src.callLog.none { it.startsWith("fictionDetail") },
        )
        assertEquals(true, fictionDao.rows["existing"]?.inLibrary)
    }

    @Test fun `addToLibrary ignores refresh failure (best-effort)`() = runTest {
        val src = FakeSource("rr").apply {
            detailResult = FictionResult.NetworkError("flaky")
        }
        val (r, fictionDao, _) = repo(sources = mapOf("rr" to src))

        // refreshDetail will fail. Ensure addToLibrary still attempts to flip
        // setInLibrary — but setInLibrary on a non-existent row in the fake
        // is a silent no-op. In production it's also harmless (UPDATE WHERE
        // id = :id touches zero rows).
        r.addToLibrary("missing")

        assertTrue(fictionDao.callLog.any { it.startsWith("setInLibrary(missing") })
    }

    @Test fun `addToLibrary applies downloadMode after setInLibrary`() = runTest {
        val src = FakeSource("rr").apply {
            detailResult = FictionResult.Success(detail("eager", chapterCount = 1))
        }
        val (r, fictionDao, _) = repo(sources = mapOf("rr" to src))

        r.addToLibrary("eager", DownloadMode.EAGER)

        val log = fictionDao.callLog
        val setLibIdx = log.indexOfFirst { it.startsWith("setInLibrary(eager") }
        val setModeIdx = log.indexOfFirst { it.startsWith("setDownloadMode(eager") }
        assertTrue("setInLibrary must precede setDownloadMode", setLibIdx in 0 until setModeIdx)
        assertEquals(DownloadMode.EAGER, fictionDao.rows["eager"]?.downloadMode)
    }

    @Test fun `removeFromLibrary clears flag and prunes if transient`() = runTest {
        val (r, fictionDao, _) = repo()
        fictionDao.rows["trans"] = summary("trans").toEntity(0L).copy(inLibrary = true)

        r.removeFromLibrary("trans")

        assertNull(
            "transient row (no library + no follow) is pruned",
            fictionDao.rows["trans"],
        )
    }

    @Test fun `removeFromLibrary keeps row when followedRemotely is set`() = runTest {
        val (r, fictionDao, _) = repo()
        fictionDao.rows["keep"] = summary("keep").toEntity(0L).copy(
            inLibrary = true,
            followedRemotely = true,
        )

        r.removeFromLibrary("keep")

        val row = fictionDao.rows["keep"]
        assertNotNull("row with active follow must NOT be pruned", row)
        assertEquals(false, row!!.inLibrary)
        assertEquals(true, row.followedRemotely)
    }

    // -- setFollowedRemote -------------------------------------------------------

    @Test fun `setFollowedRemote success flips local row only after source succeeds`() = runTest {
        val src = FakeSource("rr").apply {
            setFollowedResult = FictionResult.Success(Unit)
        }
        val (r, fictionDao, _) = repo(sources = mapOf("rr" to src))
        fictionDao.rows["x"] = summary("x").toEntity(0L)

        val result = r.setFollowedRemote("x", true)
        assertTrue(result is FictionResult.Success)
        assertEquals(true, fictionDao.rows["x"]?.followedRemotely)
    }

    @Test fun `setFollowedRemote source failure preserves local state`() = runTest {
        val src = FakeSource("rr").apply {
            setFollowedResult = FictionResult.AuthRequired()
        }
        val (r, fictionDao, _) = repo(sources = mapOf("rr" to src))
        fictionDao.rows["x"] = summary("x").toEntity(0L).copy(followedRemotely = false)

        val result = r.setFollowedRemote("x", true)
        assertTrue(result is FictionResult.AuthRequired)
        assertEquals(
            "local followedRemotely must NOT change on source failure",
            false,
            fictionDao.rows["x"]?.followedRemotely,
        )
    }

    // -- addByUrl ----------------------------------------------------------------

    @Test fun `addByUrl unknown url returns UnrecognizedUrl`() = runTest {
        val (r, fictionDao, _) = repo()
        val result = r.addByUrl("totally bogus input")
        assertTrue(result is AddByUrlResult.UnrecognizedUrl)
        assertEquals(0, fictionDao.rows.size)
    }

    @Test fun `addByUrl recognised but unbound source returns UnsupportedSource`() = runTest {
        // Map only has "rr"; UrlRouter recognises GitHub but no source bound.
        val (r, fictionDao, _) = repo(sources = mapOf("rr" to FakeSource("rr")))
        val result = r.addByUrl("https://github.com/example/repo")
        assertTrue(result is AddByUrlResult.UnsupportedSource)
        assertEquals("github", (result as AddByUrlResult.UnsupportedSource).sourceId)
        assertEquals("no stub row for unbound source", 0, fictionDao.rows.size)
    }

    @Test fun `addByUrl pre-writes a stub row before fetching detail`() = runTest {
        // UrlRouter hard-codes "royalroad" as the sourceId for RR URLs, so the
        // sources map MUST be keyed by that exact string for the bind to land.
        val rr = FakeSource("royalroad").apply {
            detailResult = FictionResult.Success(detail("123", sourceId = "royalroad"))
        }
        val (r, fictionDao, _) = repo(sources = mapOf("royalroad" to rr))

        val result = r.addByUrl("https://www.royalroad.com/fiction/123")

        assertTrue(result is AddByUrlResult.Success)
        assertEquals("123", (result as AddByUrlResult.Success).fictionId)
        val log = fictionDao.callLog
        val firstUpsert = log.indexOfFirst { it.startsWith("upsert(123)") }
        assertTrue("a stub upsert must occur", firstUpsert >= 0)
        // After detail success, the row carries the richer detail title.
        assertEquals("title-123", fictionDao.rows["123"]?.title)
    }

    @Test fun `addByUrl source failure returns SourceFailure but keeps stub row`() = runTest {
        val rr = FakeSource("royalroad").apply {
            detailResult = FictionResult.NetworkError("flaky")
        }
        val (r, fictionDao, _) = repo(sources = mapOf("royalroad" to rr))

        val result = r.addByUrl("https://www.royalroad.com/fiction/777")
        assertTrue(result is AddByUrlResult.SourceFailure)
        assertTrue(
            (result as AddByUrlResult.SourceFailure).failure is FictionResult.NetworkError,
        )
        // The stub row remains so a subsequent retry can find it.
        assertNotNull(fictionDao.rows["777"])
    }

    @Test fun `addByUrl skips stub upsert when row already exists`() = runTest {
        val rr = FakeSource("royalroad").apply {
            detailResult = FictionResult.Success(detail("123", sourceId = "royalroad"))
        }
        val (r, fictionDao, _) = repo(sources = mapOf("royalroad" to rr))
        fictionDao.rows["123"] = Fiction(
            id = "123", sourceId = "royalroad", title = "Existing", author = "",
            firstSeenAt = 0L, metadataFetchedAt = 0L,
        )
        fictionDao.callLog.clear()

        r.addByUrl("https://www.royalroad.com/fiction/123")

        // A stub-shape upsert (id="123" with empty title) must NOT happen;
        // the only upsert is the post-detail one with richer fields.
        val upserts = fictionDao.callLog.filter { it.startsWith("upsert(123)") }
        assertEquals("expected exactly one upsert: the detail upsert", 1, upserts.size)
        assertEquals("title-123", fictionDao.rows["123"]?.title)
    }

    // -- observeLibrary / observeFollowsRemote / observeFiction ------------------

    @Test fun `observeLibrary maps Fiction rows to FictionSummary`() = runTest {
        val (r, fictionDao, _) = repo()
        fictionDao.rows["a"] = summary("a", title = "A").toEntity(0L)
            .copy(inLibrary = true, addedToLibraryAt = 100L)
        fictionDao.rows["b"] = summary("b", title = "B").toEntity(0L)
            .copy(inLibrary = true, addedToLibraryAt = 200L)
        // Force the derived flow to refresh.
        fictionDao.upsert(fictionDao.rows["a"]!!)
        fictionDao.upsert(fictionDao.rows["b"]!!)

        val items = r.observeLibrary().first()
        assertEquals(setOf("a", "b"), items.map { it.id }.toSet())
        assertEquals("most-recent-first ordering", "b", items.first().id)
    }

    @Test fun `observeFiction returns null when row absent and unwraps when present`() = runTest {
        val (r, fictionDao, _) = repo()

        // Absent row → null.
        assertNull(r.observeFiction("missing").first())

        // Add row + chapter; the detail comes back.
        fictionDao.upsert(summary("here", title = "Here").toEntity(0L))
        val detail = r.observeFiction("here").first()
        assertNotNull(detail)
        assertEquals("here", detail!!.summary.id)
        assertEquals("Here", detail.summary.title)
    }

    @Test fun `setDownloadMode and setPinnedVoice delegate straight through`() = runTest {
        val (r, fictionDao, _) = repo()
        fictionDao.rows["x"] = summary("x").toEntity(0L)

        r.setDownloadMode("x", DownloadMode.LAZY)
        r.setPinnedVoice("x", "voice-1", "en-US")

        val row = fictionDao.rows["x"]!!
        assertEquals(DownloadMode.LAZY, row.downloadMode)
        assertEquals("voice-1", row.pinnedVoiceId)
        assertEquals("en-US", row.pinnedVoiceLocale)
    }
}
