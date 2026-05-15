package `in`.jphe.storyvox.data.repository.sync

import kotlinx.coroutines.flow.Flow

/**
 * Per-source followed-tags set + per-tag tombstones, owned by
 * `:app`'s DataStore and exposed up to `:source-royalroad` (and
 * any future tag-sync-capable source) through this thin
 * interface (issue #178).
 *
 * **Why an interface in `:core-data`** — same shape as
 * [SettingsSnapshotSource]. The DataStore implementation lives in
 * `:app`'s `SettingsRepositoryUiImpl` (single owner of the
 * `"storyvox_settings"` store), and `:source-royalroad` consumes
 * the abstraction so it doesn't have to depend on `:app`.
 *
 * **Per-source** — every implementation that supports server-side
 * tag-sync (Royal Road today; AO3 / Discord may follow) keys its
 * tags under a sourceId. The local mirror's wire shape is a JSON
 * object stored in `pref_rr_tag_sync_followed_tags_v1` (etc),
 * but callers see a plain `Set<String>` here — the store handles
 * serialisation.
 *
 * **Tombstones** — when the user explicitly unfollows a tag on
 * this device, the store records the removal timestamp under
 * [tombstones]. The next sync round consults the tombstones to
 * decide whether a remote re-add wins (per
 * [TagSyncMerge.PREFER_LOCAL_WINDOW_MS]).
 */
interface FollowedTagsStore {

    /**
     * Hot stream of the currently-followed-tags set for [sourceId].
     * Settings UI and the sync coordinator both observe this.
     */
    fun followedTags(sourceId: String): Flow<Set<String>>

    /** One-shot snapshot of the followed-tags set for [sourceId]. */
    suspend fun snapshotFollowedTags(sourceId: String): Set<String>

    /** Map of tag → epoch-ms timestamp of an explicit local removal. */
    suspend fun tombstones(sourceId: String): Map<String, Long>

    /**
     * Replace the followed-tags set for [sourceId] with [tags].
     * Used by the sync coordinator to apply a remote-merged set.
     * Stamps a wall-clock write so [SettingsSnapshotSource.lastLocalWriteAt]
     * advances and the next round-trip pushes the new state.
     */
    suspend fun replaceFollowedTags(sourceId: String, tags: Set<String>)

    /**
     * Toggle a single tag on or off for [sourceId]. On removal,
     * records a tombstone at [at]. On re-add, clears any
     * existing tombstone (the user re-followed something they
     * had removed — that's a positive intent, the tombstone is
     * obsolete).
     */
    suspend fun setTagFollowed(
        sourceId: String,
        tag: String,
        followed: Boolean,
        at: Long = System.currentTimeMillis(),
    )

    /**
     * Drop the tombstone for [tag] under [sourceId]. The sync
     * coordinator calls this after a successful push so the
     * tombstone log doesn't grow without bound.
     */
    suspend fun forgetTombstone(sourceId: String, tag: String)

    /**
     * Epoch-ms timestamp of the last successful tag sync round-
     * trip with the remote (RR). Driven by the sync coordinator
     * itself, surfaced to the UI for the "Last synced: <relative>"
     * affordance.
     */
    fun lastSyncedAt(sourceId: String): Flow<Long>

    /** Stamp a successful sync round-trip at [at]. */
    suspend fun stampSyncedAt(sourceId: String, at: Long = System.currentTimeMillis())

    /**
     * Whether tag-sync is enabled for [sourceId]. Defaults to
     * `true` when the user is signed in to that source. Issue
     * #178: `pref_rr_tag_sync_enabled` boolean.
     */
    fun syncEnabled(sourceId: String): Flow<Boolean>

    /** Set the sync-enabled toggle for [sourceId]. */
    suspend fun setSyncEnabled(sourceId: String, enabled: Boolean)
}
