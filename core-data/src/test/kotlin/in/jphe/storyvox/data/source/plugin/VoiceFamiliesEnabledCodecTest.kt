package `in`.jphe.storyvox.data.source.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plugin-seam Phase 4 (#501) — codec round-trip tests for the JSON
 * map persisted under `pref_voice_families_enabled_v1`. Twin of
 * [SourcePluginsEnabledCodecTest] — same failure-mode contracts.
 */
class VoiceFamiliesEnabledCodecTest {

    @Test fun `null input decodes to empty map`() {
        assertEquals(emptyMap<String, Boolean>(), decodeVoiceFamiliesEnabledJson(null))
    }

    @Test fun `blank input decodes to empty map`() {
        assertEquals(emptyMap<String, Boolean>(), decodeVoiceFamiliesEnabledJson(""))
        assertEquals(emptyMap<String, Boolean>(), decodeVoiceFamiliesEnabledJson("   "))
    }

    @Test fun `unparseable input decodes to empty map without throwing`() {
        assertEquals(emptyMap<String, Boolean>(), decodeVoiceFamiliesEnabledJson("not json"))
        assertEquals(emptyMap<String, Boolean>(), decodeVoiceFamiliesEnabledJson("{garbage"))
    }

    @Test fun `round-trip preserves true and false values`() {
        val original = mapOf(
            "voice_piper" to true,
            "voice_kokoro" to true,
            "voice_kitten" to false,
            "voice_azure" to false,
        )
        val encoded = encodeVoiceFamiliesEnabledJson(original)
        val decoded = decodeVoiceFamiliesEnabledJson(encoded)
        assertEquals(original, decoded)
    }

    @Test fun `empty map round-trips`() {
        val encoded = encodeVoiceFamiliesEnabledJson(emptyMap())
        val decoded = decodeVoiceFamiliesEnabledJson(encoded)
        assertEquals(emptyMap<String, Boolean>(), decoded)
    }

    @Test fun `forward-rolled payload with extra fields does not crash`() {
        // A future schema might add a per-family object instead of a
        // bare boolean. Today's decoder should reject the alien shape
        // by returning empty rather than crashing.
        val alien = """{"voice_piper":{"enabled":true,"version":2}}"""
        val decoded = decodeVoiceFamiliesEnabledJson(alien)
        assertEquals(emptyMap<String, Boolean>(), decoded)
    }
}
