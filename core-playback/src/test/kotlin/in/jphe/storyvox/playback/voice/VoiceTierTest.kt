package `in`.jphe.storyvox.playback.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [VoiceTier]. The whole point of pulling tier
 * derivation out of [VoiceCatalog] was so this rule can be exercised
 * without bringing up the full app — a JVM unit test is enough.
 */
class VoiceTierTest {

    @Test
    fun `piper id ending in -high resolves to High`() {
        assertEquals(QualityLevel.High, VoiceTier.forPiperId("piper_lessac_en_US_high"))
        assertEquals(QualityLevel.High, VoiceTier.forPiperId("piper_glados_en_US_high"))
        assertEquals(QualityLevel.High, VoiceTier.forPiperId("piper_libritts_en_US_high"))
    }

    @Test
    fun `piper id ending in -medium resolves to Medium`() {
        assertEquals(QualityLevel.Medium, VoiceTier.forPiperId("piper_amy_en_US_medium"))
        assertEquals(QualityLevel.Medium, VoiceTier.forPiperId("piper_lessac_en_US_medium"))
    }

    @Test
    fun `piper id ending in -low resolves to Low`() {
        assertEquals(QualityLevel.Low, VoiceTier.forPiperId("piper_alan_en_GB_low"))
        assertEquals(QualityLevel.Low, VoiceTier.forPiperId("piper_kathleen_en_US_low"))
    }

    @Test
    fun `piper parser tolerates extra trailing tokens`() {
        // Future-proofs against a hypothetical schema bump that adds a
        // version suffix (e.g. piper_amy_en_US_medium_v2). Last-token-
        // -wins isn't quite right for that; we scan from the end for the
        // first recognised tier — same outcome here.
        assertEquals(QualityLevel.Medium, VoiceTier.forPiperId("piper_amy_en_US_medium_v2"))
        assertEquals(QualityLevel.High, VoiceTier.forPiperId("piper_lessac_en_US_high_alpha"))
    }

    @Test
    fun `piper parser is case-insensitive`() {
        assertEquals(QualityLevel.High, VoiceTier.forPiperId("piper_lessac_en_US_HIGH"))
        assertEquals(QualityLevel.Medium, VoiceTier.forPiperId("piper_amy_en_US_Medium"))
    }

    @Test
    fun `piper parser falls back to Medium on a malformed id`() {
        // Medium is the catalog's modal tier — an unhelpful id shouldn't
        // get banished to "Low" or thrown out entirely. The fallback is
        // documented on [VoiceTier.forPiperId].
        assertEquals(QualityLevel.Medium, VoiceTier.forPiperId("piper_unknown_en_US_xxx"))
        assertEquals(QualityLevel.Medium, VoiceTier.forPiperId("garbage"))
        assertEquals(QualityLevel.Medium, VoiceTier.forPiperId(""))
    }

    @Test
    fun `kokoro studio voices resolve to Studio`() {
        // Curated upstream-A-graded set. Listed by id so the test
        // explicitly enumerates the contract — if someone changes
        // STUDIO_KOKORO_IDS, this test must be updated, which surfaces
        // the change for review instead of letting it slip silently.
        assertEquals(QualityLevel.Studio, VoiceTier.forKokoroId("kokoro_heart_en_US_3"))
        assertEquals(QualityLevel.Studio, VoiceTier.forKokoroId("kokoro_bella_en_US_2"))
        assertEquals(QualityLevel.Studio, VoiceTier.forKokoroId("kokoro_nicole_en_US_6"))
    }

    @Test
    fun `non-studio kokoro voices resolve to High`() {
        assertEquals(QualityLevel.High, VoiceTier.forKokoroId("kokoro_aoede_en_US_1"))
        assertEquals(QualityLevel.High, VoiceTier.forKokoroId("kokoro_alloy_en_US_0"))
        assertEquals(QualityLevel.High, VoiceTier.forKokoroId("kokoro_michael_en_US_16"))
        assertEquals(QualityLevel.High, VoiceTier.forKokoroId("kokoro_alice_en_GB_20"))
    }

    @Test
    fun `kokoro mapping rejects ids it doesn't recognise as Studio`() {
        // Defensive — guards against a typo in the Studio set silently
        // mass-promoting the catalog.
        assertEquals(QualityLevel.High, VoiceTier.forKokoroId("kokoro_heart_en_GB_99"))
        assertEquals(QualityLevel.High, VoiceTier.forKokoroId("kokoro_bell_en_US_2"))
        assertEquals(QualityLevel.High, VoiceTier.forKokoroId(""))
    }

    @Test
    fun `studio set is small and curated`() {
        // Studio is meant to be a peak, not a second 'High' bucket. If
        // this assertion ever fails, audit before bumping it — we'd
        // rather under-grant Studio than over-grant it.
        assertTrue(
            "Studio kokoro set is unexpectedly large (${VoiceTier.STUDIO_KOKORO_IDS.size}); audit before bumping",
            VoiceTier.STUDIO_KOKORO_IDS.size <= 5,
        )
    }

    @Test
    fun `catalog kokoro entries pick up tier from VoiceTier`() {
        // End-to-end: every Studio id in VoiceTier is reachable via the
        // catalog, AND every Kokoro entry in the catalog whose id is in
        // STUDIO_KOKORO_IDS shows QualityLevel.Studio.
        val kokoroEntries = VoiceCatalog.voices.filter { it.engineType is EngineType.Kokoro }
        for (id in VoiceTier.STUDIO_KOKORO_IDS) {
            val entry = kokoroEntries.firstOrNull { it.id == id }
            assertEquals(
                "$id should be in catalog and tier Studio",
                QualityLevel.Studio,
                entry?.qualityLevel,
            )
        }
        // Nothing else in the kokoro catalog should be Studio.
        val unexpectedStudio = kokoroEntries
            .filter { it.qualityLevel == QualityLevel.Studio }
            .map { it.id }
            .toSet()
        assertEquals(VoiceTier.STUDIO_KOKORO_IDS, unexpectedStudio)
    }
}
