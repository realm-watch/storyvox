package `in`.jphe.storyvox.feature.debug

import `in`.jphe.storyvox.feature.api.DebugEvent
import `in`.jphe.storyvox.feature.api.DebugEventKind
import `in`.jphe.storyvox.feature.api.DebugSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugFormattersTest {

    @Test fun `bytes formats nulls and zero as em dash`() {
        assertEquals("—", DebugFormatters.bytes(null))
        assertEquals("—", DebugFormatters.bytes(0L))
        assertEquals("—", DebugFormatters.bytes(-5L))
    }

    @Test fun `bytes scales through KB MB GB`() {
        assertEquals("500 B", DebugFormatters.bytes(500L))
        assertEquals("1.5 KB", DebugFormatters.bytes(1_536L))
        // 2 MB == 2 × 1024 × 1024 = 2097152
        assertEquals("2.00 MB", DebugFormatters.bytes(2_097_152L))
        // 3 GB
        assertEquals("3.00 GB", DebugFormatters.bytes(3L * 1024 * 1024 * 1024))
    }

    @Test fun `duration formats sub-second ms`() {
        assertEquals("240 ms", DebugFormatters.duration(240L))
    }

    @Test fun `duration formats seconds`() {
        assertEquals("1.4 s", DebugFormatters.duration(1_400L))
    }

    @Test fun `duration nullable returns em dash`() {
        assertEquals("—", DebugFormatters.duration(null))
    }

    @Test fun `ago renders relative buckets`() {
        val now = 10_000_000L
        assertEquals("now", DebugFormatters.ago(now - 500, now))
        assertEquals("3 s ago", DebugFormatters.ago(now - 3_500, now))
        assertEquals("4 min ago", DebugFormatters.ago(now - 240_000, now))
    }

    @Test fun `id truncates with leading ellipsis`() {
        val id = "github:jphein/storyvox:src/chapter-01.md"
        val s = DebugFormatters.id(id, width = 10)
        assertEquals(10, s.length)
        assertTrue("expected leading ellipsis", s.startsWith("…"))
    }

    @Test fun `id pads short input to width`() {
        val s = DebugFormatters.id("abc", width = 8)
        assertEquals(8, s.length)
        assertEquals("abc", s.trimEnd())
    }

    @Test fun `renderSnapshotAsText includes all sections`() {
        val s = DebugSnapshot.EMPTY.copy(
            build = DebugSnapshot.EMPTY.build.copy(
                versionName = "0.4.97",
                hash = "abcd123",
                sigilName = "Spectral Lantern · abcd123",
            ),
            events = listOf(
                DebugEvent(1_000L, DebugEventKind.Chapter, "Loaded: chapter 1"),
            ),
            snapshotAtMs = 1_000L,
        )
        val text = renderSnapshotAsText(s)
        assertTrue("must mention version", text.contains("0.4.97"))
        assertTrue("must mention sigil", text.contains("Spectral Lantern"))
        assertTrue("must mention event", text.contains("Loaded: chapter 1"))
        assertTrue("must include build header", text.contains("[build]"))
        assertTrue("must include events header", text.contains("[events]"))
    }

    @Test fun `renderSnapshotAsText skips azure section when not configured`() {
        val text = renderSnapshotAsText(DebugSnapshot.EMPTY)
        assertTrue("azure header should be absent", !text.contains("[azure]"))
    }
}
