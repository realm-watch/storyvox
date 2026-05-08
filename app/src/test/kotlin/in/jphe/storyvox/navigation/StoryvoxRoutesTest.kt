package `in`.jphe.storyvox.navigation

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for v0.4.25 (commit 5cfc3c5): GitHubSource fictionIds
 * contain `/` (e.g. "github:jphein/example-fiction") and chapterIds contain
 * even more (e.g. "github:jphein/example-fiction:src/chapter-01.md").
 *
 * StoryvoxNavHost declares `fiction/{fictionId}` as a single-segment route
 * template — without per-segment encoding, the router can't bind the arg to
 * a 3-segment path and crashes the whole nav graph on first GitHub fiction
 * tap. The fix in StoryvoxNavHost.kt:54-60 wraps every id in `Uri.encode()`
 * at the route-builder so the on-the-wire path always has the segment count
 * the template expects.
 *
 * These tests pin that behaviour: each id-bearing route helper produces a
 * path with the correct slash count, and round-tripping the encoded
 * segments through `Uri.decode` returns the original id verbatim. If the
 * encoding ever silently drops out (e.g. someone "simplifies" the helper
 * to a string template), the next GitHub click crashes — these tests
 * catch that before it ships.
 *
 * Robolectric runner: `Uri.encode` lives in android.jar and the framework
 * stub throws "Method not mocked" under plain JUnit. Robolectric provides
 * a real implementation backed by libcore. See app/build.gradle.kts for
 * the (single-purpose) Robolectric dep.
 */
// `application = Application::class` skips the Hilt-injected StoryvoxApp,
// which would otherwise pull in DataModule.provideEncryptedPrefs and
// crash on `AndroidKeyStore not available` under Robolectric. The test
// only needs `android.net.Uri`, not a real DI graph.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StoryvoxRoutesTest {

    @Test
    fun fictionDetail_githubId_producesTwoSegmentPath() {
        val id = "github:jphein/example-fiction"
        val route = StoryvoxRoutes.fictionDetail(id)
        // Exactly one slash → 2 segments: "fiction" and the encoded id.
        assertEquals(1, route.count { it == '/' })
        val parts = route.split('/')
        assertEquals(2, parts.size)
        assertEquals("fiction", parts[0])
        assertEquals(id, Uri.decode(parts[1]))
    }

    @Test
    fun reader_githubIds_produceThreeSegmentPathAndRoundTrip() {
        val fictionId = "github:jphein/example-fiction"
        val chapterId = "github:jphein/example-fiction:src/chapter-01.md"
        val route = StoryvoxRoutes.reader(fictionId, chapterId)
        // Exactly two slashes → 3 segments: "reader", encoded fictionId,
        // encoded chapterId. Without per-segment encoding the chapterId
        // alone contributes 4 slashes and the route blows the template up.
        assertEquals(2, route.count { it == '/' })
        val parts = route.split('/')
        assertEquals(3, parts.size)
        assertEquals("reader", parts[0])
        assertEquals(fictionId, Uri.decode(parts[1]))
        assertEquals(chapterId, Uri.decode(parts[2]))
    }

    @Test
    fun audiobook_githubIds_produceThreeSegmentPathAndRoundTrip() {
        val fictionId = "github:jphein/example-fiction"
        val chapterId = "github:jphein/example-fiction:src/chapter-01.md"
        val route = StoryvoxRoutes.audiobook(fictionId, chapterId)
        assertEquals(2, route.count { it == '/' })
        val parts = route.split('/')
        assertEquals(3, parts.size)
        assertEquals("audiobook", parts[0])
        assertEquals(fictionId, Uri.decode(parts[1]))
        assertEquals(chapterId, Uri.decode(parts[2]))
    }

    @Test
    fun fictionDetail_royalRoadId_roundTripsCleanly() {
        // Sanity: the encoding helper must not corrupt the simple
        // royalroad ids that worked pre-v0.4.25 — they have no `/` so
        // `Uri.encode` is essentially a no-op for the path-significant
        // characters, but `:` still gets percent-encoded to `%3A`.
        val id = "royalroad:12345"
        val route = StoryvoxRoutes.fictionDetail(id)
        assertEquals(1, route.count { it == '/' })
        val parts = route.split('/')
        assertEquals(2, parts.size)
        assertEquals("fiction", parts[0])
        assertEquals(id, Uri.decode(parts[1]))
    }
}
