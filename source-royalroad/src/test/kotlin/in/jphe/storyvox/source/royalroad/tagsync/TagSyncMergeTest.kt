package `in`.jphe.storyvox.source.royalroad.tagsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagSyncMergeTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `union of adds when neither side has tombstones`() {
        val result = TagSyncMerge.merge(
            localTags = setOf("action", "litrpg"),
            localTombstones = emptyMap(),
            remoteTags = setOf("litrpg", "high_fantasy"),
            lastSyncedAt = now - 60_000L,
            now = now,
        )

        // Union: action + litrpg + high_fantasy
        assertEquals(setOf("action", "litrpg", "high_fantasy"), result.merged)
        // Local needs to add high_fantasy; nothing to remove locally.
        assertEquals(setOf("high_fantasy"), result.toAddLocally)
        assertEquals(emptySet<String>(), result.toRemoveLocally)
        // Remote needs to add action; nothing to remove remotely.
        assertEquals(setOf("action"), result.toAddRemotely)
        assertEquals(emptySet<String>(), result.toRemoveRemotely)
    }

    @Test
    fun `tombstone newer than last sync wins over remote re-add`() {
        // User unfollowed "comedy" locally 30s ago; remote still
        // has it. The tombstone is newer than last-sync, so the
        // merge drops "comedy" and pushes the removal.
        val tombStamp = now - 30_000L
        val result = TagSyncMerge.merge(
            localTags = setOf("action"),
            localTombstones = mapOf("comedy" to tombStamp),
            remoteTags = setOf("action", "comedy"),
            lastSyncedAt = now - 60_000L,
            now = now,
        )

        assertEquals(setOf("action"), result.merged)
        assertTrue("comedy" in result.toRemoveRemotely)
        assertTrue(result.toRemoveLocally.isEmpty()) // already removed locally
    }

    @Test
    fun `tombstone within 5-minute window beats remote re-add even if older than last-sync`() {
        // Tombstone stamped just before last-sync, but well within
        // the 5-min prefer-local window. Spec #178: prefer local.
        val tombStamp = now - (4 * 60 * 1_000L) // 4 min ago
        val lastSync = now - (2 * 60 * 1_000L) // 2 min ago
        val result = TagSyncMerge.merge(
            localTags = emptySet(),
            localTombstones = mapOf("drama" to tombStamp),
            remoteTags = setOf("drama"),
            lastSyncedAt = lastSync,
            now = now,
        )

        // Drama tombstone is older than last-sync but within the
        // 5-min prefer-local window → still wins.
        assertEquals(emptySet<String>(), result.merged)
        assertEquals(setOf("drama"), result.toRemoveRemotely)
    }

    @Test
    fun `stale tombstone outside 5-min window is forgotten and remote re-add wins`() {
        // Tombstone is 10 min old, also older than last-sync.
        // Outside the window → remote re-add wins.
        val tombStamp = now - (10 * 60 * 1_000L)
        val lastSync = now - (8 * 60 * 1_000L)
        val result = TagSyncMerge.merge(
            localTags = emptySet(),
            localTombstones = mapOf("mystery" to tombStamp),
            remoteTags = setOf("mystery"),
            lastSyncedAt = lastSync,
            now = now,
        )

        // The tombstone is outside both the window and the
        // last-sync gate, so remote wins.
        assertEquals(setOf("mystery"), result.merged)
        // And the stale tombstone is queued for forget.
        assertTrue("mystery" in result.tombstonesToForget)
    }

    @Test
    fun `empty inputs produce empty merge`() {
        val result = TagSyncMerge.merge(
            localTags = emptySet(),
            localTombstones = emptyMap(),
            remoteTags = emptySet(),
            lastSyncedAt = 0L,
            now = now,
        )
        assertEquals(emptySet<String>(), result.merged)
        assertEquals(emptySet<String>(), result.toAddLocally)
        assertEquals(emptySet<String>(), result.toAddRemotely)
    }

    @Test
    fun `identical sets produce no-op diff`() {
        val tags = setOf("action", "litrpg", "high_fantasy")
        val result = TagSyncMerge.merge(
            localTags = tags,
            localTombstones = emptyMap(),
            remoteTags = tags,
            lastSyncedAt = now - 1000L,
            now = now,
        )
        assertEquals(tags, result.merged)
        assertEquals(emptySet<String>(), result.toAddLocally)
        assertEquals(emptySet<String>(), result.toRemoveLocally)
        assertEquals(emptySet<String>(), result.toAddRemotely)
        assertEquals(emptySet<String>(), result.toRemoveRemotely)
    }
}
