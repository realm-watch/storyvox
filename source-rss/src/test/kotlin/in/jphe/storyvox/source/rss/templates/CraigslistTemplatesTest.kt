package `in`.jphe.storyvox.source.rss.templates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #464 — pins the curated Craigslist region / category catalogue
 * and the URL composition function. The data is small enough to verify
 * parametrically across every entry rather than per-row.
 */
class CraigslistTemplatesTest {

    @Test
    fun `regions list covers the issue body's named metros`() {
        // The issue body explicitly names these metros + JP's home
        // region. If a future trim drops one we want to know — the
        // user-stated MVP includes all of them.
        val mustHave = listOf(
            "sfbay",
            "newyork",
            "losangeles",
            "chicago",
            "seattle",
            "sacramento",
            "nevadacity",
        )
        val slugs = CraigslistTemplates.REGIONS.map { it.slug }
        for (slug in mustHave) {
            assertTrue(
                "REGIONS must contain '$slug' (named in issue #464)",
                slug in slugs,
            )
        }
    }

    @Test
    fun `region list has at least 40 entries to cover the major US metros`() {
        // We curated ~50; if a future PR accidentally trims it down we
        // want a guard. 40 is a "is the major-metro list still major"
        // floor.
        assertTrue(
            "REGIONS should contain ≥40 entries (had ${CraigslistTemplates.REGIONS.size})",
            CraigslistTemplates.REGIONS.size >= 40,
        )
    }

    @Test
    fun `every region slug is a valid DNS label`() {
        // Subdomains can only contain lowercase letters, digits, and
        // hyphens; can't start or end with a hyphen; ≤63 chars. Hyphens
        // aren't expected for Craigslist subdomains but we allow them
        // for generality.
        val labelRe = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
        for (region in CraigslistTemplates.REGIONS) {
            assertTrue(
                "Region slug '${region.slug}' must be a valid DNS label",
                labelRe.matches(region.slug),
            )
        }
    }

    @Test
    fun `every region has a non-blank human label`() {
        for (region in CraigslistTemplates.REGIONS) {
            assertFalse(
                "Region '${region.slug}' has blank label",
                region.label.isBlank(),
            )
        }
    }

    @Test
    fun `region slugs are unique`() {
        val slugs = CraigslistTemplates.REGIONS.map { it.slug }
        assertEquals(
            "Region slugs must be unique (duplicates: ${slugs.groupingBy { it }.eachCount().filter { it.value > 1 }.keys})",
            slugs.size,
            slugs.toSet().size,
        )
    }

    @Test
    fun `categories list covers the issue body's named slugs`() {
        // Issue body v1 scope names: sss, cta, apa, zip, jjj, ggg, ccc.
        // We've curated sss, zip, cta, fua, ela, apa, jjj (dropped ggg
        // gigs and ccc community because they map awkwardly to listing-
        // narration UX — narrating "service offered" gigs reads worse
        // than narrating product listings; punted with the v1.5+ filter
        // surface).
        val mustHave = listOf("sss", "zip", "cta", "apa", "jjj")
        val slugs = CraigslistTemplates.CATEGORIES.map { it.slug }
        for (slug in mustHave) {
            assertTrue(
                "CATEGORIES must contain '$slug' (named in issue #464 v1 scope)",
                slug in slugs,
            )
        }
    }

    @Test
    fun `every category slug is non-empty alphanumeric`() {
        // Craigslist category slugs are 2-3 lowercase letters in practice.
        val slugRe = Regex("^[a-z]{2,8}$")
        for (cat in CraigslistTemplates.CATEGORIES) {
            assertTrue(
                "Category slug '${cat.slug}' shape unexpected",
                slugRe.matches(cat.slug),
            )
        }
    }

    @Test
    fun `category slugs are unique`() {
        val slugs = CraigslistTemplates.CATEGORIES.map { it.slug }
        assertEquals(slugs.size, slugs.toSet().size)
    }

