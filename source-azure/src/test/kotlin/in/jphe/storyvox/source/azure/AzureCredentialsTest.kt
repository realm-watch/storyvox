package `in`.jphe.storyvox.source.azure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AzureCredentials]. Uses a map-backed test double
 * subclass so we don't pull in Robolectric or a real
 * EncryptedSharedPreferences — the key/region API surface is tiny
 * enough that pure JUnit covers it.
 */
class AzureCredentialsTest {

    /** Subclass that backs every getter/setter with a MutableMap.
     *  Mirrors the LlmCredentialsStore.forTesting() pattern in
     *  :core-llm. */
    private class FakeAzureCredentials : AzureCredentials() {
        private val backing = mutableMapOf<String, String?>()

        override fun key(): String? = backing[KEY_AZURE_KEY]
        override fun regionId(): String =
            backing[KEY_AZURE_REGION] ?: AzureRegion.DEFAULT.id
        override fun region(): AzureRegion =
            AzureRegion.byId(regionId()) ?: AzureRegion.DEFAULT

        override fun setKey(key: String) {
            backing[KEY_AZURE_KEY] = key
        }
        override fun setRegion(regionId: String) {
            backing[KEY_AZURE_REGION] = regionId
        }
        override fun clear() {
            backing.remove(KEY_AZURE_KEY)
            backing.remove(KEY_AZURE_REGION)
        }
        override val isConfigured: Boolean get() = key() != null
    }

    @Test
    fun `unset key returns null and isConfigured is false`() {
        val c = FakeAzureCredentials()
        assertNull(c.key())
        assertFalse(c.isConfigured)
    }

    @Test
    fun `setKey persists and isConfigured flips true`() {
        val c = FakeAzureCredentials()
        c.setKey("test-key-1234")
        assertEquals("test-key-1234", c.key())
        assertTrue(c.isConfigured)
    }

    @Test
    fun `region defaults to eastus when nothing set`() {
        val c = FakeAzureCredentials()
        assertEquals(AzureRegion.EastUs, c.region())
        assertEquals("eastus", c.regionId())
    }

    @Test
    fun `setRegion with curated id resolves to enum`() {
        val c = FakeAzureCredentials()
        c.setRegion("westeurope")
        assertEquals(AzureRegion.WestEurope, c.region())
        assertEquals("westeurope", c.regionId())
    }

    @Test
    fun `setRegion with raw uncurated id falls back to default enum but keeps raw id`() {
        // The "Other" Settings affordance lets a power user paste a
        // raw region id like "centralus" that isn't in the curated
        // enum. region() returns DEFAULT (so any code keying on the
        // enum picks something sensible), while regionId() returns
        // the raw string verbatim (so endpoint construction uses it).
        val c = FakeAzureCredentials()
        c.setRegion("centralus")
        assertEquals(AzureRegion.DEFAULT, c.region())
        assertEquals("centralus", c.regionId())
    }

    @Test
    fun `clear wipes both key and region`() {
        val c = FakeAzureCredentials()
        c.setKey("k")
        c.setRegion("westus2")
        c.clear()
        assertNull(c.key())
        assertFalse(c.isConfigured)
        // After clear, region falls back to default.
        assertEquals(AzureRegion.DEFAULT, c.region())
    }

    @Test
    fun `forTesting factory yields a never-configured store`() {
        val c = AzureCredentials.forTesting()
        assertNull(c.key())
        assertFalse(c.isConfigured)
        // Writes against the null prefs are no-ops; the store stays
        // un-configured.
        c.setKey("anything")
        assertNull(c.key())
    }

    @Test
    fun `AzureRegion byId resolves all curated entries`() {
        for (region in AzureRegion.entries) {
            assertEquals(region, AzureRegion.byId(region.id))
        }
    }

    @Test
    fun `AzureRegion byId returns null for unknown id`() {
        assertNull(AzureRegion.byId("centralus"))
        assertNull(AzureRegion.byId(""))
        assertNull(AzureRegion.byId("not-a-region"))
    }
}
