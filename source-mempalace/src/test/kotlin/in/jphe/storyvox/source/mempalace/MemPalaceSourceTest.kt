package `in`.jphe.storyvox.source.mempalace

import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.source.mempalace.config.PalaceConfig
import `in`.jphe.storyvox.source.mempalace.config.PalaceConfigState
import `in`.jphe.storyvox.source.mempalace.model.PalaceDrawer
import `in`.jphe.storyvox.source.mempalace.model.PalaceDrawerMetadata
import `in`.jphe.storyvox.source.mempalace.model.PalaceDrawerSummary
import `in`.jphe.storyvox.source.mempalace.model.PalaceGraph
import `in`.jphe.storyvox.source.mempalace.model.PalaceList
import `in`.jphe.storyvox.source.mempalace.model.PalaceWingRooms
import `in`.jphe.storyvox.source.mempalace.net.PalaceDaemonApi
import `in`.jphe.storyvox.source.mempalace.net.PalaceDaemonResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MemPalaceSource contract tests. Cover the cross-source surface
 * (sourceId/displayName/popular/latestUpdates/byGenre/genres/
 * fictionDetail/chapter/latestRevisionToken) plus error mapping from
 * the daemon-side result envelope to FictionResult.
 *
 * Network is mocked via a stub [`PalaceDaemonApi`] subclass; the real
 * OkHttp path is tested separately by the live daemon (manual smoke).
 */
class MemPalaceSourceTest {

    @Test fun `sourceId is the stable mempalace key`() {
        assertEquals(SourceIds.MEMPALACE, source().id)
    }

    @Test fun `displayName surfaces as Memory Palace`() {
        assertEquals("Memory Palace", source().displayName)
    }

    @Test fun `popular maps top rooms by drawer count to FictionSummary`() {
        val src = source(
            graph = sample3WingGraph(),
        )
        val r = runBlocking { src.popular(page = 1) } as FictionResult.Success
        // Top 3 rooms across all wings by drawer count:
        //  - technical (54160, projects)
        //  - creatures (12000, bestiary)
        //  - architecture (10787, projects)
        assertEquals(
            listOf(
                "mempalace:projects/technical",
                "mempalace:bestiary/creatures",
                "mempalace:projects/architecture",
            ),
            r.value.items.take(3).map { it.id },
        )
        assertEquals(SourceIds.MEMPALACE, r.value.items.first().sourceId)
        assertEquals("Technical", r.value.items.first().title)
        assertEquals(54160, r.value.items.first().chapterCount)
        // Tag = wing, so users can filter by wing later.
        assertEquals(listOf("projects"), r.value.items.first().tags)
    }

    @Test fun `popular returns empty page beyond page 1`() {
        val src = source(graph = sample3WingGraph())
        val r = runBlocking { src.popular(page = 2) } as FictionResult.Success
        assertTrue(r.value.items.isEmpty())
    }

    @Test fun `popular surfaces NotReachable as NetworkError`() {
        val src = source(
            graphResult = PalaceDaemonResult.NotReachable(java.io.IOException("nope")),
        )
        val r = runBlocking { src.popular() }
        assertTrue("expected NetworkError, got $r", r is FictionResult.NetworkError)
    }

    @Test fun `genres returns sorted wing list`() {
        val src = source(graph = sample3WingGraph())
        val r = runBlocking { src.genres() } as FictionResult.Success
        assertEquals(listOf("bestiary", "claude_code_python", "projects"), r.value)
    }

    @Test fun `byGenre filters rooms to the given wing`() {
        val src = source(graph = sample3WingGraph())
        val r = runBlocking { src.byGenre("bestiary", page = 1) } as FictionResult.Success
        assertEquals(
            listOf("mempalace:bestiary/creatures", "mempalace:bestiary/lore"),
            r.value.items.map { it.id },
        )
    }

    @Test fun `byGenre with empty wing falls back to popular`() {
        val src = source(graph = sample3WingGraph())
        val r = runBlocking { src.byGenre("", page = 1) } as FictionResult.Success
        assertTrue(r.value.items.isNotEmpty())
    }

