package `in`.jphe.storyvox.playback.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalog-seam tests for the Azure HD voice integration. PR-1 of
 * Solara's Azure spec lands the data-shape changes; this file pins
 * them so a future catalog refactor can't quietly drop the cost
 * field or the Azure entries.
 */
class AzureCatalogTest {

    @Test
    fun `EngineType Azure equality is structural over voice and region`() {
        val a = EngineType.Azure("en-US-AvaDragonHDLatestNeural", "eastus")
        val b = EngineType.Azure("en-US-AvaDragonHDLatestNeural", "eastus")
        val c = EngineType.Azure("en-US-AvaDragonHDLatestNeural", "westus2")
        val d = EngineType.Azure("en-US-AndrewDragonHDLatestNeural", "eastus")

        assertEquals("identical params equal", a, b)
        assertEquals("identical hashCodes", a.hashCode(), b.hashCode())
        assertTrue("different region not equal", a != c)
        assertTrue("different voice not equal", a != d)
    }

    @Test
    fun `Azure entries surface in the catalog`() {
        val azureEntries = VoiceCatalog.voices.filter { it.engineType is EngineType.Azure }
        assertTrue(
            "at least one Azure voice in the catalog",
            azureEntries.isNotEmpty(),
        )
        // PR-7 (#186) widened from PR-1's ~5 entries to the full
        // curated roster (~20: Dragon HD set + en-US/GB/AU/IN/CA HD
        // Neural). Upper bound stays in place to defend against the
        // "hardcoded curated for v1" decision (open question #5)
        // silently mushrooming into the full ~400-voice fetch.
        assertTrue(
            "curated list stays bounded (≤25 entries)",
            azureEntries.size <= 25,
        )
    }

    @Test
    fun `Azure entries carry a non-null cost`() {
        val azureEntries = VoiceCatalog.voices.filter { it.engineType is EngineType.Azure }
        for (entry in azureEntries) {
            assertNotNull("entry ${entry.id} has cost", entry.cost)
            // Solara's spec pins $30/1M chars across the curated set —
            // both Dragon HD and HD Neural land at the same price as
            // of 2026-05. If pricing diverges we'll add per-tier cost.
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
    fun `Azure entries are Studio tier`() {
        val azureEntries = VoiceCatalog.voices.filter { it.engineType is EngineType.Azure }
        for (entry in azureEntries) {
            assertEquals(
                "entry ${entry.id} surfaces as Studio tier",
                QualityLevel.Studio,
                entry.qualityLevel,
            )
        }
    }

    @Test
    fun `Azure entries have zero sizeBytes`() {
        // Cloud voices have nothing to download; sizeBytes drives the
        // download progress bar in the picker, which would be nonsense
        // for an Azure voice. PR-4 will surface a "configure key"
        // affordance instead.
        val azureEntries = VoiceCatalog.voices.filter { it.engineType is EngineType.Azure }
        for (entry in azureEntries) {
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
    fun `Azure voice ids are uniquely prefixed`() {
        val azureIds = VoiceCatalog.voices
            .filter { it.engineType is EngineType.Azure }
            .map { it.id }
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
    fun `byId resolves an Azure entry`() {
        val entry = VoiceCatalog.byId("azure_ava_en_US_dragon_hd")
        assertNotNull(entry)
        assertTrue(
            "engineType is Azure",
            entry!!.engineType is EngineType.Azure,
        )
        val azure = entry.engineType as EngineType.Azure
        assertEquals("en-US-AvaDragonHDLatestNeural", azure.voiceName)
        assertEquals("eastus", azure.region)
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
