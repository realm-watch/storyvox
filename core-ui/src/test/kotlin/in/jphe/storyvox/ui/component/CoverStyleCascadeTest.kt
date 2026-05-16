package `in`.jphe.storyvox.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the [chooseFallbackStyle] resolver — the pure function the
 * [FictionCoverThumb] cascade dispatches on when the remote cover URL
 * is missing, expired, or fails to load.
 *
 * v0.5.59 (#cover-style-toggle) — introduced as part of the
 * user-facing book-cover style toggle. JP's audit found the v0.5.51
 * BrandedCoverTile was overwriting the visual "this is a placeholder"
 * affordance; the new default reverts to [MonogramSigilTile] and
 * users opt into Branded via Settings → Appearance.
 */
class CoverStyleCascadeTest {

    @Test
    fun `Monogram style always resolves to the monogram tile`() {
        // The classic minimalist sigil tile is style-stable: title
        // content doesn't change the tile choice, only the monogram
        // letter it draws (which is the caller's responsibility).
        assertEquals(
            ResolvedFallback.Monogram,
            chooseFallbackStyle(CoverStyleLocal.Monogram, title = "Some title"),
        )
        assertEquals(
            ResolvedFallback.Monogram,
            chooseFallbackStyle(CoverStyleLocal.Monogram, title = ""),
        )
        assertEquals(
            ResolvedFallback.Monogram,
            chooseFallbackStyle(CoverStyleLocal.Monogram, title = "   "),
        )
    }

    @Test
    fun `Branded style resolves to the branded tile when title is non-blank`() {
        assertEquals(
            ResolvedFallback.Branded,
            chooseFallbackStyle(CoverStyleLocal.Branded, title = "A Practical Guide"),
        )
        assertEquals(
            ResolvedFallback.Branded,
            chooseFallbackStyle(CoverStyleLocal.Branded, title = "x"),
        )
    }

    @Test
    fun `Branded style falls back to the monogram tile on a blank title`() {
        // The branded tile's dominant visual is the title typeset in
        // EB Garamond; with no title to render the result reads as
        // broken, so the cascade falls to the sigil tile.
        assertEquals(
            ResolvedFallback.Monogram,
            chooseFallbackStyle(CoverStyleLocal.Branded, title = ""),
        )
        assertEquals(
            ResolvedFallback.Monogram,
            chooseFallbackStyle(CoverStyleLocal.Branded, title = "   "),
        )
        assertEquals(
            ResolvedFallback.Monogram,
            chooseFallbackStyle(CoverStyleLocal.Branded, title = "\n\t"),
        )
    }

    @Test
    fun `CoverOnly style always resolves to the dim placeholder`() {
        // The user has asked us not to fabricate cover-shaped content.
        // Even with a perfectly serviceable title we render the dim
        // brass-ring outline — the contract is "real cover or nothing
        // salient".
        assertEquals(
            ResolvedFallback.Dim,
            chooseFallbackStyle(CoverStyleLocal.CoverOnly, title = "Has a title"),
        )
        assertEquals(
            ResolvedFallback.Dim,
            chooseFallbackStyle(CoverStyleLocal.CoverOnly, title = ""),
        )
    }
}
