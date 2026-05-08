package `in`.jphe.storyvox.playback.cache

import `in`.jphe.storyvox.playback.tts.CHUNKER_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PcmCacheKeyTest {

    private val baseline = PcmCacheKey(
        chapterId = "skypride/ch1",
        voiceId = "cori",
        speedHundredths = 100,
        pitchHundredths = 100,
        chunkerVersion = CHUNKER_VERSION,
    )

    @Test
    fun `fileBaseName is 64-char hex`() {
        val s = baseline.fileBaseName()
        assertEquals(64, s.length)
        assert(s.all { it in '0'..'9' || it in 'a'..'f' }) {
            "expected lowercase hex, got '$s'"
        }
    }

    @Test
    fun `fileBaseName is stable for the same key`() {
        assertEquals(baseline.fileBaseName(), baseline.copy().fileBaseName())
    }

    @Test
    fun `every field contributes to the hash`() {
        val differentEachField = listOf(
            baseline.copy(chapterId = "skypride/ch2"),
            baseline.copy(voiceId = "amy"),
            baseline.copy(speedHundredths = 125),
            baseline.copy(pitchHundredths = 105),
            baseline.copy(chunkerVersion = baseline.chunkerVersion + 1),
        )
        for (k in differentEachField) {
            assertNotEquals(
                "key with shifted field $k must change the hash",
                baseline.fileBaseName(),
                k.fileBaseName(),
            )
        }
    }

    @Test
    fun `quantize rounds to nearest hundredth`() {
        assertEquals(100, PcmCacheKey.quantize(1.0f))
        assertEquals(125, PcmCacheKey.quantize(1.25f))
        assertEquals(102, PcmCacheKey.quantize(1.024f))   // rounds up
        assertEquals(102, PcmCacheKey.quantize(1.018f))   // rounds up
        assertEquals(101, PcmCacheKey.quantize(1.014f))   // rounds down
        assertEquals(50,  PcmCacheKey.quantize(0.5f))
    }
}
