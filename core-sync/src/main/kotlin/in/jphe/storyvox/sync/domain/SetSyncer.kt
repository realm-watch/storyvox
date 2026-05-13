package `in`.jphe.storyvox.sync.domain

import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.ConflictPolicies
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.coordinator.Syncer
import `in`.jphe.storyvox.sync.coordinator.TombstonesAccess

/**
 * Reusable syncer for set-shaped domains — Library, Follows, Starred
 * voices, anything where the state is "this user has marked these IDs."
 *
 * Strategy:
 *  1. Snapshot the local set and the live tombstone set.
 *  2. Pull the remote set (via [remote]).
 *  3. Merge with [ConflictPolicies.unionWithTombstones].
 *  4. Compute the diff vs local — adds, removes.
 *  5. Apply locally (via [localAdd] / [localRemove]) and push the union
 *     plus tombstones back to remote (via [remote]).
 *  6. Forget tombstones that are now reflected in remote.
 *
 * One implementation, three callers — Library, Follows, plus future.
 */
class SetSyncer(
    override val name: String,
    private val tombstones: TombstonesAccess,
    private val localSnapshot: suspend () -> Set<String>,
    private val localAdd: suspend (String) -> Unit,
    private val localRemove: suspend (String) -> Unit,
    private val remote: SetRemote,
) : Syncer {

    /** Abstraction over InstantDB calls so tests don't need a live
     *  network. Implementations transact updates to a single "set"
     *  entity per (user, domain). */
    interface SetRemote {
        suspend fun fetch(user: SignedInUser): Result<RemoteSet>
        suspend fun push(user: SignedInUser, members: Set<String>, tombstones: Set<String>): Result<Unit>
    }

    data class RemoteSet(val members: Set<String>, val tombstones: Set<String>)

    override suspend fun push(user: SignedInUser): SyncOutcome = pushImpl(user)

    override suspend fun pull(user: SignedInUser): SyncOutcome = pushImpl(user)

    /**
     * Push and pull are the same operation for set sync: read both
     * sides, merge, write the result. Calling it "push" or "pull"
     * is purely semantic — they bot end with both sides identical.
     */
    private suspend fun pushImpl(user: SignedInUser): SyncOutcome {
        val local = runCatching { localSnapshot() }
            .getOrElse { return SyncOutcome.Transient("local snapshot: ${it.message}") }
        val localTombs = runCatching { tombstones.snapshot(name) }
            .getOrElse { return SyncOutcome.Transient("tombstones: ${it.message}") }

        val remoteResult = remote.fetch(user)
        val remoteSet = remoteResult.getOrElse {
            return SyncOutcome.Transient("remote fetch: ${it.message}")
        }

        val mergedMembers = ConflictPolicies.unionWithTombstones(
            local = local,
            remote = remoteSet.members,
            tombstones = localTombs + remoteSet.tombstones,
        )
        val mergedTombs = localTombs + remoteSet.tombstones

        // Reconcile local — apply remote-only adds, drop remote tombs.
        val toAddLocally = mergedMembers - local
        val toRemoveLocally = (local - mergedMembers)
        for (id in toAddLocally) {
            runCatching { localAdd(id) }
                .getOrElse { return SyncOutcome.Transient("localAdd($id): ${it.message}") }
        }
        for (id in toRemoveLocally) {
            runCatching { localRemove(id) }
                .getOrElse { return SyncOutcome.Transient("localRemove($id): ${it.message}") }
        }

        // Push the canonical state back so the server matches.
        val push = remote.push(user, mergedMembers, mergedTombs)
        if (push.isFailure) {
            return SyncOutcome.Transient("remote push: ${push.exceptionOrNull()?.message}")
        }

        // Best-effort tombstone hygiene — forget any that are now also
        // server-side, since the server keeps its own copy.
        for (id in remoteSet.tombstones) {
            runCatching { tombstones.forget(name, id) }
        }
        return SyncOutcome.Ok(recordsAffected = toAddLocally.size + toRemoveLocally.size)
    }
}
