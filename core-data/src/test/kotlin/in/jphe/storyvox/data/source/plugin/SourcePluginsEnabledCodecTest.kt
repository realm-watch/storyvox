package `in`.jphe.storyvox.data.source.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plugin-seam Phase 1 (#384) — codec round-trip tests for the JSON
 * map persisted under `pref_source_plugins_enabled_v1`.
 *
 * Covers the failure-mode contracts spelled out in the file's kdoc:
 * - Null / blank / unparseable input returns an empty map (no throw).
 * - Encode → decode round-trips intact for true and false values.
 * - Unknown keys round-trip without losing data
 *   (`ignoreUnknownKeys = true` semantics).
 */
class SourcePluginsEnabledCodecTest {

    @Test fun `null input decodes to empty map`() {
        assertEquals(emptyMap<String, Boolean>(), decodeSourcePluginsEnabledJson(null))
    }

    @Test fun `blank input decodes to empty map`() {
        assertEquals(emptyMap<String, Boolean>(), decodeSourcePluginsEnabledJson(""))
        assertEquals(emptyMap<String, Boolean>(), decodeSourcePluginsEnabledJson("   "))
    }

    @Test fun `unparseable input decodes to empty map without throwing`() {
        assertEquals(emptyMap<String, Boolean>(), decodeSourcePluginsEnabledJson("not json"))
        assertEquals(emptyMap<String, Boolean>(), decodeSourcePluginsEnabledJson("{garbage"))
    }

    @Test fun `round-trip preserves true and false values`() {
        val original = mapOf("kvmr" to true, "royalroad" to false, "notion" to true)

        val encoded = encodeSourcePluginsEnabledJson(original)
        val decoded = decodeSourcePluginsEnabledJson(encoded)

        assertEquals(original, decoded)
    }

    @Test fun `empty map round-trips`() {
        val encoded = encodeSourcePluginsEnabledJson(emptyMap())
        val decoded = decodeSourcePluginsEnabledJson(encoded)
        assertEquals(emptyMap<String, Boolean>(), decoded)
    }

    @Test fun `forward-rolled payload with extra fields does not crash`() {
        // A future schema might add a per-plugin object instead of a
        // bare boolean. Today's decoder should reject the alien shape
        // by returning empty rather than crashing — the impl's
        // fallback path then re-seeds from the legacy keys.
        val alien = """{"kvmr":{"enabled":true,"priority":3}}"""
        val decoded = decodeSourcePluginsEnabledJson(alien)
        assertEquals(emptyMap<String, Boolean>(), decoded)
    }
}
