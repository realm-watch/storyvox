package `in`.jphe.storyvox.source.ao3

import `in`.jphe.storyvox.source.ao3.net.Ao3Api
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #408 — pin the AO3 tag-feed URL shape and the curated
 * [Ao3Source.FANDOM_TAGS] payload.
 *
 * The bug surfaced when AO3 dropped the implicit slug→numeric-id
 * redirect that backed `/tags/<slug>/feed.atom`. The old URL builder
 * URL-encoded the slug (with the `*s*`/`*a*` escapes the Archive uses
 * for special characters); the fix keys the URL on AO3's internal
 * numeric tag id instead, which is a primary key and doesn't depend
 * on any redirect being in place. These tests pin both halves —
 *
 *  - the URL builder emits exactly `/tags/<numeric>/feed.atom`
 *    (no slug, no encoding step that could regress to the old shape)
 *  - [Ao3Source.FANDOM_TAGS] carries real (non-blank) display names
 *    paired with positive numeric ids, with no duplicates
 *
 * A failure here is the right signal that someone tried to bring the
 * slug form back without also resolving every numeric id.
 */
class Ao3TagUrlTest {

    @Test
    fun `tagFeedPath emits numeric id path on page 1`() {
        val path = Ao3Api.tagFeedPath(tagId = 414093L, page = 1)
        assertEquals("/tags/414093/feed.atom", path)
        // No slug, no percent-encoding, no AO3 *s* / *a* escape sequences.
        assertFalse("path must not contain a slug separator", path.contains("Marvel"))
        assertFalse("path must not contain percent-encoding", path.contains('%'))
        assertFalse("path must not carry AO3's slash escape", path.contains("*s*"))
        assertFalse("path must not carry AO3's amp escape", path.contains("*a*"))
    }

    @Test
    fun `tagFeedPath appends page query for paginated requests`() {
        assertEquals("/tags/27251507/feed.atom?page=2", Ao3Api.tagFeedPath(27251507L, 2))
        assertEquals("/tags/27251507/feed.atom?page=10", Ao3Api.tagFeedPath(27251507L, 10))
    }

    @Test
    fun `tagFeedPath treats page 0 and negative as page 1 (no query)`() {
        // Defensive — callers shouldn't pass <= 0, but if they do we
        // return the canonical first-page URL rather than emit a
        // malformed `?page=0` that AO3 might silently 200-with-empty.
        assertEquals("/tags/414093/feed.atom", Ao3Api.tagFeedPath(414093L, 0))
        assertEquals("/tags/414093/feed.atom", Ao3Api.tagFeedPath(414093L, -3))
    }

    @Test
    fun `FANDOM_TAGS entries are well-formed`() {
        val tags = Ao3Source.FANDOM_TAGS
        assertTrue("curated list must not be empty", tags.isNotEmpty())
        for ((name, id) in tags) {
            assertTrue("display name must be non-blank, got '$name'", name.isNotBlank())
            assertTrue("tag id must be positive, got $id for '$name'", id > 0L)
        }
        val names = tags.map { it.first }
        assertEquals("display names must be unique", names.toSet().size, names.size)
        val ids = tags.map { it.second }
        assertEquals("tag ids must be unique", ids.toSet().size, ids.size)
    }

    @Test
    fun `DEFAULT_TAG_ID is a real entry in FANDOM_TAGS`() {
        // The default has to be a tag we actually surface, otherwise
        // the Browse → AO3 → Popular tab will key off something the
        // user can never re-select from the genre row.
        val defaultId = Ao3Source.DEFAULT_TAG_ID
        assertTrue(
            "DEFAULT_TAG_ID $defaultId must appear in FANDOM_TAGS",
            Ao3Source.FANDOM_TAGS.any { it.second == defaultId },
        )
    }

    // ─── #426 PR2 — authed surface paths ──────────────────────────────

    @Test
    fun `subscriptionsPath emits the canonical AO3 subscriptions URL`() {
        assertEquals("/users/alice/subscriptions", Ao3Api.subscriptionsPath("alice", 1))
        assertEquals("/users/alice/subscriptions?page=3", Ao3Api.subscriptionsPath("alice", 3))
    }

    @Test
    fun `subscriptionsPath treats page 0 and negative as page 1`() {
        // Defensive parity with [tagFeedPath]'s page-0 handling so a
        // bad caller doesn't get AO3's `?page=0` reject.
        assertEquals("/users/alice/subscriptions", Ao3Api.subscriptionsPath("alice", 0))
        assertEquals("/users/alice/subscriptions", Ao3Api.subscriptionsPath("alice", -1))
    }

    @Test
    fun `markedForLaterPath uses readings show=marked`() {
        // AO3's Marked-for-Later lives under the user's readings page
        // with `show=marked`. The page query stays on a `&page=N`
        // because `show=marked` is already in the URL.
        assertEquals(
            "/users/alice/readings?show=marked",
            Ao3Api.markedForLaterPath("alice", 1),
        )
        assertEquals(
            "/users/alice/readings?show=marked&page=5",
            Ao3Api.markedForLaterPath("alice", 5),
        )
    }

    @Test
    fun `looksLikeLogin signals AO3 expired-session HTML body`() {
        // Detect AO3's `<form id="new_user" class="new_user">` login
        // surface — a 200 + login HTML means the session lapsed and
        // the request needs to short-circuit to AuthRequired.
        assertTrue(
            Ao3Api.looksLikeLogin("""<form id="new_user" class="new_user">"""),
        )
        assertTrue(
            Ao3Api.looksLikeLogin("""<input name="user[login]" type="text" />"""),
        )
        // A real subscriptions page body — no login form markers.
        assertEquals(
            false,
            Ao3Api.looksLikeLogin("""<ol class="work index group"><li class="work blurb">...</li></ol>"""),
        )
    }
}
