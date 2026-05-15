package `in`.jphe.storyvox.widget

import `in`.jphe.storyvox.playback.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #159 — projection from PlaybackState to the widget's narrow
 * visible-slice. These tests exist because the widget's
 * distinctUntilChanged depends on this projection collapsing
 * irrelevant churn (sentence-range ticking 10x/sec, buffering toggle,
 * error transitions). If the projection accidentally widens, the
 * widget will broadcast on every frame and burn host CPU.
 */
class NowPlayingSnapshotTest {

    @Test fun `idle when no chapter loaded`() {
        val state = PlaybackState() // defaults — no fictionId/chapterId
        val snap = NowPlayingSnapshot.from(state)
        assertTrue(snap.isIdle)
        assertFalse(snap.isPlaying)
        assertNull(snap.bookTitle)
        assertNull(snap.chapterTitle)
    }

    @Test fun `idle when fiction id is blank`() {
        val state = PlaybackState(
            currentFictionId = "",
            currentChapterId = "ch-1",
            bookTitle = "x",
        )
        assertTrue(NowPlayingSnapshot.from(state).isIdle)
    }

    @Test fun `idle when chapter id is blank`() {
        val state = PlaybackState(
            currentFictionId = "fid-1",
            currentChapterId = "",
            bookTitle = "x",
        )
        assertTrue(NowPlayingSnapshot.from(state).isIdle)
    }

    @Test fun `active snapshot carries book and chapter titles`() {
        val state = PlaybackState(
            currentFictionId = "fid-1",
            currentChapterId = "ch-1",
            bookTitle = "Worth the Candle",
            chapterTitle = "Chapter 1: Tabletop",
            isPlaying = true,
        )
        val snap = NowPlayingSnapshot.from(state)
        assertFalse(snap.isIdle)
        assertTrue(snap.isPlaying)
        assertEquals("Worth the Candle", snap.bookTitle)
        assertEquals("Chapter 1: Tabletop", snap.chapterTitle)
        assertEquals("fid-1", snap.fictionId)
        assertEquals("ch-1", snap.chapterId)
    }

    @Test fun `blank string titles project to null so the widget falls back to idle copy`() {
        val state = PlaybackState(
            currentFictionId = "fid-1",
            currentChapterId = "ch-1",
            bookTitle = "   ",
            chapterTitle = "",
            isPlaying = true,
        )
        val snap = NowPlayingSnapshot.from(state)
        // Still "playing" (we have ids) — but the renderer will use
        // the idle strings because both titles came back null.
        assertFalse(snap.isIdle)
        assertNull(snap.bookTitle)
        assertNull(snap.chapterTitle)
    }

    @Test fun `sleep timer remaining flows through unchanged`() {
        val state = PlaybackState(
            currentFictionId = "fid-1",
            currentChapterId = "ch-1",
            sleepTimerRemainingMs = 15 * 60 * 1000L,
        )
        assertEquals(15 * 60 * 1000L, NowPlayingSnapshot.from(state).sleepTimerRemainingMs)
    }

    @Test fun `idle constant has no ids and is not playing`() {
        val snap = NowPlayingSnapshot.Idle
        assertTrue(snap.isIdle)
        assertFalse(snap.isPlaying)
        assertNull(snap.fictionId)
        assertNull(snap.chapterId)
        assertNull(snap.coverUri)
    }
}
