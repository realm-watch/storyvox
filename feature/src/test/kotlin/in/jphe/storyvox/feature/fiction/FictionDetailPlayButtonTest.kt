package `in`.jphe.storyvox.feature.fiction

import `in`.jphe.storyvox.feature.api.UiChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #604 (v1.0 blocker) — pre-fix the FictionDetail BottomBar
 * had no Play button. Cold-launch users with a book in hand had no
 * visible primary CTA on the screen; TalkBack users couldn't reach
 * the chapter list without first knowing it existed.
 *
 * The fix added a Play / Resume button to the BottomBar driven by
 * two pure helpers ([pickChapterToPlay], [playButtonLabel]) that
 * the screen composable calls. Tests pin the helpers' behavior so
 * the discoverability win can't regress without somebody noticing
 * here first.
 */
class FictionDetailPlayButtonTest {

    private fun ch(id: String, number: Int, finished: Boolean) = UiChapter(
        id = id,
        number = number,
        title = "Chapter $number",
        publishedRelative = "1d",
        durationLabel = "12 min",
        isDownloaded = false,
        isFinished = finished,
    )

    @Test
    fun `pickChapterToPlay returns null when chapters list is empty`() {
        // No chapters → the BottomBar should hide the Play slot
        // entirely (onPlay = null disables the BrassButton).
        assertNull(pickChapterToPlay(emptyList()))
    }

    @Test
    fun `pickChapterToPlay returns first chapter on a fresh fiction`() {
        val chapters = listOf(
            ch("c1", 1, finished = false),
            ch("c2", 2, finished = false),
            ch("c3", 3, finished = false),
        )
        val picked = pickChapterToPlay(chapters)
        assertEquals("c1", picked?.id)
    }

    @Test
    fun `pickChapterToPlay resumes at the first non-finished chapter`() {
        // The user has finished c1, c2, c3. Resume should jump them
        // into c4 — that's the "Resume" path.
        val chapters = listOf(
            ch("c1", 1, finished = true),
            ch("c2", 2, finished = true),
            ch("c3", 3, finished = true),
            ch("c4", 4, finished = false),
            ch("c5", 5, finished = false),
        )
        val picked = pickChapterToPlay(chapters)
        assertEquals("c4", picked?.id)
    }

    @Test
    fun `pickChapterToPlay falls back to first chapter when fiction is fully finished`() {
        // Every chapter finished → the fiction is complete. Tap Play
        // and the user expects "play the whole thing again" starting
        // at chapter 1 (matches Spotify "replay" for a finished
        // playlist).
        val chapters = listOf(
            ch("c1", 1, finished = true),
            ch("c2", 2, finished = true),
        )
        val picked = pickChapterToPlay(chapters)
        assertEquals("c1", picked?.id)
    }

    @Test
    fun `playButtonLabel reads Play on a fresh fiction`() {
        val chapters = listOf(ch("c1", 1, finished = false))
        assertEquals("Play", playButtonLabel(chapters, pickChapterToPlay(chapters)))
    }

    @Test
    fun `playButtonLabel reads Resume when the user has finished earlier chapters`() {
        val chapters = listOf(
            ch("c1", 1, finished = true),
            ch("c2", 2, finished = false),
        )
        assertEquals("Resume", playButtonLabel(chapters, pickChapterToPlay(chapters)))
    }

    @Test
    fun `playButtonLabel reads Play on a fully-finished fiction (replay path)`() {
        // Every chapter finished — pickChapterToPlay returns c1,
        // which equals the first chapter, so label is Play (not
        // Resume). The user is restarting from the top, not
        // resuming partway.
        val chapters = listOf(
            ch("c1", 1, finished = true),
            ch("c2", 2, finished = true),
        )
        assertEquals("Play", playButtonLabel(chapters, pickChapterToPlay(chapters)))
    }

    @Test
    fun `playButtonLabel reads Play when chapter list is empty`() {
        // Defensive — empty list means picked is null, label
        // resolves to Play (the button is hidden anyway by the
        // null onPlay slot, but the label fallback should be sane).
        assertEquals("Play", playButtonLabel(emptyList(), null))
    }
}
