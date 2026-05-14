package `in`.jphe.storyvox.data.repository

import `in`.jphe.storyvox.data.db.dao.InboxEventDao
import `in`.jphe.storyvox.data.db.entity.InboxEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Issue #383 — cross-source Inbox surface.
 *
 * Three responsibilities:
 *  1. Observe the live event feed for the Library Inbox tab
 *     ([observeAll]).
 *  2. Observe the unread count for the tab's badge
 *     ([observeUnreadCount]).
 *  3. Accept inserts from backend pollers ([record]), coalescing
 *     duplicate "N new chapters in X" events for the same fiction so
 *     the feed doesn't flood after a long offline gap.
 *
 * Per-source mute toggles ARE NOT enforced here — the gating happens at
 * the call site so each backend's update emitter can short-circuit
 * before doing the work to build the event payload. The repo is the
 * write path of last resort; if you call [record], it writes.
 */
interface InboxRepository {

    /** All events, most-recent first. */
    fun observeAll(): Flow<List<InboxEvent>>

    /** Events newer than [afterTs]. */
    fun observeAfter(afterTs: Long): Flow<List<InboxEvent>>

    /** Live unread count — backs the Library Inbox tab badge. */
    fun observeUnreadCount(): Flow<Int>

    /**
     * Insert an event with coalescing. If an existing unread event
     * matches `(sourceId, fictionId)`, its title/body/ts are updated
     * in place — used by the new-chapter poller to roll "1 new chapter"
     * + "2 new chapters" into "3 new chapters" across consecutive
     * polls without flooding the feed.
     *
     * When [fictionId] is null (source-wide events like "KVMR live
     * now") we always insert a new row — there's nothing to coalesce
     * against. Same when no unread row exists for the fiction.
     */
    suspend fun record(
        sourceId: String,
        fictionId: String?,
        chapterId: String?,
        title: String,
        body: String?,
        ts: Long = System.currentTimeMillis(),
        deepLinkUri: String?,
    ): Long

    /** Mark a single event read — fired on row tap. */
    suspend fun markRead(id: Long)

    /** Mark every event read — fired by the "Mark all read" action. */
    suspend fun markAllRead()
}

@Singleton
class InboxRepositoryImpl @Inject constructor(
    private val dao: InboxEventDao,
) : InboxRepository {

    override fun observeAll(): Flow<List<InboxEvent>> = dao.observeAll()

    override fun observeAfter(afterTs: Long): Flow<List<InboxEvent>> =
        dao.observeAfter(afterTs)

    override fun observeUnreadCount(): Flow<Int> = dao.observeUnreadCount()

    override suspend fun record(
        sourceId: String,
        fictionId: String?,
        chapterId: String?,
        title: String,
        body: String?,
        ts: Long,
        deepLinkUri: String?,
    ): Long {
        // Coalesce when we have a fictionId to look up against. The
        // common case: the new-chapter poller fires every N hours and
        // the user hasn't opened the Inbox yet — instead of stacking
        // "1 new chapter" then "2 new chapters" rows, update the
        // existing unread row so the feed shows one entry with the
        // latest count.
        if (fictionId != null) {
            val existing = dao.latestUnreadForFiction(sourceId, fictionId)
            if (existing != null) {
                dao.updateInPlace(
                    id = existing.id,
                    title = title,
                    body = body,
                    ts = ts,
                    deepLinkUri = deepLinkUri,
                )
                return existing.id
            }
        }
        return dao.insert(
            InboxEvent(
                sourceId = sourceId,
                fictionId = fictionId,
                chapterId = chapterId,
                title = title,
                body = body,
                ts = ts,
                isRead = false,
                deepLinkUri = deepLinkUri,
            ),
        )
    }

    override suspend fun markRead(id: Long) = dao.markRead(id)
    override suspend fun markAllRead() = dao.markAllRead()
}
