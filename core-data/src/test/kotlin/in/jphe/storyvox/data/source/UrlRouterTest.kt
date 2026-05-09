package `in`.jphe.storyvox.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlRouterTest {

    // ── Royal Road ────────────────────────────────────────────────────────

    @Test fun `royalroad fiction url routes to royalroad with numeric fictionId`() {
        val m = UrlRouter.route("https://www.royalroad.com/fiction/12345")
        assertEquals(UrlRouter.Match("royalroad", "12345"), m)
    }

    @Test fun `royalroad fiction url with slug after id is accepted`() {
        val m = UrlRouter.route("https://www.royalroad.com/fiction/12345/my-fiction-slug")
        assertEquals(UrlRouter.Match("royalroad", "12345"), m)
    }

    @Test fun `royalroad chapter url is accepted and resolves to fiction id`() {
        val m = UrlRouter.route(
            "https://www.royalroad.com/fiction/12345/my-fiction-slug/chapter/678/some-chapter",
        )
        assertEquals(UrlRouter.Match("royalroad", "12345"), m)
    }

    @Test fun `royalroad without www subdomain is accepted`() {
        val m = UrlRouter.route("https://royalroad.com/fiction/99")
        assertEquals(UrlRouter.Match("royalroad", "99"), m)
    }

    @Test fun `royalroad with http scheme is accepted`() {
        val m = UrlRouter.route("http://www.royalroad.com/fiction/1")
        assertEquals(UrlRouter.Match("royalroad", "1"), m)
    }

    @Test fun `mixed-case host is accepted`() {
        val m = UrlRouter.route("https://www.RoyalRoad.com/fiction/42")
        assertEquals(UrlRouter.Match("royalroad", "42"), m)
    }

    @Test fun `royalroad without numeric id is rejected`() {
        assertNull(UrlRouter.route("https://www.royalroad.com/fiction/best"))
    }

    @Test fun `royalroad bare host is rejected`() {
        assertNull(UrlRouter.route("https://www.royalroad.com"))
    }

    // ── GitHub ────────────────────────────────────────────────────────────

    @Test fun `github https url routes to github with namespaced fictionId`() {
        val m = UrlRouter.route("https://github.com/jphein/example-fiction")
        assertEquals(UrlRouter.Match("github", "github:jphein/example-fiction"), m)
    }

    @Test fun `github tree branch url is accepted and resolves to repo`() {
        val m = UrlRouter.route("https://github.com/jphein/example-fiction/tree/main")
        assertEquals(UrlRouter.Match("github", "github:jphein/example-fiction"), m)
    }

    @Test fun `github dot-git suffix is stripped`() {
        val m = UrlRouter.route("https://github.com/jphein/example-fiction.git")
        assertEquals(UrlRouter.Match("github", "github:jphein/example-fiction"), m)
    }

    @Test fun `github short form with prefix routes to github`() {
        val m = UrlRouter.route("github:jphein/example-fiction")
        assertEquals(UrlRouter.Match("github", "github:jphein/example-fiction"), m)
    }

    @Test fun `github bare owner-slash-repo routes to github`() {
        val m = UrlRouter.route("jphein/example-fiction")
        assertEquals(UrlRouter.Match("github", "github:jphein/example-fiction"), m)
    }

    @Test fun `github case is normalised to lowercase`() {
        val m = UrlRouter.route("https://github.com/JPHein/Example-Fiction")
        assertEquals(UrlRouter.Match("github", "github:jphein/example-fiction"), m)
    }

    // ── Negative cases ────────────────────────────────────────────────────

    @Test fun `empty input returns null`() {
        assertNull(UrlRouter.route(""))
    }

    @Test fun `whitespace-only input returns null`() {
        assertNull(UrlRouter.route("   "))
    }

    @Test fun `random url returns null`() {
        assertNull(UrlRouter.route("https://example.com/some/path"))
    }

    @Test fun `unrelated short form returns null`() {
        assertNull(UrlRouter.route("just some text"))
    }

    @Test fun `bare path with too many slashes is not mistaken for github short`() {
        // Three slashes — not a valid `owner/repo` short form.
        assertNull(UrlRouter.route("a/b/c"))
    }

    @Test fun `surrounding whitespace is trimmed before matching`() {
        val m = UrlRouter.route("  https://www.royalroad.com/fiction/7  ")
        assertEquals(UrlRouter.Match("royalroad", "7"), m)
    }

    // ── Gists ─────────────────────────────────────────────────────────────

    @Test fun `gist url with user prefix routes to gist sub-prefix`() {
        val m = UrlRouter.route("https://gist.github.com/jphein/abc123def456")
        assertEquals(UrlRouter.Match("github", "github:gist:abc123def456"), m)
    }

    @Test fun `gist url without user prefix is accepted`() {
        // GitHub still serves the user-less form for legacy gist URLs.
        val m = UrlRouter.route("https://gist.github.com/abc123def456")
        assertEquals(UrlRouter.Match("github", "github:gist:abc123def456"), m)
    }

    @Test fun `gist url with revision suffix discards revision`() {
        val m = UrlRouter.route(
            "https://gist.github.com/jphein/abc123def456/0123456789abcdef",
        )
        assertEquals(UrlRouter.Match("github", "github:gist:abc123def456"), m)
    }

    @Test fun `gist mixed-case id is normalised to lowercase`() {
        val m = UrlRouter.route("https://gist.github.com/jphein/ABC123DEF456")
        assertEquals(UrlRouter.Match("github", "github:gist:abc123def456"), m)
    }

    @Test fun `gist short form with prefix routes to gist sub-prefix`() {
        val m = UrlRouter.route("gist:abc123def456")
        assertEquals(UrlRouter.Match("github", "github:gist:abc123def456"), m)
    }

    @Test fun `bare hex without gist prefix is not mistaken for a gist`() {
        // Bare hex is too easy to collide with arbitrary user input;
        // require the explicit `gist:` prefix on the short form.
        assertNull(UrlRouter.route("abc123def456"))
    }

    @Test fun `gist with non-hex id is rejected`() {
        // GitHub gist ids are hex; rejecting non-hex avoids picking up
        // arbitrary `gist.github.com/<anything>` typos.
        assertNull(UrlRouter.route("https://gist.github.com/jphein/not-a-gist"))
    }
}
