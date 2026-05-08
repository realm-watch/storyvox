package `in`.jphe.storyvox.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStateTest {

    @Test fun `scrubProgress is zero when duration is zero`() {
        assertEquals(0f, PlaybackState().scrubProgress(), 0f)
    }

    @Test fun `scrubProgress is zero when duration is negative`() {
        val s = PlaybackState(durationEstimateMs = -100L, charOffset = 50)
        assertEquals(0f, s.scrubProgress(), 0f)
    }

    @Test fun `scrubProgress is zero at start of chapter`() {
        val s = PlaybackState(durationEstimateMs = 10_000L, charOffset = 0)
        assertEquals(0f, s.scrubProgress(), 0f)
    }

    @Test fun `scrubProgress at half-way charOffset is roughly half`() {
        // 1000ms at speed=1.0 => 1.0 * 12.5 chars/s = 12.5 chars total.
        // (SPEED_BASELINE_WPM 150 * 5 / 60 = 12.5 chars/sec.)
        val total = (1_000f / 1000f) * SPEED_BASELINE_CHARS_PER_SECOND * 1.0f
        val s = PlaybackState(
            durationEstimateMs = 1_000L,
            charOffset = (total / 2f).toInt(),
            speed = 1.0f,
        )
        assertEquals(0.5f, s.scrubProgress(), 0.05f)
    }

    @Test fun `scrubProgress is clamped to 1 when charOffset overshoots`() {
        val s = PlaybackState(
            durationEstimateMs = 1_000L,
            charOffset = 10_000,
            speed = 1.0f,
        )
        assertEquals(1f, s.scrubProgress(), 0f)
    }

    @Test fun `higher speed expands expected total chars and reduces progress`() {
        val base = PlaybackState(durationEstimateMs = 10_000L, charOffset = 100, speed = 1.0f)
        val fast = base.copy(speed = 2.0f)
        // At 2x speed, the same charOffset over the same duration represents
        // half as much progress (twice as much total content fits).
        assertEquals(base.scrubProgress() / 2f, fast.scrubProgress(), 1e-4f)
    }

    @Test fun `speed baseline constants are stable contract`() {
        assertEquals(150f, SPEED_BASELINE_WPM, 0f)
        assertEquals(12.5f, SPEED_BASELINE_CHARS_PER_SECOND, 0f)
    }

    @Test fun `isBuffering defaults false and serializes round-trip`() {
        val s = PlaybackState(isBuffering = true, isPlaying = true, charOffset = 42)
        val json = kotlinx.serialization.json.Json.encodeToString(PlaybackState.serializer(), s)
        val round = kotlinx.serialization.json.Json.decodeFromString(PlaybackState.serializer(), json)
        assertEquals(true, round.isBuffering)
        assertEquals(true, round.isPlaying)
        assertEquals(42, round.charOffset)

        val default = PlaybackState()
        assertEquals(false, default.isBuffering)
    }
}
