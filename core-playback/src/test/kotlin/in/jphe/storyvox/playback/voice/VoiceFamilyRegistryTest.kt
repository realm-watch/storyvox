package `in`.jphe.storyvox.playback.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plugin-seam Phase 4 (#501) — registry shape + EngineType mapping
 * tests for [VoiceFamilyRegistry].
 *
 * Lives in `:core-playback` (the registry's home module) so the
 * `EngineType.voiceFamilyId()` extension can be exercised against
 * each [EngineType] variant directly. The Plugin Manager card
 * filter logic is covered separately in
 * `:feature`'s `VoiceFamilyFilterTest`.
 */
class VoiceFamilyRegistryTest {

    @Test fun `registry exposes Piper Kokoro Kitten Azure and the upstream placeholder`() {
        val registry = VoiceFamilyRegistry()
        val ids = registry.descriptors.map { it.id }
        assertEquals(
            "Registry should ship exactly five descriptors today",
            5,
            registry.descriptors.size,
        )
        assertTrue(VoiceFamilyIds.PIPER in ids)
        assertTrue(VoiceFamilyIds.KOKORO in ids)
        assertTrue(VoiceFamilyIds.KITTEN in ids)
        assertTrue(VoiceFamilyIds.AZURE in ids)
        assertTrue(VoiceFamilyIds.VOXSHERPA_UPSTREAMS in ids)
    }

    @Test fun `byId returns null for unknown ids`() {
        val registry = VoiceFamilyRegistry()
        assertEquals(null, registry.byId("voice_does_not_exist"))
    }

    @Test fun `EngineType Piper maps to PIPER family`() {
        assertEquals(VoiceFamilyIds.PIPER, EngineType.Piper.voiceFamilyId())
    }

    @Test fun `EngineType Kokoro maps to KOKORO family regardless of speakerId`() {
        assertEquals(VoiceFamilyIds.KOKORO, EngineType.Kokoro(speakerId = 0).voiceFamilyId())
        assertEquals(VoiceFamilyIds.KOKORO, EngineType.Kokoro(speakerId = 52).voiceFamilyId())
    }

    @Test fun `EngineType Kitten maps to KITTEN family regardless of speakerId`() {
        assertEquals(VoiceFamilyIds.KITTEN, EngineType.Kitten(speakerId = 0).voiceFamilyId())
        assertEquals(VoiceFamilyIds.KITTEN, EngineType.Kitten(speakerId = 7).voiceFamilyId())
    }

    @Test fun `EngineType Azure maps to AZURE family regardless of voice name`() {
        assertEquals(
            VoiceFamilyIds.AZURE,
            EngineType.Azure(voiceName = "en-US-AvaDragonHDLatestNeural", region = "eastus").voiceFamilyId(),
        )
    }

    @Test fun `placeholder descriptor is not toggleable`() {
        val registry = VoiceFamilyRegistry()
        val placeholder = registry.byId(VoiceFamilyIds.VOXSHERPA_UPSTREAMS)
        assertNotNull(placeholder)
        assertTrue(placeholder!!.isPlaceholder)
        assertFalse(VoiceFamilyIds.VOXSHERPA_UPSTREAMS in registry.toggleableIds)
    }

    @Test fun `every voice in VoiceCatalog maps to a known family`() {
        val knownFamilies = VoiceFamilyRegistry().descriptors.mapTo(mutableSetOf()) { it.id }
        // VoiceCatalog.voices covers Piper / Kokoro / Kitten (Azure is
        // populated at runtime from the roster, not here). Every static
        // catalog entry's engine should land on a registered family.
        VoiceCatalog.voices.forEach { entry ->
            val familyId = entry.engineType.voiceFamilyId()
            assertTrue(
                "Voice ${entry.id} (engine ${entry.engineType}) maps to $familyId which is not in the registry",
                familyId in knownFamilies,
            )
        }
    }
}
