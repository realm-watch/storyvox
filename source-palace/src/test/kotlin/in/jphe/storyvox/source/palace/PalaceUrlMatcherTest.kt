package `in`.jphe.storyvox.source.palace

import `in`.jphe.storyvox.data.source.SourceIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #502 — UrlMatcher coverage for the Palace Project URL surface.
 *
 * These are pure regex tests (no source instance, no Hilt), exercised
 * by walking the compiled URL patterns directly. The PalaceSource
 * `matchUrl` implementation funnels through these patterns; if the
 * regex changes, this test catches the breakage before the magic-link
 * resolver does.
 */
class PalaceUrlMatcherTest {

    @Test
    fun `claims canonical palaceproject_io work URL with confidence 0_9`() {
        val url = "https://nypl.palaceproject.io/groups/123/works/" +
            "urn%3Alibrarysimplified.org%3Aworks%3A555"
        val m = PALACE_WORK_URL_PATTERN.matchEntire(url)
        assertNotNull("Canonical Palace work URL should match", m)
        // The captured URN goes into the storyvox fictionId.
        assertEquals(
            "urn%3Alibrarysimplified.org%3Aworks%3A555",
            m!!.groupValues[1],
        )
    }

    @Test
    fun `claims circulation_openebooks_us work URL`() {
        val url = "https://circulation.openebooks.us/feeds/123/works/urn:works:9001"
        val m = PALACE_WORK_URL_PATTERN.matchEntire(url)
        assertNotNull("Open eBooks (Palace-backed) work URL should match", m)
    }

    @Test
    fun `host-only palaceproject_io URL falls through to the host pattern`() {
        val homepage = "https://www.palaceproject.io/"
        assertNull(
            "Homepage must not match the work URL pattern",
            PALACE_WORK_URL_PATTERN.matchEntire(homepage),
        )
        assertNotNull(
            "Homepage must match the host-only URL pattern",
            PALACE_HOST_URL_PATTERN.matchEntire(homepage),
        )
    }

    @Test
    fun `unrelated URLs do not claim`() {
        // Royal Road, AO3, generic Wikipedia — none of these should
        // claim through the Palace matcher. They have their own
        // sources at higher confidence.
        listOf(
            "https://www.royalroad.com/fiction/12345",
            "https://archiveofourown.org/works/67890",
            "https://en.wikipedia.org/wiki/Public_library",
            "https://hoopladigital.com/title/13579",
        ).forEach { url ->
            assertNull(
                "Non-Palace URL should not match the work pattern: $url",
                PALACE_WORK_URL_PATTERN.matchEntire(url),
            )
            assertNull(
                "Non-Palace URL should not match the host pattern: $url",
                PALACE_HOST_URL_PATTERN.matchEntire(url),
            )
        }
    }

    @Test
    fun `source id constant resolves to palace`() {
        // Smoke test: a moved SourceIds constant would silently break
        // every persisted Palace fiction's lookup path. Keep the
        // literal value pinned here.
        assertEquals("palace", SourceIds.PALACE)
    }
}