    @Test fun `latestUpdates dedupes drawers by wing slash room`() {
        val src = source(
            list = PalaceList(
                drawers = listOf(
                    drawer("p", "realmwatch", "drawer_a"),
                    drawer("p", "realmwatch", "drawer_b"),  // dup wing/room
                    drawer("p", "architecture", "drawer_c"),
                    drawer("b", "creatures", "drawer_d"),
                ),
                count = 4,
            ),
        )
        val r = runBlocking { src.latestUpdates() } as FictionResult.Success
        assertEquals(
            listOf(
                "mempalace:p/realmwatch",
                "mempalace:p/architecture",
                "mempalace:b/creatures",
            ),
            r.value.items.map { it.id },
        )
    }

    @Test fun `search returns empty in v1`() {
        val src = source()
        val r = runBlocking { src.search(SearchQuery(term = "anything")) } as FictionResult.Success
        assertTrue(r.value.items.isEmpty())
    }

    @Test fun `fictionDetail rejects malformed fiction id with NotFound`() {
        val src = source()
        val r = runBlocking { src.fictionDetail("github:foo/bar") }
        assertTrue("expected NotFound, got $r", r is FictionResult.NotFound)
    }

    @Test fun `fictionDetail builds chapter list from listed drawers`() {
        val src = source(
            list = PalaceList(
                drawers = listOf(
                    drawer("projects", "realmwatch", "drawer_a", preview = "RealmWatch overview..."),
                    drawer("projects", "realmwatch", "drawer_b", preview = "Topology section..."),
                ),
                count = 2,
            ),
        )
        val r = runBlocking {
            src.fictionDetail("mempalace:projects/realmwatch")
        } as FictionResult.Success
        assertEquals(2, r.value.chapters.size)
        assertEquals(
            "mempalace:projects/realmwatch:drawer_a",
            r.value.chapters[0].id,
        )
        assertEquals("Realmwatch", r.value.summary.title)
        assertEquals(2, r.value.summary.chapterCount)
        assertEquals(listOf("projects"), r.value.genres)
    }

    @Test fun `fictionDetail returns NotFound when no drawers listed`() {
        val src = source(list = PalaceList(drawers = emptyList(), count = 0))
        val r = runBlocking { src.fictionDetail("mempalace:projects/missing") }
        assertTrue("expected NotFound, got $r", r is FictionResult.NotFound)
    }

    @Test fun `chapter unwraps drawer body to ChapterContent`() {
        val drawer = PalaceDrawer(
            drawerId = "drawer_x",
            content = "Hello palace.\nLine two.",
            wing = "projects",
            room = "realmwatch",
            metadata = PalaceDrawerMetadata(
                filedAt = "2026-04-09T19:19:20.970872",
                sourceFile = "OVERVIEW.md",
                chunkIndex = 0,
            ),
        )
        val src = source(drawer = drawer)
        val r = runBlocking {
            src.chapter(
                "mempalace:projects/realmwatch",
                "mempalace:projects/realmwatch:drawer_x",
            )
        } as FictionResult.Success
        assertEquals("Hello palace.\nLine two.", r.value.plainBody)
        assertEquals("Overview", r.value.info.title)
        assertEquals("drawer_x", r.value.info.sourceChapterId)
        assertTrue(r.value.htmlBody.startsWith("<pre>"))
    }

    @Test fun `chapter rejects mismatched fiction id`() {
        val src = source()
        val r = runBlocking {
            src.chapter(
                fictionId = "mempalace:projects/realmwatch",
                chapterId = "mempalace:bestiary/creatures:drawer_x",
            )
        }
        assertTrue("expected NotFound, got $r", r is FictionResult.NotFound)
    }

    @Test fun `chapter chunk_index appends part suffix`() {
        val drawer = PalaceDrawer(
            drawerId = "drawer_y",
            content = "Part 2 body.",
            wing = "p",
            room = "r",
            metadata = PalaceDrawerMetadata(sourceFile = "BIG.txt", chunkIndex = 1),
        )
        val src = source(drawer = drawer)
        val r = runBlocking {
            src.chapter("mempalace:p/r", "mempalace:p/r:drawer_y")
        } as FictionResult.Success
        assertEquals("Big (part 2)", r.value.info.title)
    }

