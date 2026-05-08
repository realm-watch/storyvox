package `in`.jphe.storyvox.source.azure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AzureSsmlBuilder]. The whole point of building SSML
 * client-side is so a chapter that contains `<` / `&` / smart quotes
 * doesn't break Azure's strict parser, so escaping is the lion's
 * share of these tests.
 */
class AzureSsmlBuilderTest {

    @Test
    fun `basic build wraps text in voice and prosody`() {
        val ssml = AzureSsmlBuilder.build(
            text = "Hello world.",
            voiceName = "en-US-AvaDragonHDLatestNeural",
        )
        assertTrue("contains <speak> root", ssml.contains("<speak"))
        assertTrue("declares xml lang", ssml.contains("xml:lang=\"en-US\""))
        assertTrue(
            "wraps voice name",
            ssml.contains("<voice name=\"en-US-AvaDragonHDLatestNeural\">"),
        )
        assertTrue("wraps prosody", ssml.contains("<prosody"))
        assertTrue("contains body text", ssml.contains("Hello world."))
        assertTrue("closes speak", ssml.endsWith("</speak>"))
    }

    @Test
    fun `default speed and pitch produce zero-percent prosody`() {
        val ssml = AzureSsmlBuilder.build(
            text = "test",
            voiceName = "en-US-AriaNeural",
            speed = 1.0f,
            pitch = 1.0f,
        )
        assertTrue("speed is +0%", ssml.contains("rate=\"+0%\""))
        assertTrue("pitch is +0%", ssml.contains("pitch=\"+0%\""))
    }

    @Test
    fun `speed greater than 1 emits positive percent`() {
        val ssml = AzureSsmlBuilder.build(
            text = "test",
            voiceName = "v",
            speed = 1.5f,
        )
        assertTrue("speed is +50%", ssml.contains("rate=\"+50%\""))
    }

    @Test
    fun `speed less than 1 emits negative percent`() {
        val ssml = AzureSsmlBuilder.build(
            text = "test",
            voiceName = "v",
            speed = 0.75f,
        )
        // (0.75 - 1) * 100 = -25 → "-25%"
        assertTrue("speed is -25%", ssml.contains("rate=\"-25%\""))
    }

    @Test
    fun `speed clamps to plus 50 percent`() {
        val ssml = AzureSsmlBuilder.build(
            text = "test",
            voiceName = "v",
            speed = 3.0f, // (3-1)*100 = 200 → clamped to 50
        )
        assertTrue("speed clamped to +50%", ssml.contains("rate=\"+50%\""))
    }

    @Test
    fun `speed clamps to minus 50 percent`() {
        val ssml = AzureSsmlBuilder.build(
            text = "test",
            voiceName = "v",
            speed = 0.0f, // (0-1)*100 = -100 → clamped to -50
        )
        assertTrue("speed clamped to -50%", ssml.contains("rate=\"-50%\""))
    }

    @Test
    fun `ampersand is xml-escaped`() {
        val ssml = AzureSsmlBuilder.build(text = "Tom & Jerry", voiceName = "v")
        assertTrue("ampersand escaped", ssml.contains("Tom &amp; Jerry"))
        assertFalse("raw ampersand absent", ssml.contains("Tom & Jerry"))
    }

    @Test
    fun `less than and greater than are escaped`() {
        val ssml = AzureSsmlBuilder.build(
            text = "1 < 2 and 3 > 1",
            voiceName = "v",
        )
        assertTrue(ssml.contains("1 &lt; 2"))
        assertTrue(ssml.contains("3 &gt; 1"))
        assertFalse("raw less-than absent in body", ssml.contains("1 < 2"))
    }

    @Test
    fun `single and double quotes pass through body text`() {
        // Inside element content, `'` and `"` don't need escaping —
        // SSML's character data only requires &, <, > to be escaped.
        // The builder does NOT escape them in body text (only in
        // attribute values), so this test pins that behaviour.
        val ssml = AzureSsmlBuilder.build(
            text = "She said \"hi\" and 'bye'",
            voiceName = "v",
        )
        assertTrue(
            "double quotes in body OK",
            ssml.contains("She said \"hi\""),
        )
        assertTrue("single quotes in body OK", ssml.contains("'bye'"))
    }

    @Test
    fun `voice name with special chars is attribute-escaped`() {
        // Defensive — Azure's voice ids don't currently contain
        // problematic chars, but if a future "fetch voices/list" path
        // surfaces one we still produce valid SSML rather than
        // attribute-injection-vulnerable garbage.
        val ssml = AzureSsmlBuilder.build(
            text = "hello",
            voiceName = "evil-voice\"-injected",
        )
        assertTrue(
            "double quote in attr is escaped",
            ssml.contains("name=\"evil-voice&quot;-injected\""),
        )
        assertFalse(
            "raw double quote in attr absent",
            ssml.contains("name=\"evil-voice\"-injected\""),
        )
    }

    @Test
    fun `escapeForSsml handles ampersand correctly with surrounding entities`() {
        // Order-of-operations check: we escape & first, then <. The naive
        // bug would double-escape & when the input contains both — let's
        // pin the correct shape.
        assertEquals("&lt;a&gt; &amp; &lt;b&gt;", "<a> & <b>".escapeForSsml())
    }

    @Test
    fun `empty text builds a syntactically valid envelope`() {
        // Producer can hand us an empty sentence (e.g. a chapter that's
        // just whitespace). The SSML envelope must still be well-formed
        // — Azure responds with 200 + zero PCM, which the engine handle
        // turns into a skipped sentence.
        val ssml = AzureSsmlBuilder.build(text = "", voiceName = "v")
        assertTrue("contains empty prosody", ssml.contains("<prosody"))
        assertTrue("ends with closing speak tag", ssml.endsWith("</speak>"))
    }

    @Test
    fun `unicode passes through unmodified`() {
        // SSML is XML; XML body text accepts arbitrary Unicode. We pass
        // smart quotes / ellipsis / em-dash through verbatim — Azure's
        // pronunciation lexicon handles them.
        val ssml = AzureSsmlBuilder.build(
            text = "She said “hello”—and waved…",
            voiceName = "v",
        )
        assertTrue(
            "unicode preserved",
            ssml.contains("She said “hello”—and waved…"),
        )
    }
}
