package `in`.jphe.storyvox.source.hackernews

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.hackernews.net.HackerNewsApi
import `in`.jphe.storyvox.source.hackernews.net.HnItem
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #379 — verifies the link-story body composition. The chapter
 * body for a link-type submission ("here's a URL") is the title +
 * link + the top 20 comments walked depth-first. This test feeds a
 * fixture tree of HnItems into a thin api wrapper that intercepts
 * the network calls, then asserts on the rendered plain body.
 *
 * Test fixture shape: one root story with 2 top-level kids; one of
 * those kids has 2 nested replies. The walker should render all 5
 * comments because the totals are well under the 50-comment cap and
 * the depth 2 is under the depth-4 cap.
 */
class HackerNewsCommentRenderingTest {

    /** A swappable HackerNewsApi-shaped seam — we want to intercept
     *  the `item()` calls so the renderer doesn't go to the network.
     *  Subclassing the concrete class with an [OkHttpClient] argument
     *  lets us reuse the public surface; only `item()` is overridden. */
    private class FakeApi(
        private val items: Map<Long, HnItem>,
    ) : HackerNewsApi(OkHttpClient()) {
        override suspend fun item(id: Long): FictionResult<HnItem?> {
            val it = items[id]
            return FictionResult.Success(it)
        }
    }

    @Test
    fun `link story body includes title link and top comments`() = runTest {
        // Story 100 links to https://example.com/post; the article
        // has two top-level comments; the second has two replies.
        val items = mapOf(
            100L to HnItem(
                id = 100,
                type = "story",
                by = "alice",
                title = "Cool new database engine",
                url = "https://example.com/post",
                kids = listOf(101L, 102L),
            ),
            101L to HnItem(
                id = 101,
                type = "comment",
                by = "bob",
                text = "I tried this and it&#x27;s fast.",
                parent = 100L,
            ),
            102L to HnItem(
                id = 102,
                type = "comment",
                by = "carol",
                text = "Benchmarks look <i>cherry-picked</i> to me.",
                parent = 100L,
                kids = listOf(103L, 104L),
            ),
            103L to HnItem(
                id = 103,
                type = "comment",
                by = "dave",
                text = "Author here — happy to share full data.",
                parent = 102L,
            ),
            104L to HnItem(
                id = 104,
                type = "comment",
                by = "erin",
                text = "Agreed, &amp; the methodology is unclear.",
                parent = 102L,
            ),
        )
        val fakeApi = FakeApi(items)
        val source = HackerNewsSource(fakeApi)
        val result = source.chapter(
            fictionId = "hackernews:100",
            chapterId = "hackernews:100::c0",
        )
        check(result is FictionResult.Success) {
            "expected Success, got $result"
        }
        val body = result.value.plainBody

        // Title + link prelude is the first paragraph.
        assertTrue(
            "expected title-and-link prelude, got: $body",
            body.contains("Cool new database engine") &&
                body.contains("link to https://example.com/post"),
        )

        // All four comment authors should appear; entity decoding
        // should have stripped the &#x27; / &amp; / <i> markup.
        for (author in listOf("bob", "carol", "dave", "erin")) {
            assertTrue("author $author missing from body", body.contains(author))
        }
        assertTrue(body.contains("I'm looking") || body.contains("it's fast"))
        assertFalse("entities should be decoded", body.contains("&#x27;"))
        assertFalse("entities should be decoded", body.contains("&amp;"))
        assertFalse("tags should be stripped", body.contains("<i>"))

        // Depth prefix scheme: depth-1 comments get one em-dash;
        // depth-2 comments (replies) get two. Verifies the threaded
        // shape carries through to the rendered body.
        assertTrue(body.contains("— bob:"))
        assertTrue(body.contains("—— dave:"))
    }

    @Test
    fun `ask story with text body uses the text field directly`() = runTest {
        val items = mapOf(
            200L to HnItem(
                id = 200,
                type = "story",
                by = "frank",
                title = "Ask HN: What tools do you use?",
                text = "I&#x27;m curious about your <i>workflow</i>.",
            ),
        )
        val source = HackerNewsSource(FakeApi(items))
        val result = source.chapter(
            fictionId = "hackernews:200",
            chapterId = "hackernews:200::c0",
        )
        check(result is FictionResult.Success)
        val body = result.value.plainBody
        // Body should be the entity-decoded, tag-stripped text — NOT
        // the title-plus-link prelude.
        assertTrue(body.contains("I'm curious about your workflow"))
        assertFalse("Ask body shouldn't synthesize a link prelude", body.contains("link to"))
    }
}
