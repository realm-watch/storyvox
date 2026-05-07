package `in`.jphe.storyvox.source.github

import `in`.jphe.storyvox.source.github.net.GitHubApi
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * Sanity tests for the stub [GitHubSource]. The stub is intentional —
 * the source's id/displayName are stable contract surface (the
 * UrlRouter routes to `sourceId="github"` and the Library sheet
 * surfaces `displayName` in its "coming soon" message), but every
 * suspend call should fail loudly so an accidental Hilt binding
 * surfaces during dev rather than silently returning empty pages.
 *
 * The stub never calls into [GitHubApi], so the constructor argument
 * here is a no-op: a default OkHttpClient that never issues a request.
 */
class GitHubSourceTest {

    private fun stub(): GitHubSource = GitHubSource(api = GitHubApi(OkHttpClient()))

    @Test fun `sourceId is the stable github key`() {
        assertEquals("github", stub().id)
    }

    @Test fun `displayName surfaces in UI strings and is stable`() {
        assertEquals("GitHub", stub().displayName)
    }

    @Test fun `every suspend method throws NotImplementedError`() {
        val src = stub()
        assertThrows(NotImplementedError::class.java) { runBlocking { src.popular() } }
        assertThrows(NotImplementedError::class.java) { runBlocking { src.latestUpdates() } }
        assertThrows(NotImplementedError::class.java) { runBlocking { src.byGenre("fantasy") } }
        assertThrows(NotImplementedError::class.java) { runBlocking { src.genres() } }
        assertThrows(NotImplementedError::class.java) {
            runBlocking { src.fictionDetail("github:o/r") }
        }
        assertThrows(NotImplementedError::class.java) {
            runBlocking { src.chapter("github:o/r", "github:o/r:src/01.md") }
        }
        assertThrows(NotImplementedError::class.java) { runBlocking { src.followsList() } }
        assertThrows(NotImplementedError::class.java) {
            runBlocking { src.setFollowed("github:o/r", true) }
        }
    }
}
