package `in`.jphe.storyvox.source.royalroad.tagsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedTagsParserTest {

    @Test
    fun `parses selected tags from saved-filter search page`() {
        val html = """
            <html><body>
              <form>
                <input type="hidden" name="__RequestVerificationToken" value="abc123"/>
                <button class="btn default search-tag selected" data-tag="action" data-label="Action">
                  <span class="tag-label">Action</span>
                </button>
                <button class="btn default search-tag" data-tag="adventure" data-label="Adventure">
                  <span class="tag-label">Adventure</span>
                </button>
                <button class="btn default search-tag selected" data-tag="litrpg" data-label="LitRPG">
                  <span class="tag-label">LitRPG</span>
                </button>
              </form>
            </body></html>
        """.trimIndent()

        val result = SavedTagsParser.parse(html, "https://www.royalroad.com/fictions/search?globalFilters=true")
            as SavedTagsParser.Result.Ok

        assertEquals(setOf("action", "litrpg"), result.parsed.savedTags)
        assertEquals("abc123", result.parsed.csrfToken)
    }

    @Test
    fun `also picks up aria-pressed selected state`() {
        val html = """
            <html><body>
              <button class="btn default search-tag" data-tag="comedy" aria-pressed="true">
                <span class="tag-label">Comedy</span>
              </button>
              <button class="btn default search-tag selected" data-tag="drama">
                <span class="tag-label">Drama</span>
              </button>
            </body></html>
        """.trimIndent()

        val result = SavedTagsParser.parse(html, "https://www.royalroad.com/fictions/search?globalFilters=true")
            as SavedTagsParser.Result.Ok

        assertEquals(setOf("comedy", "drama"), result.parsed.savedTags)
    }

    @Test
    fun `unauthed redirect surfaces as NotAuthenticated`() {
        val result = SavedTagsParser.parse(
            html = "<html><body>Login required</body></html>",
            finalUrl = "https://www.royalroad.com/account/login?ReturnUrl=%2Ffictions%2Fsearch",
        )
        assertEquals(SavedTagsParser.Result.NotAuthenticated, result)
    }

    @Test
    fun `inline login form treated as unauthed`() {
        val html = """
            <html><body>
              <form class="form-login-details"><input name="email"/></form>
            </body></html>
        """.trimIndent()
        val result = SavedTagsParser.parse(html, "https://www.royalroad.com/fictions/search?globalFilters=true")
        assertEquals(SavedTagsParser.Result.NotAuthenticated, result)
    }

    @Test
    fun `no selected buttons produces empty set without crashing`() {
        val html = """
            <html><body>
              <input type="hidden" name="__RequestVerificationToken" value="token"/>
              <button class="btn default search-tag" data-tag="action">Action</button>
              <button class="btn default search-tag" data-tag="comedy">Comedy</button>
            </body></html>
        """.trimIndent()

        val result = SavedTagsParser.parse(html, "https://www.royalroad.com/fictions/search?globalFilters=true")
            as SavedTagsParser.Result.Ok
        assertTrue(result.parsed.savedTags.isEmpty())
        assertNotNull(result.parsed.csrfToken)
    }

    @Test
    fun `null csrf token when hidden input missing`() {
        val html = """
            <html><body>
              <button class="btn default search-tag selected" data-tag="action">Action</button>
            </body></html>
        """.trimIndent()
        val result = SavedTagsParser.parse(html, "https://www.royalroad.com/fictions/search?globalFilters=true")
            as SavedTagsParser.Result.Ok
        assertEquals(setOf("action"), result.parsed.savedTags)
        assertEquals(null, result.parsed.csrfToken)
    }
}