    @Test
    fun `composeFeedUrl produces the expected canonical URL`() {
        // Pin the exact URL shape for two representative pairs — the
        // issue body's "free stuff in SF Bay" and the canonical "all
        // for sale in Sacramento" examples.
        val sfbay = CraigslistTemplates.REGIONS.first { it.slug == "sfbay" }
        val sacramento = CraigslistTemplates.REGIONS.first { it.slug == "sacramento" }
        val freeStuff = CraigslistTemplates.CATEGORIES.first { it.slug == "zip" }
        val allForSale = CraigslistTemplates.CATEGORIES.first { it.slug == "sss" }

        assertEquals(
            "https://sfbay.craigslist.org/search/zip?format=rss",
            CraigslistTemplates.composeFeedUrl(sfbay, freeStuff),
        )
        assertEquals(
            "https://sacramento.craigslist.org/search/sss?format=rss",
            CraigslistTemplates.composeFeedUrl(sacramento, allForSale),
        )
    }

    @Test
    fun `composeFeedUrl is well-formed across the entire region by category matrix`() {
        // Cheap exhaustive check — ~50 regions x 7 categories = ~350
        // strings. Verify every produced URL is `https`-scheme,
        // `<region>.craigslist.org`-host, `/search/<category>` path,
        // `?format=rss` query.
        for (region in CraigslistTemplates.REGIONS) {
            for (cat in CraigslistTemplates.CATEGORIES) {
                val url = CraigslistTemplates.composeFeedUrl(region, cat)
                assertTrue(
                    "URL should start with https://: $url",
                    url.startsWith("https://"),
                )
                assertTrue(
                    "URL should embed region slug: $url",
                    url.contains("${region.slug}.craigslist.org"),
                )
                assertTrue(
                    "URL should embed category slug: $url",
                    url.contains("/search/${cat.slug}"),
                )
                assertTrue(
                    "URL should request RSS: $url",
                    url.endsWith("?format=rss"),
                )
            }
        }
    }

    @Test
    fun `friendlyTitle includes both region and category labels with Craigslist prefix`() {
        val region = CraigslistTemplates.REGIONS.first { it.slug == "sfbay" }
        val category = CraigslistTemplates.CATEGORIES.first { it.slug == "zip" }
        val title = CraigslistTemplates.friendlyTitle(region, category)
        assertTrue("title should start with 'Craigslist': $title", title.startsWith("Craigslist"))
        assertTrue("title should contain region label: $title", title.contains("SF Bay Area"))
        assertTrue("title should contain category label: $title", title.contains("Free stuff"))
    }

    @Test
    fun `regionFromHost recovers a curated region`() {
        assertEquals(
            "sfbay",
            CraigslistTemplates.regionFromHost("sfbay.craigslist.org")?.slug,
        )
        assertEquals(
            "sacramento",
            CraigslistTemplates.regionFromHost("sacramento.craigslist.org")?.slug,
        )
        assertEquals(
            "nevadacity",
            CraigslistTemplates.regionFromHost("nevadacity.craigslist.org")?.slug,
        )
    }

    @Test
    fun `regionFromHost normalises www prefix and trailing case`() {
        assertNotNull(CraigslistTemplates.regionFromHost("WWW.sfbay.CraigsList.ORG"))
    }

    @Test
    fun `regionFromHost returns null for non-craigslist hosts`() {
        assertNull(CraigslistTemplates.regionFromHost("example.com"))
        assertNull(CraigslistTemplates.regionFromHost("craigslist.org"))
        assertNull(CraigslistTemplates.regionFromHost("forums.craigslist.org"))
        // Unknown subdomain — we don't want to claim a URL we can't
        // route to a curated region.
        assertNull(CraigslistTemplates.regionFromHost("smallville.craigslist.org"))
    }

    @Test
    fun `isCraigslistHost agrees with regionFromHost`() {
        assertTrue(CraigslistTemplates.isCraigslistHost("sfbay.craigslist.org"))
        assertFalse(CraigslistTemplates.isCraigslistHost("notacity.craigslist.org"))
        assertFalse(CraigslistTemplates.isCraigslistHost("example.com"))
    }
}
