package `in`.jphe.storyvox.sync.coordinator

/**
 * Conflict-resolution primitives shared by the per-domain syncers.
 *
 * The design constraint from JP's brief: "last-write-wins for scalars; for
 * sets/lists (library/follows) use union semantics with tombstones for
 * deletes. Reading position: server keeps the maximum across devices (you
 * generally only listen forward)."
 *
 * Each of these is a pure function so they're trivially unit-testable
 * without needing Room or the network. Concrete syncers compose them with
 * their domain-specific IO.
 */
object ConflictPolicies {

    /**
     * Pick the side with the higher [Stamped.updatedAt]. Ties go to
     * [local] (we'd rather not write back to the local store when nothing
     * meaningfully changed).
     */
    fun <T> lastWriteWins(local: Stamped<T>, remote: Stamped<T>): Stamped<T> =
        if (remote.updatedAt > local.updatedAt) remote else local

    /**
     * Union two sets, suppressing entries that appear in [tombstones].
     * Tombstones win over presence — if A is in local and in tombstones,
     * the merged set won't contain A (the user deleted it on some
     * device, so it should be gone everywhere).
     *
     * This is the library / follows / favorites strategy.
     */
    fun <T> unionWithTombstones(
        local: Set<T>,
        remote: Set<T>,
        tombstones: Set<T>,
    ): Set<T> = (local union remote) - tombstones

    /**
     * Max-of-comparable. Used for reading position — the listener
     * generally only moves forward, so the highest position seen on any
     * device is the right one to land on after a sync.
     *
     * Caveat: if a user actually rewinds a chapter intentionally, max
     * will undo their rewind on next sync. Mitigation: per-fiction
     * positions, not per-chapter — rewinding within a chapter is fine,
     * but jumping back across chapters is rare and explicitly happens
     * via the chapter picker, not the playhead. The chapter picker
     * action updates the per-fiction "current chapter" with a fresh
     * timestamp, which is LWW (not max), so an intentional rewind wins.
     */
    fun <T : Comparable<T>> maxScalar(local: T, remote: T): T =
        if (remote > local) remote else local

    /**
     * Merge two stamped scalars with the [TimestampedMaxComparator]:
     * pick the one with the later updatedAt; ties go to the higher
     * value (so a clock-skewed simultaneous update on two devices
     * doesn't lose the further-forward listener).
     */
    fun <T : Comparable<T>> maxScalarStamped(
        local: Stamped<T>,
        remote: Stamped<T>,
    ): Stamped<T> {
        val tieBreak = local.value.compareTo(remote.value)
        return when {
            remote.updatedAt > local.updatedAt -> remote
            local.updatedAt > remote.updatedAt -> local
            tieBreak >= 0 -> local
            else -> remote
        }
    }
}

/**
 * A value with an updatedAt epoch-millis stamp. Two stamped values are
 * comparable for LWW purposes.
 */
data class Stamped<T>(val value: T, val updatedAt: Long)
