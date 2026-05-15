package `in`.jphe.storyvox.source.royalroad.tagsync

/**
 * Pure merge logic for the RR ↔ storyvox tag-sync mirror (issue
 * #178). Extracted from the syncer so it can be tested in isolation
 * against a fixed clock and a deterministic input set.
 *
 * Policy (per issue #178 spec):
 *
 * **Adds** — union. If either side has a tag the other doesn't,
 * keep it. Reasoning: a tag-add is monotonically additive; the
 * cost of carrying an extra tag is small (the user's filter set
 * grows by one), the cost of losing one is large (the user's
 * intent to track that tag is silently dropped).
 *
 * **Removes** — timestamp-based last-write-wins, with a
 * **5-minute prefer-local window**. If both sides changed within
 * that window, prefer storyvox's local change (the user's intent
 * on *this* device is the most current). Outside the window, the
 * side with the newer timestamp wins.
 *
 * **Tombstones** — per-tag local-remove timestamps live in
 * [TagSyncState.localTombstones]. A tag is "tombstoned" on the
 * local side when the user explicitly unfollowed it. The merge
 * applies tombstones to the remote set so a re-add by the remote
 * doesn't resurrect a tag the user actively removed.
 *
 * **Last-sync stamp** — [TagSyncState.lastSyncedAt] is the wall-
 * clock time of the *previous* successful sync. Used for the
 * 5-minute conflict window only.
 */
internal object TagSyncMerge {

    /** Milliseconds that count as "both modified at the same time"
     *  for the prefer-local tiebreaker. Issue #178 spec: 5 minutes. */
    const val PREFER_LOCAL_WINDOW_MS: Long = 5 * 60 * 1_000L

    /**
     * Merge a local view (storyvox's [`pref_followed_tags_v1`] set
     * + per-tag tombstones) with the remote view (RR's saved-tags
     * set, just-fetched). Returns the canonical set that both
     * sides should end up holding.
     *
     *  - [localTags]: the user's currently-followed-tags set in storyvox.
     *  - [localTombstones]: per-tag epoch-ms timestamps for tags the
     *    user explicitly removed on this device. Tags absent from
     *    the map were never explicitly removed locally.
     *  - [remoteTags]: the saved-tags set fetched from RR right now.
     *  - [lastSyncedAt]: epoch-ms of the previous successful sync.
     *    Used to compute the 5-minute prefer-local window — any
     *    local tombstone newer than [lastSyncedAt] is considered
     *    "new since last sync" and wins over a remote re-add.
     *  - [now]: clock injected for testability.
     */
    fun merge(
        localTags: Set<String>,
        localTombstones: Map<String, Long>,
        remoteTags: Set<String>,
        lastSyncedAt: Long,
        now: Long,
    ): MergeResult {
        // 1. Union the adds. Both sides keep what they had, plus
        //    whatever the other side has.
        val unioned = localTags + remoteTags

        // 2. Apply tombstones — any tag the user explicitly removed
        //    locally since the last sync, OR within the 5-min
        //    prefer-local window of remote re-add, gets dropped.
        //    Outside the window, a tombstone older than the
        //    apparent remote add stamp can lose; we don't have
        //    per-tag remote stamps (RR doesn't expose them), so we
        //    proxy with `now - PREFER_LOCAL_WINDOW_MS` — any
        //    tombstone stamped within that window of `now` wins.
        val effectiveTombstones = localTombstones.filter { (_, stamp) ->
            // A tombstone wins if either:
            //   - it was recorded after the last successful sync
            //     (we never told the remote about it yet), OR
            //   - it was recorded within PREFER_LOCAL_WINDOW_MS of
            //     now (the user's intent on this device is fresh
            //     enough to beat any remote re-add).
            stamp > lastSyncedAt || (now - stamp) <= PREFER_LOCAL_WINDOW_MS
        }
        val merged = unioned - effectiveTombstones.keys

        // 3. Compute the diff vs each side so the caller knows
        //    what to apply where.
        val toAddLocally = (merged - localTags)
        val toRemoveLocally = (localTags - merged)
        val toAddRemotely = (merged - remoteTags)
        val toRemoveRemotely = (remoteTags - merged)

        // 4. Tombstone hygiene — any tombstone stamped older than
        //    the new "now" sync stamp can be forgotten once
        //    persisted, because the remote side has now been told
        //    about the removal. The syncer is the right place to
        //    drop them after a successful push.
        val tombstonesToForget = localTombstones.keys - effectiveTombstones.keys

        return MergeResult(
            merged = merged,
            toAddLocally = toAddLocally,
            toRemoveLocally = toRemoveLocally,
            toAddRemotely = toAddRemotely,
            toRemoveRemotely = toRemoveRemotely,
            tombstonesToForget = tombstonesToForget,
        )
    }

    data class MergeResult(
        /** The canonical set both sides should hold post-merge. */
        val merged: Set<String>,
        /** Tags to add to the local storyvox set. */
        val toAddLocally: Set<String>,
        /** Tags to remove from the local storyvox set. */
        val toRemoveLocally: Set<String>,
        /** Tags to push to the RR side (the writer needs this list). */
        val toAddRemotely: Set<String>,
        /** Tags to push as removed on the RR side. */
        val toRemoveRemotely: Set<String>,
        /** Tombstones the syncer can forget after a successful push. */
        val tombstonesToForget: Set<String>,
    )
}
