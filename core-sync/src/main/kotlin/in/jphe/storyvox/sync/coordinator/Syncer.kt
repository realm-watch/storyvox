package `in`.jphe.storyvox.sync.coordinator

import `in`.jphe.storyvox.sync.client.SignedInUser

/**
 * Contract for a per-domain sync handler.
 *
 * One implementation per "thing that should sync" — library, follows,
 * reading positions, bookmarks, pronunciation dict, secrets, etc. The
 * [SyncCoordinator] schedules calls; each syncer owns its local IO and
 * is responsible for pushing/pulling its own InstantDB entity.
 *
 * Why a thin interface and not a generic "sync everything" macro: each
 * domain has subtly different conflict rules (LWW vs union vs max), so
 * the syncer is where domain knowledge lives. The coordinator's job is
 * orchestration — when to call which, retries, surfacing failures.
 */
interface Syncer {

    /** Stable identifier — used in logs and as a dedup key in the
     *  coordinator. */
    val name: String

    /**
     * Push the local state to InstantDB. Called whenever the local
     * source mutates, plus on first sign-in to upload existing state
     * (the "migration" path called out in JP's brief).
     *
     * Implementations must be idempotent — the coordinator may retry on
     * transient failures, and a duplicate transact for the same entity
     * id with the same updatedAt should be a no-op.
     */
    suspend fun push(user: SignedInUser): SyncOutcome

    /**
     * Pull remote state into the local store. Called on cold start after
     * the refresh token verifies, and on any sign-in-on-a-fresh-install.
     *
     * Implementations apply the merge themselves — they own knowing
     * whether to LWW, union, or max-scalar.
     */
    suspend fun pull(user: SignedInUser): SyncOutcome
}

/** Result of a single push or pull. */
sealed interface SyncOutcome {
    /** Everything went through. The optional [recordsAffected] is for
     *  logging / status UI only — there's no contract on the number. */
    data class Ok(val recordsAffected: Int = 0) : SyncOutcome

    /** Network or server failure. The coordinator will retry on its
     *  own schedule; the message is for status UI / logs. */
    data class Transient(val message: String) : SyncOutcome

    /** Permanent failure — corrupt local state, bad credentials, etc.
     *  The coordinator should NOT retry; the syncer's owner needs to
     *  intervene. */
    data class Permanent(val message: String) : SyncOutcome
}
