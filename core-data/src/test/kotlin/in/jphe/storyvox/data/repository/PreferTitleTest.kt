package `in`.jphe.storyvox.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #279 — regression coverage for the title-degradation guard in
 * [preferTitle] / [inferUrlHost].
 *
 * The bug: pull-to-refresh on a Library card was silently overwriting
 * 'Lion's Roar' with 'lionsroar.com' because the RSS source falls back
 * to the URL host on parse failure and FictionRepository.toEntity wrote
 * that fallback verbatim. These tests pin the guard so the next time
 * someone "simplifies" the merge logic, the regression caught here
 * before it ships.
 */
class PreferTitleTest {

    @Test
    fun `prefers incoming when it is genuinely new and existing is blank`() {
        assertEquals("Lion's Roar", preferTitle(
            incoming = "Lion's Roar",
            existing = "",
            sourceFallback = "lionsroar.com",
        ))
    }

    @Test
    fun `keeps existing when incoming is blank`() {
        assertEquals("Lion's Roar", preferTitle(
            incoming = "",
            existing = "Lion's Roar",
            sourceFallback = "lionsroar.com",
        ))
    }

    @Test
    fun `keeps existing when incoming equals the source URL host fallback`() {
        // This is THE #279 case: feed parse came up blank, RSS source
        // returned "lionsroar.com" as the title, and toEntity used to
        // overwrite the cached real title with it.
        assertEquals("Lion's Roar", preferTitle(
            incoming = "lionsroar.com",
            existing = "Lion's Roar",
            sourceFallback = "lionsroar.com",
        ))
    }

    @Test
    fun `host equality check is case-insensitive`() {
        // Sources may capitalise the host inconsistently; the guard
        // should still recognise the fallback shape.
        assertEquals("Lion's Roar", preferTitle(
            incoming = "LionsRoar.com",
            existing = "Lion's Roar",
            sourceFallback = "lionsroar.com",
        ))
    }

    @Test
    fun `accepts a richer new title even when existing is non-blank`() {
        // The guard MUST NOT freeze the title — if upstream actually
        // sent a non-fallback string, we want to take it (renamed
        // fiction, fixed typo, etc).
        assertEquals("Lion's Roar Magazine", preferTitle(
            incoming = "Lion's Roar Magazine",
            existing = "Lion's Roar",
            sourceFallback = "lionsroar.com",
        ))
    }

    @Test
    fun `passes incoming through when sourceFallback is null`() {
        // First-add flow: no description means no inferable host. The
        // guard should fall back to the "trust incoming" branch so the
        // stub row gets a title.
        assertEquals("Lion's Roar", preferTitle(
            incoming = "Lion's Roar",
            existing = "",
            sourceFallback = null,
        ))
    }

    @Test
    fun `returns incoming when both incoming and existing are blank`() {
        // Edge case: no signal in either direction. Hand the empty
        // string back so callers can decide. Matches the prior shape.
        assertEquals("", preferTitle(
            incoming = "",
            existing = "",
            sourceFallback = "lionsroar.com",
        ))
    }

    @Test
    fun `inferUrlHost extracts host from a normal URL`() {
        assertEquals("lionsroar.com", inferUrlHost("https://www.lionsroar.com/feed/"))
    }

    @Test
    fun `inferUrlHost strips www prefix to match RSS source's displayLabelForUrl`() {
        // The whole point of the equality check is that RSS source's
        // displayLabelForUrl returns host with "www." stripped. If we
        // didn't match that strip here, the equality would always fail
        // for www-prefixed feeds and the guard would silently regress.
        assertEquals("lionsroar.com", inferUrlHost("https://www.lionsroar.com/podcast"))
    }

    @Test
    fun `inferUrlHost returns null for non-URL strings`() {
        assertNull(inferUrlHost("just some plain text"))
        assertNull(inferUrlHost(""))
        assertNull(inferUrlHost(null))
    }
}