    @Test fun `latestRevisionToken returns the most recent drawer id`() {
        val src = source(
            list = PalaceList(
                drawers = listOf(drawer("p", "r", "drawer_newest")),
                count = 1,
            ),
        )
        val r = runBlocking {
            src.latestRevisionToken("mempalace:p/r")
        } as FictionResult.Success
        assertEquals("drawer_newest", r.value)
    }

    @Test fun `latestRevisionToken returns null when room is empty`() {
        val src = source(list = PalaceList(drawers = emptyList(), count = 0))
        val r = runBlocking {
            src.latestRevisionToken("mempalace:p/r")
        } as FictionResult.Success
        assertNull(r.value)
    }

    @Test fun `followsList returns AuthRequired since palace has no follows`() {
        val src = source()
        val r = runBlocking { src.followsList() }
        assertTrue("expected AuthRequired, got $r", r is FictionResult.AuthRequired)
    }

    @Test fun `setFollowed returns AuthRequired since palace has no follows`() {
        val src = source()
        val r = runBlocking { src.setFollowed("mempalace:p/r", true) }
        assertTrue("expected AuthRequired, got $r", r is FictionResult.AuthRequired)
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private fun sample3WingGraph(): PalaceGraph = PalaceGraph(
        wings = mapOf(
            "projects" to 70561,
            "bestiary" to 20000,
            "claude_code_python" to 2366,
        ),
        rooms = listOf(
            PalaceWingRooms(
                wing = "projects",
                rooms = mapOf(
                    "realmwatch" to 5614,
                    "architecture" to 10787,
                    "technical" to 54160,
                ),
            ),
            PalaceWingRooms(
                wing = "bestiary",
                rooms = mapOf("creatures" to 12000, "lore" to 8000),
            ),
            PalaceWingRooms(
                wing = "claude_code_python",
                rooms = mapOf("planning" to 2366),
            ),
        ),
    )

    private fun drawer(
        wing: String,
        room: String,
        drawerId: String,
        preview: String? = null,
    ) = PalaceDrawerSummary(
        drawerId = drawerId,
        wing = wing,
        room = room,
        contentPreview = preview,
    )

    private fun source(
        graph: PalaceGraph? = null,
        graphResult: PalaceDaemonResult<PalaceGraph>? = null,
        list: PalaceList? = null,
        listResult: PalaceDaemonResult<PalaceList>? = null,
        drawer: PalaceDrawer? = null,
        drawerResult: PalaceDaemonResult<PalaceDrawer>? = null,
    ): MemPalaceSource = MemPalaceSource(
        api = StubApi(
            graphResp = graphResult ?: graph?.let { PalaceDaemonResult.Success(it) }
                ?: PalaceDaemonResult.Success(PalaceGraph()),
            listResp = listResult ?: list?.let { PalaceDaemonResult.Success(it) }
                ?: PalaceDaemonResult.Success(PalaceList()),
            drawerResp = drawerResult ?: drawer?.let { PalaceDaemonResult.Success(it) }
                ?: PalaceDaemonResult.Success(PalaceDrawer(
                    drawerId = "stub",
                    content = "stub",
                    wing = "stub",
                    room = "stub",
                )),
        ),
    )

    /**
     * Hand-rolled stub. We can't use Mockito here without a heavy
     * dep; subclassing is enough — `PalaceDaemonApi` is `open` and
     * the methods we care about are `open suspend fun`.
     */
    private class StubApi(
        val graphResp: PalaceDaemonResult<PalaceGraph>,
        val listResp: PalaceDaemonResult<PalaceList>,
        val drawerResp: PalaceDaemonResult<PalaceDrawer>,
    ) : PalaceDaemonApi(
        OkHttpClient(),
        object : PalaceConfig {
            override val state: Flow<PalaceConfigState> =
                flowOf(PalaceConfigState(host = "stub", apiKey = ""))
            override suspend fun current(): PalaceConfigState =
                PalaceConfigState(host = "stub", apiKey = "")
        },
    ) {
        override suspend fun graph() = graphResp
        override suspend fun list(wing: String?, room: String?, limit: Int, offset: Int) =
            listResp
        override suspend fun getDrawer(drawerId: String) = drawerResp
    }
}
