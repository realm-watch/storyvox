package `in`.jphe.storyvox.source.royalroad.tagsync

import `in`.jphe.storyvox.data.auth.SessionState
import `in`.jphe.storyvox.data.repository.AuthRepository
import `in`.jphe.storyvox.data.repository.sync.FollowedTagsStore
import `in`.jphe.storyvox.data.source.SourceIds
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Orchestrates a single sync round-trip for Royal Road's saved-
 * tags ↔ storyvox-followed-tags mirror (issue #178).
 *
 * Triggers (per #178 spec):
 *
 * 1. **On initial RR sign-in** — `:app` observes
 *    [AuthRepository.sessionState] flipping to
 *    [SessionState.Authenticated] and invokes [syncNow] once. The
 *    first sync pulls RR's saved tags into the local mirror and
 *    pushes any local-only tags the user had set before signing in.
 *
 * 2. **Periodically** — `WorkManager` enqueues a
 *    [RoyalRoadTagSyncWorker] with a 24h cadence + battery-not-low
 *    + connected-network constraints. Each tick invokes [syncNow].
 *
 * 3. **On every local tag toggle** — the storyvox follow-filter
 *    surface calls [pushImmediateChange] right after writing the
 *    local mirror. Best-effort; failures are silent and the next
 *    24h periodic re-pushes from the canonical local state.
 *
 * 4. **Sync now button** — the Settings UI calls [syncNow]
 *    directly when the user taps the brass "Sync now" button.
 *
 * Conflict policy: timestamp-based last-write-wins with a 5-min
 * prefer-local window. Implemented in pure form by [TagSyncMerge];
 * this coordinator is just plumbing.
 */
@Singleton
class RoyalRoadTagSyncCoordinator @Inject internal constructor(
    private val source: RoyalRoadTagSyncSource,
    private val store: FollowedTagsStore,
    private val auth: AuthRepository,
) {

    /**
     * Run one full round-trip. Idempotent and safe to call
     * concurrently — the underlying [RateLimitedClient] serialises
     * all RR requests via its mutex.
     *
     * Returns a structured [Outcome] for the worker / UI so the
     * "Last synced" affordance can surface state ("skipped — not
     * signed in", "ok — 3 tags merged", etc).
     */
    suspend fun syncNow(): Outcome {
        // Cheap guards first.
        if (!store.syncEnabled(SourceIds.ROYAL_ROAD).first()) return Outcome.Disabled
        val session = auth.sessionState(SourceIds.ROYAL_ROAD).first()
        if (session !is SessionState.Authenticated) return Outcome.NotAuthenticated

        // 1. Snapshot local.
        val localTags = store.snapshotFollowedTags(SourceIds.ROYAL_ROAD)
        val localTombs = store.tombstones(SourceIds.ROYAL_ROAD)
        val lastSyncedAt = store.lastSyncedAt(SourceIds.ROYAL_ROAD).first()

        // 2. Pull remote.
        val remotePull = source.fetchSavedTags()
        val parsed = when (remotePull) {
            is RoyalRoadTagSyncSource.Result.Ok -> remotePull.value
            RoyalRoadTagSyncSource.Result.NotAuthenticated -> return Outcome.NotAuthenticated
            is RoyalRoadTagSyncSource.Result.Error -> return Outcome.Failed(remotePull.message)
        }

        // 3. Merge.
        val now = System.currentTimeMillis()
        val merge = TagSyncMerge.merge(
            localTags = localTags,
            localTombstones = localTombs,
            remoteTags = parsed.savedTags,
            lastSyncedAt = lastSyncedAt,
            now = now,
        )

        // 4. Apply locally — replace with the canonical set. The
        //    store stamps a fresh local-write so SettingsSnapshotSource
        //    knows there's something new to push to InstantDB.
        if (merge.toAddLocally.isNotEmpty() || merge.toRemoveLocally.isNotEmpty()) {
            store.replaceFollowedTags(SourceIds.ROYAL_ROAD, merge.merged)
        }

        // 5. Push to RR if there are remote-side changes to apply,
        //    OR if any local tombstone is still effective (we need
        //    to tell RR about an active removal).
        val remoteNeedsUpdate =
            merge.toAddRemotely.isNotEmpty() || merge.toRemoveRemotely.isNotEmpty()
        val pushedOk = if (remoteNeedsUpdate) {
            val token = parsed.csrfToken
                ?: return Outcome.Failed("No antiforgery token on read — RR markup changed")
            when (val push = source.pushSavedTags(merge.merged, token)) {
                is RoyalRoadTagSyncSource.Result.Ok -> true
                RoyalRoadTagSyncSource.Result.NotAuthenticated -> return Outcome.NotAuthenticated
                is RoyalRoadTagSyncSource.Result.Error -> {
                    // Spec: "we don't surface them" — return Failed
                    // so the UI can render a dim "last sync failed"
                    // pill but don't trigger any user-visible toast.
                    return Outcome.Failed(push.message)
                }
            }
        } else {
            // No remote changes to push, but record the sync
            // round as successful — we did pull and merge.
            true
        }

        // 6. Hygiene — forget tombstones now reflected on the
        //    remote side. Only safe to do after a successful push.
        if (pushedOk) {
            for (tag in merge.tombstonesToForget) {
                store.forgetTombstone(SourceIds.ROYAL_ROAD, tag)
            }
            store.stampSyncedAt(SourceIds.ROYAL_ROAD, now)
        }

        return Outcome.Ok(
            tagsPulledIn = merge.toAddLocally.size,
            tagsPushedOut = merge.toAddRemotely.size,
            tagsRemovedLocally = merge.toRemoveLocally.size,
            tagsRemovedRemotely = merge.toRemoveRemotely.size,
            syncedAt = now,
        )
    }

    /**
     * Best-effort immediate push after a local follow/unfollow.
     * Spec: "Network failures are retried at 24h next-sync; we
     * don't surface them." So we just call [syncNow] under the
     * hood — it pulls + merges + pushes, which is the safest
     * thing to do whether the local change was an add or a
     * remove. Returns the [Outcome] anyway for testability.
     */
    suspend fun pushImmediateChange(): Outcome = syncNow()

    sealed interface Outcome {
        data class Ok(
            val tagsPulledIn: Int,
            val tagsPushedOut: Int,
            val tagsRemovedLocally: Int,
            val tagsRemovedRemotely: Int,
            val syncedAt: Long,
        ) : Outcome

        /** User isn't signed in to RR — skipped silently. */
        data object NotAuthenticated : Outcome

        /** User has tag-sync disabled in Settings. */
        data object Disabled : Outcome

        /** Network / parser failure. Surfaced to UI as a dim status
         *  pill, NOT a toast — spec: "we don't surface them." */
        data class Failed(val message: String) : Outcome
    }
}
