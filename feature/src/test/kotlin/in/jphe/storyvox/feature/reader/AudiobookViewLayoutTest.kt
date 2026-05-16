package `in`.jphe.storyvox.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #526 — play/pause button layout shift regression guard.
 *
 * Pre-#526 the play button's host Box wrapped to whichever child was
 * visible at the moment (96 dp spinner when warming/buffering, 72 dp
 * FilledIconButton when idle). Every Buffering ⇄ Playing state flip
 * shrank/grew the host by 25 dp and re-flowed the SpaceEvenly transport
 * row — visually jarring.
 *
 * The composable now pins the host Box to [PLAY_BUTTON_HOST_SIZE_DP].
 * This test asserts the constant equals the spinner's outer bound so a
 * future "spinner gets bigger" change must also update the host size,
 * or the regression returns.
 */
class AudiobookViewLayoutTest {

    /**
     * #526 — the host must be exactly the size of the largest child
     * (the MagicSpinner at 96 dp inside the transport row). 96 dp is
     * the largest visible footprint; the 72 dp FilledIconButton sits
     * inside it centered. Smaller host = layout shift returns. Larger
     * host = wasted vertical space.
     */
    @Test
    fun `play button host is sized to fit the spinner without resizing`() {
        // The MagicSpinner is constructed with `.size(96.dp)` inside
        // the transport row — the AnimatedVisibility wrapper doesn't
        // change that. The host must be at least that to keep the
        // spinner from shrinking when it enters; equal-to keeps the
        // row tight.
        assertEquals(
            "Play button host should equal the spinner's outer bound. " +
                "If the spinner is now larger, update both this constant " +
                "and the visual; #526's regression is silent until QA " +
                "spots the bouncing transport row.",
            96,
            PLAY_BUTTON_HOST_SIZE_DP,
        )
    }

    /**
     * Sanity: the host must be larger than the inner FilledIconButton
     * (72 dp). If the host shrinks below the button, the button
     * itself gets clipped — a different regression but worth pinning.
     */
    @Test
    fun `play button host is larger than the inner FilledIconButton`() {
        val innerFilledIconButtonSizeDp = 72
        assertTrue(
            "Host must be larger than the 72 dp inner button so the " +
                "button never gets clipped by the host bounds.",
            PLAY_BUTTON_HOST_SIZE_DP > innerFilledIconButtonSizeDp,
        )
    }

    /**
     * #525 — the cover-tap feedback duration must be long enough to
     * register but short enough not to obscure the cover during normal
     * listening. 400 ms is the Spotify/Apple Music benchmark; the
     * test pins it so a future "make it longer" tweak surfaces UX intent.
     */
    @Test
    fun `cover tap feedback duration matches the Spotify benchmark`() {
        assertEquals(
            "Cover-tap feedback must be brief — 400 ms is long enough " +
                "for a single read pass + short enough to not obscure " +
                "the cover during normal listening.",
            400L,
            COVER_FEEDBACK_DURATION_MS,
        )
    }
}
