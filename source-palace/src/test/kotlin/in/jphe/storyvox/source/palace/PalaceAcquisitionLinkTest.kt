package `in`.jphe.storyvox.source.palace

import `in`.jphe.storyvox.source.palace.parse.OpdsLink
import `in`.jphe.storyvox.source.palace.parse.OpdsMime
import `in`.jphe.storyvox.source.palace.parse.OpdsRel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #502 — load-bearing test for the DRM-handling boundary.
 *
 * The single most important invariant for `:source-palace` v1 is:
 *
 *   *No LCP-licensed acquisition link is ever fetched as if it were a
 *   free EPUB.*
 *
 * If [pickOpenAccessEpub] returns an LCP link, the source would
 * download a JSON license payload, hand it to `:source-epub`'s parser,
 * and surface a "could not parse Palace EPUB" error. Worse, the
 * cache layer would persist the JSON payload, and the next chapter
 * open would silently fail forever. These tests pin the boundary.
 */
class PalaceAcquisitionLinkTest {

    @Test
    fun `prefers open-access EPUB link when present`() {
        val links = listOf(
            OpdsLink(
                rel = OpdsRel.ACQUISITION_OPEN_ACCESS,
                href = "https://lib.example/works/1/free.epub",
                type = OpdsMime.EPUB,
            ),
            OpdsLink(
                rel = OpdsRel.ACQUISITION_BORROW,
                href = "https://lib.example/works/1/borrow",
                type = OpdsMime.LCP_LICENSE,
            ),
        )
        val picked = pickOpenAccessEpub(links)
        assertEquals(
            "Open-access EPUB must win over LCP borrow",
            "https://lib.example/works/1/free.epub",
            picked?.href,
        )
    }

    @Test
    fun `accepts bare acquisition rel when MIME is EPUB`() {
        // Some older Palace deployments use the bare `acquisition` rel
        // instead of `acquisition/open-access`. As long as the MIME is
        // EPUB and not the LCP license, treat it as free.
        val links = listOf(
            OpdsLink(
                rel = OpdsRel.ACQUISITION,
                href = "https://lib.example/works/2/download.epub",
                type = OpdsMime.EPUB,
            ),
        )
        val picked = pickOpenAccessEpub(links)
        assertEquals(
            "Bare acquisition + EPUB MIME = open-access for our purposes",
            "https://lib.example/works/2/download.epub",
            picked?.href,
        )
    }

    @Test
    fun `returns null for LCP-only entries`() {
        // The DRM invariant. Storyvox v1 must surface this title
        // greyed-out with a deep-link CTA, NEVER fetch the license URL.
        val links = listOf(
            OpdsLink(
                rel = OpdsRel.ACQUISITION_BORROW,
                href = "https://lib.example/works/3/borrow",
                type = OpdsMime.LCP_LICENSE,
            ),
        )
        assertNull(
            "DRM-only entry MUST NOT produce an open-access link",
            pickOpenAccessEpub(links),
        )
    }

    @Test
    fun `returns null for audiobook-only entries`() {
        // Readium audiobook manifests are out of scope for v1. The
        // EPUB-only invariant in the picker rejects them cleanly so
        // an audiobook-only Palace title surfaces the same deep-link
        // CTA as an LCP'd one.
        val links = listOf(
            OpdsLink(
                rel = OpdsRel.ACQUISITION_OPEN_ACCESS,
                href = "https://lib.example/works/4/audiobook.json",
                type = OpdsMime.AUDIOBOOK_MANIFEST,
            ),
        )
        assertNull(
            "Audiobook manifests are not EPUB and must be skipped",
            pickOpenAccessEpub(links),
        )
    }

    @Test
    fun `returns null when no acquisition links present`() {
        // Catalog-only entries (cover images, sample chapters but no
        // download link) should never produce an open-access link.
        val links = listOf(
            OpdsLink(
                rel = OpdsRel.IMAGE,
                href = "https://lib.example/covers/5.jpg",
                type = "image/jpeg",
            ),
        )
        assertNull(pickOpenAccessEpub(links))
    }
}
