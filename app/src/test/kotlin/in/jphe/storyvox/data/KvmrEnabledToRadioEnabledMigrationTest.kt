package `in`.jphe.storyvox.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #417 — pins the [KvmrEnabledToRadioEnabledMigration] contract.
 *
 * The migration runs once on the first read after upgrading to
 * v0.5.32. It promotes any persisted `pref_source_kvmr_enabled` value
 * (whether explicitly toggled by the user or seeded by v0.5.20's
 * default-true) into the new `pref_source_radio_enabled` key, while
 * leaving the legacy key intact for the SourcePluginsMapMigration
 * downstream + the one-cycle SourceIds.KVMR alias.
 *
 * Tests exercise [androidx.datastore.core.DataMigration] directly —
 * cheaper than spinning up a DataStore for a one-shot key transform
 * and avoids the OkioStorage "one instance per file" constraint that
 * makes seed-then-reopen test shapes brittle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KvmrEnabledToRadioEnabledMigrationTest {

    private val legacyKvmr = booleanPreferencesKey("pref_source_kvmr_enabled")
    private val newRadio = booleanPreferencesKey("pref_source_radio_enabled")

    @Test fun `seeds pref_source_radio_enabled from a true legacy KVMR value`() = runTest {
        val before = mutablePreferencesOf(legacyKvmr to true)

        assertTrue(KvmrEnabledToRadioEnabledMigration.shouldMigrate(before))
        val after = KvmrEnabledToRadioEnabledMigration.migrate(before)

        assertEquals(true, after[newRadio])
        // Legacy key preserved for downstream migrations
        // (SourcePluginsMapMigration + the SourceIds.KVMR alias).
        assertEquals(true, after[legacyKvmr])
    }

    @Test fun `seeds pref_source_radio_enabled from a false legacy KVMR value`() = runTest {
        val before = mutablePreferencesOf(legacyKvmr to false)

        assertTrue(KvmrEnabledToRadioEnabledMigration.shouldMigrate(before))
        val after = KvmrEnabledToRadioEnabledMigration.migrate(before)

        assertEquals(false, after[newRadio])
        assertEquals(false, after[legacyKvmr])
    }

    @Test fun `is a no-op on a fresh install with no legacy KVMR key`() = runTest {
        val fresh = emptyPreferences()

        // Migration is a no-op — the JSON-map migration downstream
        // picks up the SourceIds.RADIO default directly.
        assertFalse(KvmrEnabledToRadioEnabledMigration.shouldMigrate(fresh))
        // If shouldMigrate is false we don't run migrate() in production
        // anyway; if a caller did, the new key would still be absent.
        val after = KvmrEnabledToRadioEnabledMigration.migrate(fresh)
        // migrate() defaults to TRUE when the legacy key is absent (it
        // can't really happen if shouldMigrate returned false, but the
        // default is documented behavior for safety).
        assertEquals(true, after[newRadio])
    }

    @Test fun `is idempotent when the new radio key is already set`() = runTest {
        // User has already gone through the migration once and toggled
        // the new key independently (or just landed here from a fresh
        // install where the JSON-map seeder wrote the key).
        val before = mutablePreferencesOf(
            legacyKvmr to true,
            newRadio to false,  // user explicitly turned the renamed source off
        )

        // shouldMigrate returns false when the new key is set →
        // user's explicit choice survives.
        assertFalse(KvmrEnabledToRadioEnabledMigration.shouldMigrate(before))
    }

    @Test fun `migration result preserves the legacy key for downstream consumers`() = runTest {
        // SourcePluginsMapMigration runs AFTER this migration in the
        // DataStore migration list. It reads the SOURCE_KVMR_ENABLED
        // value from the same Preferences blob to seed the JSON map's
        // KVMR alias entry. This test pins the contract: legacy key
        // must NOT be deleted by the rename migration.
        val before = mutablePreferencesOf(legacyKvmr to true)
        val after = KvmrEnabledToRadioEnabledMigration.migrate(before)

        assertNull(
            "Legacy key must NOT be cleared — SourcePluginsMapMigration needs it downstream",
            null.takeIf { after[legacyKvmr] == null },
        )
        assertEquals(true, after[legacyKvmr])
    }
}
