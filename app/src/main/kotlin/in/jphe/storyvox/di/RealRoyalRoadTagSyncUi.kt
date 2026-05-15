package `in`.jphe.storyvox.di

import `in`.jphe.storyvox.data.repository.sync.FollowedTagsStore
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.feature.api.RoyalRoadTagSyncUi
import `in`.jphe.storyvox.feature.api.UiTagSyncOutcome
import `in`.jphe.storyvox.source.royalroad.tagsync.RoyalRoadTagSyncCoordinator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * `:app`-side bridge between the feature/api facade
 * [RoyalRoadTagSyncUi] (consumed by `RoyalRoadTagSyncViewModel`)
 * and the concrete `:source-royalroad` coordinator + store
 * (issue #178).
 *
 * Pure adapter — no business logic of its own. Same shape as the
 * other `Real*Ui` bindings in this package.
 */
@Singleton
class RealRoyalRoadTagSyncUi @Inject internal constructor(
    private val store: FollowedTagsStore,
    private val coordinator: RoyalRoadTagSyncCoordinator,
) : RoyalRoadTagSyncUi {

    override val syncEnabled: Flow<Boolean> = store.syncEnabled(SourceIds.ROYAL_ROAD)
    override val lastSyncedAt: Flow<Long> = store.lastSyncedAt(SourceIds.ROYAL_ROAD)

    override suspend fun setSyncEnabled(enabled: Boolean) {
        store.setSyncEnabled(SourceIds.ROYAL_ROAD, enabled)
    }

    override suspend fun syncNow(): UiTagSyncOutcome =
        coordinator.syncNow().toUi()

    private fun RoyalRoadTagSyncCoordinator.Outcome.toUi(): UiTagSyncOutcome = when (this) {
        is RoyalRoadTagSyncCoordinator.Outcome.Ok -> UiTagSyncOutcome.Ok(
            tagsPulledIn = tagsPulledIn,
            tagsPushedOut = tagsPushedOut,
            tagsRemovedLocally = tagsRemovedLocally,
            tagsRemovedRemotely = tagsRemovedRemotely,
            syncedAt = syncedAt,
        )
        RoyalRoadTagSyncCoordinator.Outcome.NotAuthenticated -> UiTagSyncOutcome.NotAuthenticated
        RoyalRoadTagSyncCoordinator.Outcome.Disabled -> UiTagSyncOutcome.Disabled
        is RoyalRoadTagSyncCoordinator.Outcome.Failed -> UiTagSyncOutcome.Failed(message)
    }
}
