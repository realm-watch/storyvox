package `in`.jphe.storyvox.feature.browse

import `in`.jphe.storyvox.feature.api.GitHubPushedSince
import `in`.jphe.storyvox.feature.api.GitHubSearchFilter
import `in`.jphe.storyvox.feature.api.GitHubSort
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubFilterQueryTest {

    private val today = LocalDate.of(2026, 5, 8)
    private val empty = GitHubSearchFilter()

    // -- composeGitHubQuery ------------------------------------------------------

    @Test fun `empty filter and blank term yields empty string`() {
        assertEquals("", composeGitHubQuery("", empty, today))
    }

    @Test fun `term only is trimmed and returned verbatim`() {
        assertEquals("archmage", composeGitHubQuery("  archmage  ", empty, today))
    }

    @Test fun `minStars zero is omitted`() {
        assertEquals("", composeGitHubQuery("", empty.copy(minStars = 0), today))
    }

    @Test fun `minStars negative is omitted`() {
        // Defensive — UI never sets a negative value but the helper
        // shouldn't emit `stars:>=-5` if it ever does.
        assertEquals("", composeGitHubQuery("", empty.copy(minStars = -5), today))
    }

    @Test fun `minStars positive emits qualifier`() {
        assertEquals(
            "stars:>=10",
            composeGitHubQuery("", empty.copy(minStars = 10), today),
        )
    }

    @Test fun `language is lowercased`() {
        assertEquals(
            "language:en",
            composeGitHubQuery("", empty.copy(language = "EN"), today),
        )
    }

    @Test fun `blank language is omitted`() {
        assertEquals("", composeGitHubQuery("", empty.copy(language = "   "), today))
    }

    @Test fun `pushedSince Last30Days emits ISO cutoff date`() {
        assertEquals(
            "pushed:>=2026-04-08",
            composeGitHubQuery("", empty.copy(pushedSince = GitHubPushedSince.Last30Days), today),
        )
    }

    @Test fun `pushedSince Any omits qualifier`() {
        assertEquals(
            "",
            composeGitHubQuery("", empty.copy(pushedSince = GitHubPushedSince.Any), today),
        )
    }

    @Test fun `sort BestMatch omits qualifier`() {
        assertEquals(
            "",
            composeGitHubQuery("", empty.copy(sort = GitHubSort.BestMatch), today),
        )
    }

    @Test fun `sort Stars emits qualifier`() {
        assertEquals(
            "sort:stars",
            composeGitHubQuery("", empty.copy(sort = GitHubSort.Stars), today),
        )
    }

    @Test fun `sort Updated emits qualifier`() {
        assertEquals(
            "sort:updated",
            composeGitHubQuery("", empty.copy(sort = GitHubSort.Updated), today),
        )
    }

    @Test fun `combined term and all qualifiers space-separated in expected order`() {
        val filter = GitHubSearchFilter(
            minStars = 100,
            language = "en",
            pushedSince = GitHubPushedSince.Last90Days,
            sort = GitHubSort.Stars,
        )
        assertEquals(
            "dragon stars:>=100 language:en pushed:>=2026-02-07 sort:stars",
            composeGitHubQuery("dragon", filter, today),
        )
    }

    @Test fun `qualifiers are space-separated when term is blank`() {
        val filter = GitHubSearchFilter(
            minStars = 50,
            sort = GitHubSort.Updated,
        )
        assertEquals(
            "stars:>=50 sort:updated",
            composeGitHubQuery("", filter, today),
        )
    }

    @Test fun `Last7Days uses minus-7 cutoff`() {
        assertEquals(
            "pushed:>=2026-05-01",
            composeGitHubQuery("", empty.copy(pushedSince = GitHubPushedSince.Last7Days), today),
        )
    }

    @Test fun `LastYear uses minus-1-year cutoff`() {
        assertEquals(
            "pushed:>=2025-05-08",
            composeGitHubQuery("", empty.copy(pushedSince = GitHubPushedSince.LastYear), today),
        )
    }

    // -- isActive ----------------------------------------------------------------

    @Test fun `default filter is not active`() {
        assertFalse(empty.isActive())
    }

    @Test fun `minStars zero is not active`() {
        assertFalse(empty.copy(minStars = 0).isActive())
    }

    @Test fun `minStars positive is active`() {
        assertTrue(empty.copy(minStars = 1).isActive())
    }

    @Test fun `non-blank language is active`() {
        assertTrue(empty.copy(language = "en").isActive())
    }

    @Test fun `blank language is not active`() {
        assertFalse(empty.copy(language = "  ").isActive())
    }

    @Test fun `non-default pushedSince is active`() {
        assertTrue(empty.copy(pushedSince = GitHubPushedSince.Last30Days).isActive())
    }

    @Test fun `non-default sort is active`() {
        assertTrue(empty.copy(sort = GitHubSort.Stars).isActive())
    }

    // -- activeCount -------------------------------------------------------------

    @Test fun `default activeCount is 0`() {
        assertEquals(0, empty.activeCount())
    }

    @Test fun `each set knob increments activeCount by 1`() {
        val filter = GitHubSearchFilter(
            minStars = 5,
            language = "en",
            pushedSince = GitHubPushedSince.Last7Days,
            sort = GitHubSort.Updated,
        )
        assertEquals(4, filter.activeCount())
    }

    @Test fun `partial filter counts only set knobs`() {
        assertEquals(1, empty.copy(minStars = 5).activeCount())
        assertEquals(2, empty.copy(minStars = 5, language = "en").activeCount())
    }
}
