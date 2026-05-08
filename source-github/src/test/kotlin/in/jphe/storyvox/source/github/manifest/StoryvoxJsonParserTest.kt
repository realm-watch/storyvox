package `in`.jphe.storyvox.source.github.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryvoxJsonParserTest {

    @Test fun `full document parses every field`() {
        val sv = StoryvoxJsonParser.parse(
            """
                {
                  "version": 1,
                  "cover": "assets/cover.png",
                  "tags": ["fantasy", "litrpg"],
                  "status": "ongoing",
                  "narrator_voice_id": "en-US-Andrew:DragonHDLatestNeural",
                  "honeypot_selectors": [".audio-only", ".visual-only"]
                }
            """.trimIndent(),
        )!!

        assertEquals(1, sv.version)
        assertEquals("assets/cover.png", sv.cover)
        assertEquals(listOf("fantasy", "litrpg"), sv.tags)
        assertEquals("ongoing", sv.status)
        assertEquals("en-US-Andrew:DragonHDLatestNeural", sv.narratorVoiceId)
        assertEquals(listOf(".audio-only", ".visual-only"), sv.honeypotSelectors)
    }

    @Test fun `minimal document with only version parses`() {
        val sv = StoryvoxJsonParser.parse("""{ "version": 1 }""")!!
        assertEquals(1, sv.version)
        assertNull(sv.cover)
        assertTrue(sv.tags.isEmpty())
        assertNull(sv.status)
        assertNull(sv.narratorVoiceId)
        assertTrue(sv.honeypotSelectors.isEmpty())
    }

    @Test fun `unknown keys are tolerated for forward-compat`() {
        val sv = StoryvoxJsonParser.parse(
            """
                {
                  "version": 1,
                  "future_extension": "shrug",
                  "tags": ["fantasy"]
                }
            """.trimIndent(),
        )
        assertNotNull(sv)
        assertEquals(listOf("fantasy"), sv!!.tags)
    }

    @Test fun `unparseable input returns null`() {
        assertNull(StoryvoxJsonParser.parse("not json {"))
        assertNull(StoryvoxJsonParser.parse("[1, 2, 3]"))
    }

    @Test fun `version defaults to 1 when omitted`() {
        // Curators may forget to author the version field; we don't
        // hard-fail on it.
        val sv = StoryvoxJsonParser.parse("""{ "tags": [] }""")
        assertNotNull(sv)
        assertEquals(1, sv!!.version)
    }
}
