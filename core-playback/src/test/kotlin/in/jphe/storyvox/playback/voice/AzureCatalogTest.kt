package `in`.jphe.storyvox.playback.voice

import `in`.jphe.storyvox.data.source.AzureVoiceDescriptor
import `in`.jphe.storyvox.data.source.AzureVoiceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalog-seam tests for the Azure HD voice integration.
 *
 * Originally these pinned the v0.4.x hardcoded Azure roster against
 * regression. After the v0.4.84 pivot to live fetch
 * (AzureVoiceProvider) the static catalog no longer carries Azure
 * entries — these tests now exercise [VoiceCatalog.azureEntriesFromRoster]
 * to verify the projection shape (cost field, sizeBytes, ID prefix, etc.)
 * stays correct against a synthesized roster.
 */
class AzureCatalogTest {

    private fun sampleRoster(): List<AzureVoiceDescriptor> = listOf(
        AzureVoiceDescriptor(
            shortName = "en-US-AriaNeural",
            displayName = "Aria",
            locale = "en-US",
            gender = "Female",
            tier = AzureVoiceTier.Neural,
        ),
        AzureVoiceDescriptor(
            shortName = "en-US-Ava:DragonHDLatestNeural",
            displayName = "Ava",
            locale = "en-US",
            gender = "Female",
            tier = AzureVoiceTier.DragonHd,
        ),
        AzureVoiceDescriptor(
            shortName = "en-GB-SoniaNeural",
            displayName = "Sonia",
            locale = "en-GB",
            gender = "Female",
            tier = AzureVoiceTier.Neural,
        ),
    )

    @Test
    fun `EngineType Azure equality is structural over voice and region`() {
        val a = EngineType.Azure("en-US-Ava:DragonHDLatestNeural", "eastus")
        val b = EngineType.Azure("en-US-Ava:DragonHDLatestNeural", "eastus")
        val c = EngineType.Azure("en-US-Ava:DragonHDLatestNeural", "westus2")
        val d = EngineType.Azure("en-US-Andrew:DragonHDLatestNeural", "eastus")

        assertEquals("identical params equal", a, b)
        assertEquals("identical hashCodes", a.hashCode(), b.hashCode())
        assertTrue("different region not equal", a != c)
        assertTrue("different voice not equal", a != d)
    }

    @Test
    fun `static catalog has no Azure entries — they come from the live roster`() {
        val staticAzure = VoiceCatalog.voices.filter { it.engineType is EngineType.Azure }
        assertTrue(
            "static catalog must not include Azure (post live-fetch pivot)",
            staticAzure.isEmpty(),
        )
    }

    @Test
    fun `azureEntriesFromRoster projects descriptors into Azure CatalogEntries`() {
        val entries = VoiceCatalog.azureEntriesFromRoster(sampleRoster())
        assertEquals("one entry per descriptor", 3, entries.size)
        for (entry in entries) {
            assertTrue("engineType is Azure", entry.engineType is EngineType.Azure)
        }
    }

    @Test
    fun `azureEntriesFromRoster carries non-null cost at 3000c per 1M chars`() {
        val entries = VoiceCatalog.azureEntriesFromRoster(sampleRoster())
        for (entry in entries) {
            assertNotNull("entry ${entry.id} has cost", entry.cost)
            assertEquals(
                "entry ${entry.id} priced at 3000¢/1M chars",
                3000,
                entry.cost!!.centsPer1MChars,
            )
            assertEquals(
                "entry ${entry.id} billed by Azure",
                "Azure",
                entry.cost!!.billedBy,
            )
        }
    }

    @Test
    fun `azureEntriesFromRoster surfaces voices as Studio tier`() {
        val entries = VoiceCatalog.azureEntriesFromRoster(sampleRoster())
        for (entry in entries) {
            assertEquals(
                "entry ${entry.id} surfaces as Studio tier",
                QualityLevel.Studio,
                entry.qualityLevel,
            )
        }
    }

    @Test
    fun `azureEntriesFromRoster has zero sizeBytes`() {
        val entries = VoiceCatalog.azureEntriesFromRoster(sampleRoster())
        for (entry in entries) {
            assertEquals("entry ${entry.id} sizeBytes is 0", 0L, entry.sizeBytes)
        }
    }

    @Test
    fun `local engine entries still have null cost`() {
        val localEntries = VoiceCatalog.voices.filter {
            it.engineType !is EngineType.Azure
        }
        // Sanity — no rogue cost field crept onto a Piper or Kokoro
        // entry. Local engines have no per-character bill; cost MUST
        // stay null so the picker's "$X / 1M chars" chip doesn't
        // surface for them.
        for (entry in localEntries) {
            assertNull(
                "local entry ${entry.id} has no cost",
                entry.cost,
            )
        }
    }

    @Test
    fun `azure voice ids are uniquely prefixed`() {
        val azureEntries = VoiceCatalog.azureEntriesFromRoster(sampleRoster())
        val azureIds = azureEntries.map { it.id }
        for (id in azureIds) {
            assertTrue("$id starts with azure_", id.startsWith("azure_"))
        }
        // No collisions with Piper / Kokoro id namespace.
        val nonAzureIds = VoiceCatalog.voices
            .filter { it.engineType !is EngineType.Azure }
            .map { it.id }
            .toSet()
        for (id in azureIds) {
            assertTrue("$id not in non-Azure namespace", id !in nonAzureIds)
        }
    }

    @Test
    fun `azure voice ID escapes colons in DragonHD ShortNames`() {
        // Azure's Dragon HD ShortNames use a colon — `en-US-Ava:DragonHDLatestNeural`.
        // The catalog ID must keep the original ShortName recoverable but
        // not embed a literal colon (some splitters / route-builders treat
        // `:` as a separator). We replace with `_`.
        val entries = VoiceCatalog.azureEntriesFromRoster(sampleRoster())
        val ava = entries.first { (it.engineType as EngineType.Azure).voiceName == "en-US-Ava:DragonHDLatestNeural" }
        assertEquals("azure_en-US-Ava_DragonHDLatestNeural", ava.id)
    }

    @Test
    fun `byIdWithAzure resolves a live Azure entry`() {
        val roster = sampleRoster()
        val entry = VoiceCatalog.byIdWithAzure("azure_en-US-AriaNeural", roster)
        assertNotNull(entry)
        assertTrue(
            "engineType is Azure",
            entry!!.engineType is EngineType.Azure,
        )
        val azure = entry.engineType as EngineType.Azure
        assertEquals("en-US-AriaNeural", azure.voiceName)
    }

    @Test
    fun `byId without roster returns null for Azure entries`() {
        // Static [byId] no longer sees Azure rows — callers that need
        // Azure resolution must use [byIdWithAzure].
        val entry = VoiceCatalog.byId("azure_en-US-AriaNeural")
        assertNull(entry)
    }

    @Test
    fun `VoiceCost is integer cents to dodge fp arithmetic`() {
        // Pin the type — if a future refactor changes centsPer1MChars
        // to a Float / Double, the per-chapter cost calculation
        // (chars × cents / 1_000_000) loses determinism on 32-bit
        // floats. Integer cents stays exact.
        val cost = VoiceCost(centsPer1MChars = 3000, billedBy = "Azure")
        assertEquals(3000, cost.centsPer1MChars)
        assertEquals("Azure", cost.billedBy)
    }
}
