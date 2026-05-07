package `in`.jphe.storyvox.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsVolumeRampTest {

    @Test fun `default current is full volume`() {
        assertEquals(1.0f, TtsVolumeRamp().current, 0f)
    }

    @Test fun `set within range updates current verbatim`() {
        val ramp = TtsVolumeRamp()
        ramp.set(0.5f)
        assertEquals(0.5f, ramp.current, 0f)
        ramp.set(0.0f)
        assertEquals(0.0f, ramp.current, 0f)
        ramp.set(1.0f)
        assertEquals(1.0f, ramp.current, 0f)
    }

    @Test fun `negative input is clamped to zero`() {
        val ramp = TtsVolumeRamp()
        ramp.set(-0.5f)
        assertEquals(0f, ramp.current, 0f)
        ramp.set(-1000f)
        assertEquals(0f, ramp.current, 0f)
    }

    @Test fun `above-one input is clamped to one`() {
        val ramp = TtsVolumeRamp()
        ramp.set(1.5f)
        assertEquals(1f, ramp.current, 0f)
        ramp.set(99f)
        assertEquals(1f, ramp.current, 0f)
    }

    @Test fun `successive sets overwrite, last write wins`() {
        val ramp = TtsVolumeRamp()
        ramp.set(0.2f)
        ramp.set(0.8f)
        ramp.set(0.4f)
        assertEquals(0.4f, ramp.current, 0f)
    }
}
