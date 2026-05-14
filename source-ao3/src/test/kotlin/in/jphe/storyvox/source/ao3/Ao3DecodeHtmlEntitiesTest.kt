package `in`.jphe.storyvox.source.ao3

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #444 — AO3 fiction descriptions surfaced literal `&amp;amp;`
 * (and similar) because the XML parser only decodes one layer of
 * entities and AO3's summary HTML carries another. Pin the
 * `decodeHtmlEntities` extension so the most common AO3 patterns
 * round-trip to the human-visible form.
 *
 * A future contributor who refactors `stripTags` (or skips entity
 * decoding because Android `Html.fromHtml` handles it) gets caught by
 * the `&amp;amp;` and `Clint Barton & Kate Bishop` assertions before
 * the user does.
 */
class Ao3DecodeHtmlEntitiesTest {

    @Test
    fun decodesDoubleEncodedAmpersand() {
        // The repro case from #444: AO3 Atom feed sometimes carries
        // `&amp;amp;` (the source contains `&amp;` which got escaped
        // again into the XML payload). After XML parsing storyvox
        // sees `&amp;`; decodeHtmlEntities is the second pass that
        // produces a literal `&`.
        assertEquals(
            "Clint Barton & Kate Bishop",
            "Clint Barton &amp; Kate Bishop".decodeHtmlEntities(),
        )
    }

    @Test
    fun decodesCommonNamedEntities() {
        // &nbsp; decodes to a space so the run ends with two spaces:
        // one from the literal " " preceding &nbsp; and one from the
        // entity itself.
        assertEquals("< > \" '  ", "&lt; &gt; &quot; &apos; &nbsp;".decodeHtmlEntities())
    }

    @Test
    fun decodesNumericEntities() {
        // `&#38;` = decimal 38 = '&'; `&#x26;` = hex 0x26 = '&'.
        assertEquals("a & b", "a &#38; b".decodeHtmlEntities())
        assertEquals("a & b", "a &#x26; b".decodeHtmlEntities())
        // typographic ellipsis as &#8230;
        assertEquals("end…", "end&#8230;".decodeHtmlEntities())
    }

    @Test
    fun leavesPlainTextUntouched() {
        // No `&` short-circuit path — pure text should round-trip
        // unchanged so the regex doesn't get exercised on every
        // description.
        assertEquals("Plain text with no entities.", "Plain text with no entities.".decodeHtmlEntities())
    }

    @Test
    fun leavesUnknownNamedEntitiesAlone() {
        // `&foo;` isn't a real entity; we leave it verbatim rather
        // than crashing — Android's full Html.fromHtml would too, and
        // it reads better than nuking the run.
        assertEquals("a &foo; b", "a &foo; b".decodeHtmlEntities())
    }

    @Test
    fun decodesMixedRun() {
        // Realistic snippet: a single-encoded ampersand plus the
        // double-encoded form on the same line. Both decode.
        assertEquals(
            "Tony & Pepper, Tony & Steve",
            "Tony &amp; Pepper, Tony &amp;amp; Steve".decodeHtmlEntities(),
        )
    }
}
