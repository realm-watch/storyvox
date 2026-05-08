package `in`.jphe.storyvox.source.mempalace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Codec round-trip + edge-case tests for [MemPalaceIds]. The fictionId
 * and chapterId formats are persisted in Room (via FictionRepository's
 * cache); breaking the parse later would orphan every saved palace
 * fiction, so these tests exist as a regression net.
 */
class MemPalaceIdsTest {

    @Test fun `parses well-formed fiction id`() {
        val parsed = MemPalaceIds.parseFictionId("mempalace:projects/realmwatch")
        assertEquals("projects" to "realmwatch", parsed)
    }

    @Test fun `parses fiction id with dot-separated wing name`() {
        val parsed = MemPalaceIds.parseFictionId("mempalace:os.realm.watch/architecture")
        assertEquals("os.realm.watch" to "architecture", parsed)
    }

    @Test fun `rejects fiction id without prefix`() {
        assertNull(MemPalaceIds.parseFictionId("projects/realmwatch"))
    }

    @Test fun `rejects fiction id without slash`() {
        assertNull(MemPalaceIds.parseFictionId("mempalace:projects"))
    }

    @Test fun `rejects fiction id with empty wing or room`() {
        assertNull(MemPalaceIds.parseFictionId("mempalace:/realmwatch"))
        assertNull(MemPalaceIds.parseFictionId("mempalace:projects/"))
    }

    @Test fun `rejects fiction id that is actually a chapter id`() {
        // Extra `/` between the room and drawer half — should refuse to parse
        // as a fiction id so callers don't accidentally treat a chapter as a
        // fiction.
        assertNull(MemPalaceIds.parseFictionId("mempalace:projects/realmwatch/extra"))
    }

    @Test fun `parses well-formed chapter id`() {
        val parsed = MemPalaceIds.parseChapterId(
            "mempalace:projects/realmwatch:drawer_projects_realmwatch_a1b2c3d4",
        )
        assertEquals(
            Triple("projects", "realmwatch", "drawer_projects_realmwatch_a1b2c3d4"),
            parsed,
        )
    }

    @Test fun `parses chapter id with hex-only drawer id`() {
        val parsed = MemPalaceIds.parseChapterId(
            "mempalace:bestiary/technical:drawer_bestiary_technical_e5f6a7b8",
        )
        assertEquals(
            Triple("bestiary", "technical", "drawer_bestiary_technical_e5f6a7b8"),
            parsed,
        )
    }

    @Test fun `rejects chapter id without prefix`() {
        assertNull(MemPalaceIds.parseChapterId("projects/realmwatch:drawer_x"))
    }

    @Test fun `rejects chapter id without colon between room and drawer`() {
        assertNull(MemPalaceIds.parseChapterId("mempalace:projects/realmwatch"))
    }

    @Test fun `rejects chapter id with empty drawer id`() {
        assertNull(MemPalaceIds.parseChapterId("mempalace:projects/realmwatch:"))
    }

    @Test fun `fictionId encoder matches parser inverse`() {
        val id = MemPalaceIds.fictionId("projects", "realmwatch")
        val parsed = MemPalaceIds.parseFictionId(id)
        assertEquals("projects" to "realmwatch", parsed)
    }

    @Test fun `chapterId encoder matches parser inverse`() {
        val id = MemPalaceIds.chapterId(
            "projects",
            "realmwatch",
            "drawer_projects_realmwatch_a1b2c3d4",
        )
        val parsed = MemPalaceIds.parseChapterId(id)
        assertEquals(
            Triple("projects", "realmwatch", "drawer_projects_realmwatch_a1b2c3d4"),
            parsed,
        )
    }

    @Test fun `prettify replaces underscores with spaces`() {
        assertEquals("Claude Code Python", MemPalaceIds.prettify("claude_code_python"))
    }

    @Test fun `prettify preserves dot-domain wing names`() {
        assertEquals("Os.Realm.Watch", MemPalaceIds.prettify("os.realm.watch"))
    }

    @Test fun `prettify single-word room`() {
        assertEquals("Realmwatch", MemPalaceIds.prettify("realmwatch"))
    }

    @Test fun `prettify hyphen-separated`() {
        assertEquals("Mirror Realm Watch", MemPalaceIds.prettify("mirror-realm-watch"))
    }
}
