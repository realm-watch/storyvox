package `in`.jphe.storyvox.source.ao3.parser

import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.FictionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR2 of #426 — parser contract tests for [Ao3WorksIndexParser].
 *
 * AO3's `/users/<u>/subscriptions` and `/users/<u>/readings?show=marked`
 * pages emit the same `<ol class="work index group">` shape, so the
 * parser is shared across both. The fixtures here are reduced
 * snapshots of real AO3 markup (anonymized work ids / titles) — the
 * full pages carry navigation chrome, header, footer, JS that we
 * don't need to faithfully reproduce for the parser to round-trip
 * the `<li class="work blurb">` cards.
 */
class Ao3WorksIndexParserTest {

    @Test fun `parses a single-card subscriptions page`() {
        val page = parser_fixture_singleCard()
        val result = Ao3WorksIndexParser.parse(page, page = 1)

        assertEquals(1, result.items.size)
        val item = result.items.single()

        assertEquals("ao3:12345", item.id)
        assertEquals(SourceIds.AO3, item.sourceId)
        assertEquals("A Test Work", item.title)
        assertEquals("alice", item.author)
        assertNotNull("summary should round-trip", item.description)
        assertTrue(item.description!!.contains("placeholder summary"))
        assertEquals(FictionStatus.ONGOING, item.status)
        // hasNext = false: no pagination element.
        assertFalse(result.hasNext)
        assertEquals(1, result.page)
    }

    @Test fun `parses a multi-card page with mixed completed and ongoing works`() {
        val page = parser_fixture_multipleCards()
        val result = Ao3WorksIndexParser.parse(page, page = 2)

        // Two cards in the fixture — one ongoing, one completed.
        assertEquals(2, result.items.size)
        assertEquals(2, result.page)

        val ongoing = result.items[0]
        val completed = result.items[1]

        assertEquals("ao3:11111", ongoing.id)
        assertEquals("First Work", ongoing.title)
        assertEquals(FictionStatus.ONGOING, ongoing.status)

        assertEquals("ao3:22222", completed.id)
        assertEquals("Second Work", completed.title)
        assertEquals(FictionStatus.COMPLETED, completed.status)
    }

    @Test fun `parses multi-author co-authored work`() {
        // AO3 emits one `<a rel="author">` per author; the parser
        // joins them with ", " to match the Atom-feed shape used by
        // [Ao3FeedEntry.authorDisplay].
        val page = parser_fixture_coAuthored()
        val result = Ao3WorksIndexParser.parse(page)

        assertEquals(1, result.items.size)
        val item = result.items.single()
        assertEquals("alice, bob", item.author)
    }

    @Test fun `extracts freeform and fandom tags but skips warnings`() {
        val page = parser_fixture_taggedWork()
        val result = Ao3WorksIndexParser.parse(page)
        val item = result.items.single()

        // Two freeforms ("Fluff", "Hurt/Comfort") + one fandom
        // ("Marvel"). The warning ("Choose Not To Use") is not in
        // the tag list — those are AO3 metadata that the storyvox
        // browse-card surface doesn't render as chips.
        assertTrue("expected freeform tag", "Fluff" in item.tags)
        assertTrue("expected freeform tag", "Hurt/Comfort" in item.tags)
        assertTrue("expected fandom tag", "Marvel" in item.tags)
        assertFalse(
            "warnings must not leak into tags list",
            "Choose Not To Use Archive Warnings" in item.tags,
        )
    }

    @Test fun `detects pagination next link`() {
        val page = parser_fixture_paginated()
        val result = Ao3WorksIndexParser.parse(page, page = 1)
        assertTrue("pagination block with Next link must set hasNext=true", result.hasNext)
    }

    @Test fun `returns empty list on AO3 empty-state notice`() {
        // AO3 surfaces an empty subscriptions list as a notice div
        // with no `<li class="work blurb">` children. The parser
        // returns an empty ListPage rather than throwing.
        val page = parser_fixture_emptyState()
        val result = Ao3WorksIndexParser.parse(page)
        assertEquals(0, result.items.size)
        assertFalse(result.hasNext)
    }

    @Test fun `silently drops malformed cards instead of throwing`() {
        // A card with no work id and no title anchor is unrecoverable
        // — the parser should skip it and surface the remaining
        // well-formed cards.
        val page = parser_fixture_oneGoodOneBroken()
        val result = Ao3WorksIndexParser.parse(page)
        assertEquals(1, result.items.size)
        assertEquals("ao3:33333", result.items.single().id)
    }

    @Test fun `falls back to title-anchor href when li id is missing`() {
        // A subscriptions-page card without the `id="work_<id>"`
        // attribute (rare but valid AO3 markup) — the parser uses
        // the title anchor's `/works/<id>` href as the fallback.
        val page = parser_fixture_noLiId()
        val result = Ao3WorksIndexParser.parse(page)
        assertEquals(1, result.items.size)
        assertEquals("ao3:44444", result.items.single().id)
    }

    @Test fun `parses chapter count from stats block`() {
        val page = parser_fixture_chapteredWork()
        val result = Ao3WorksIndexParser.parse(page)
        val item = result.items.single()
        // "5/?" in the fixture — the numerator is what we want.
        assertEquals(5, item.chapterCount)
    }

