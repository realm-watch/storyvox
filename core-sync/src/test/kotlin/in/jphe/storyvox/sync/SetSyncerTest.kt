package `in`.jphe.storyvox.sync

import `in`.jphe.storyvox.sync.client.FakeInstantBackend
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.coordinator.TombstonesAccess
import `in`.jphe.storyvox.sync.domain.BackendSetRemote
import `in`.jphe.storyvox.sync.domain.SetSyncer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetSyncerTest {

    private val USER = SignedInUser(userId = "u-1", email = null, refreshToken = "rt-1")

    /** Pure-JVM tombstone store substitute. The production
     *  [TombstoneStore] is a DataStore wrapper; tests substitute this
     *  in-memory implementation through the [TombstonesAccess]
     *  interface so they don't need a Robolectric runtime. */
    private class InMemoryTombstones : TombstonesAccess {
        private val byDomain = mutableMapOf<String, MutableSet<String>>()
        override fun observe(domain: String): Flow<Set<String>> =
            flowOf(byDomain[domain]?.toSet() ?: emptySet())
        override suspend fun snapshot(domain: String): Set<String> =
            byDomain[domain]?.toSet() ?: emptySet()
        override suspend fun add(domain: String, id: String) {
            byDomain.getOrPut(domain) { mutableSetOf() }.add(id)
        }
        override suspend fun addAll(domain: String, ids: Collection<String>) {
            byDomain.getOrPut(domain) { mutableSetOf() }.addAll(ids)
        }
        override suspend fun forget(domain: String, id: String) {
            byDomain[domain]?.remove(id)
        }
        override suspend fun clear(domain: String) {
            byDomain.remove(domain)
        }
    }

    @Test fun `first-time push of local set lands the members on remote`() = runTest {
        val backend = FakeInstantBackend()
        val localState = mutableSetOf("a", "b", "c")
        val syncer = SetSyncer(
            name = "library",
            tombstones = InMemoryTombstones(),
            localSnapshot = { localState.toSet() },
            localAdd = { localState.add(it) },
            localRemove = { localState.remove(it) },
            remote = BackendSetRemote("library", backend),
        )

        val outcome = syncer.push(USER)
        assertTrue(outcome is SyncOutcome.Ok)

        // A second "device" pulls — local empty → ends up with all
        // three. This is the migration story end-to-end.
        val device2 = mutableSetOf<String>()
        val syncer2 = SetSyncer(
            name = "library",
            tombstones = InMemoryTombstones(),
            localSnapshot = { device2.toSet() },
            localAdd = { device2.add(it) },
            localRemove = { device2.remove(it) },
            remote = BackendSetRemote("library", backend),
        )
        val pull2 = syncer2.pull(USER)
        assertTrue(pull2 is SyncOutcome.Ok)
        assertEquals(setOf("a", "b", "c"), device2)
    }

    @Test fun `tombstone on one device removes the member on the other after sync`() = runTest {
        val backend = FakeInstantBackend()

        val tombA = InMemoryTombstones()
        tombA.add("library", "b")
        val deviceA = mutableSetOf("a", "c")
        SetSyncer(
            "library", tombA,
            { deviceA.toSet() }, { deviceA.add(it) }, { deviceA.remove(it) },
            BackendSetRemote("library", backend),
        ).push(USER)

        val deviceB = mutableSetOf("a", "b", "c")
        SetSyncer(
            "library", InMemoryTombstones(),
            { deviceB.toSet() }, { deviceB.add(it) }, { deviceB.remove(it) },
            BackendSetRemote("library", backend),
        ).pull(USER)

        // After the pull, device B should no longer have b — the
        // tombstone propagated through the cloud.
        assertEquals(setOf("a", "c"), deviceB)
    }

    @Test fun `union takes both sides additions`() = runTest {
        val backend = FakeInstantBackend()
        val deviceA = mutableSetOf("x")
        val deviceB = mutableSetOf("y")
        val syncerA = SetSyncer(
            "library", InMemoryTombstones(),
            { deviceA.toSet() }, { deviceA.add(it) }, { deviceA.remove(it) },
            BackendSetRemote("library", backend),
        )
        val syncerB = SetSyncer(
            "library", InMemoryTombstones(),
            { deviceB.toSet() }, { deviceB.add(it) }, { deviceB.remove(it) },
            BackendSetRemote("library", backend),
        )
        syncerA.push(USER)
        syncerB.push(USER)
        syncerA.pull(USER)
        assertEquals(setOf("x", "y"), deviceA)
    }
}