    @Test fun `returns null id for a card with no recognizable work reference`() {
        // Card has neither an `id="work_<id>"` nor a `/works/<id>`
        // anchor — there's no work to surface. Parser drops it, the
        // list comes back empty.
        val page = parser_fixture_noWorkRef()
        val result = Ao3WorksIndexParser.parse(page)
        assertEquals(0, result.items.size)
    }

    // ─── fixtures ──────────────────────────────────────────────────────

    private fun parser_fixture_singleCard(): String = """
        <html><body>
        <ol class="work index group">
          <li class="work blurb group" id="work_12345" role="article">
            <h4 class="heading">
              <a href="/works/12345">A Test Work</a>
              by <a rel="author" href="/users/alice">alice</a>
            </h4>
            <blockquote class="userstuff summary">
              <p>This is a placeholder summary.</p>
            </blockquote>
            <ul class="tags commas">
              <li class="freeforms"><a class="tag">Fluff</a></li>
            </ul>
          </li>
        </ol>
        </body></html>
    """.trimIndent()

    private fun parser_fixture_multipleCards(): String = """
        <html><body>
        <ol class="work index group">
          <li class="work blurb group" id="work_11111" role="article">
            <h4 class="heading">
              <a href="/works/11111">First Work</a>
              by <a rel="author" href="/users/alice">alice</a>
            </h4>
            <blockquote class="userstuff summary">First summary</blockquote>
          </li>
          <li class="work blurb group" id="work_22222" role="article">
            <h4 class="heading">
              <a href="/works/22222">Second Work</a>
              by <a rel="author" href="/users/bob">bob</a>
            </h4>
            <blockquote class="userstuff summary">Second summary</blockquote>
            <dl class="stats"><dd class="status">Complete</dd></dl>
          </li>
        </ol>
        </body></html>
    """.trimIndent()

    private fun parser_fixture_coAuthored(): String = """
        <html><body>
        <ol class="work index group">
          <li class="work blurb group" id="work_55555">
            <h4 class="heading">
              <a href="/works/55555">Co-Authored Work</a>
              by
              <a rel="author" href="/users/alice">alice</a>,
              <a rel="author" href="/users/bob">bob</a>
            </h4>
            <blockquote class="userstuff summary">A shared draft.</blockquote>
          </li>
        </ol>
        </body></html>
    """.trimIndent()

    private fun parser_fixture_taggedWork(): String = """
        <html><body>
        <ol class="work index group">
          <li class="work blurb group" id="work_66666">
            <h4 class="heading">
              <a href="/works/66666">Tagged Work</a>
              by <a rel="author" href="/users/alice">alice</a>
            </h4>
            <ul class="tags commas">
              <li class="warnings"><a class="tag">Choose Not To Use Archive Warnings</a></li>
              <li class="fandoms"><a class="tag">Marvel</a></li>
              <li class="freeforms"><a class="tag">Fluff</a></li>
              <li class="freeforms"><a class="tag">Hurt/Comfort</a></li>
            </ul>
          </li>
        </ol>
        </body></html>
    """.trimIndent()

    private fun parser_fixture_paginated(): String = """
        <html><body>
        <ol class="work index group">
          <li class="work blurb group" id="work_77777">
            <h4 class="heading"><a href="/works/77777">Work</a></h4>
          </li>
        </ol>
        <ol class="pagination actions">
          <li><a href="/users/alice/subscriptions?page=1">1</a></li>
          <li><a href="/users/alice/subscriptions?page=2" rel="next">Next →</a></li>
        </ol>
        </body></html>
    """.trimIndent()

    private fun parser_fixture_emptyState(): String = """
        <html><body>
        <div class="notice">You have no subscribed works.</div>
        </body></html>
    """.trimIndent()

    private fun parser_fixture_oneGoodOneBroken(): String = """
        <html><body>
        <ol class="work index group">
          <li class="work blurb group">
            <!-- no id, no title anchor -->
          </li>
          <li class="work blurb group" id="work_33333">
            <h4 class="heading"><a href="/works/33333">Good Work</a></h4>
          </li>
        </ol>
        </body></html>
    """.trimIndent()

    private fun parser_fixture_noLiId(): String = """
        <html><body>
        <ol class="work index group">
          <li class="work blurb group">
            <h4 class="heading"><a href="/works/44444">No Li Id</a></h4>
          </li>
        </ol>
        </body></html>
    """.trimIndent()

    private fun parser_fixture_chapteredWork(): String = """
        <html><body>
        <ol class="work index group">
          <li class="work blurb group" id="work_88888">
            <h4 class="heading"><a href="/works/88888">Long Work</a></h4>
            <dl class="stats">
              <dd class="chapters">5/?</dd>
            </dl>
          </li>
        </ol>
        </body></html>
    """.trimIndent()

    private fun parser_fixture_noWorkRef(): String = """
        <html><body>
        <ol class="work index group">
          <li class="work blurb group">
            <h4 class="heading"><a href="/tags/Marvel">A Tag Link Not A Work</a></h4>
          </li>
        </ol>
        </body></html>
    """.trimIndent()
}
