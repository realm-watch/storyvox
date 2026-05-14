package `in`.jphe.storyvox.llm.imaging

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #215 — pure-math unit tests for [ImageResizer]. The full
 * [ImageResizer.encodeForUpload] path uses `BitmapFactory` (Android
 * framework) and would need Robolectric, but the math entry points
 * are platform-independent.
 */
class ImageResizerTest {

    @Test
    fun `1080x2640 portrait downscales to fit 1280 long edge`() {
        // The typical Android screenshot — tall, ~21:9. Long edge is
        // height 2640 → scale to height 1280, width pro rata.
        val target = ImageResizer.computeTargetSize(
            srcWidth = 1080,
            srcHeight = 2640,
            maxLongEdge = 1280,
        )
        // 1080 * (1280 / 2640) = 523.6 → 523
        assertEquals(523, target[0])
        assertEquals(1280, target[1])
    }

    @Test
    fun `4032x3024 landscape downscales to fit 1280 long edge`() {
        // The typical phone-camera landscape — 4:3.
        val target = ImageResizer.computeTargetSize(
            srcWidth = 4032,
            srcHeight = 3024,
            maxLongEdge = 1280,
        )
        assertEquals(1280, target[0])
        // 3024 * (1280 / 4032) = 960.0
        assertEquals(960, target[1])
    }

    @Test
    fun `under-cap image is left at native size`() {
        val target = ImageResizer.computeTargetSize(
            srcWidth = 800,
            srcHeight = 600,
            maxLongEdge = 1280,
        )
        assertEquals(800, target[0])
        assertEquals(600, target[1])
    }

    @Test
    fun `compute in-sample-size picks largest power-of-two that keeps target`() {
        // 4032x3024 → target 1280x960. srcLong/dstLong = 3.15 → 2x.
        assertEquals(
            2,
            ImageResizer.computeInSampleSize(4032, 3024, 1280, 960),
        )
        // 8000x6000 → target 1280x960. srcLong/dstLong = 6.25 → 4x.
        assertEquals(
            4,
            ImageResizer.computeInSampleSize(8000, 6000, 1280, 960),
        )
        // Source ≤ target → sampleSize = 1 (no downsample).
        assertEquals(
            1,
            ImageResizer.computeInSampleSize(800, 600, 1280, 960),
        )
    }

    @Test
    fun `base64 round-trips an arbitrary byte sequence`() {
        // A handful of bytes that exercise every base64 sextet shape
        // (including padding) and surface any bytewise corruption.
        val source = byteArrayOf(
            0x00, 0x01, 0x02.toByte(), 0x7F, 0x80.toByte(), 0xFF.toByte(),
            'h'.code.toByte(), 'i'.code.toByte(),
            0x00, 0x00, 0x00,
        )
        val encoded = Base64.getEncoder().encodeToString(source)
        val decoded = Base64.getDecoder().decode(encoded)
        assertArrayEquals(source, decoded)
        // And the encoded form should be the standard padded shape
        // (the multiple of 4 invariant) — Anthropic + OpenAI both
        // expect padded base64 on the wire.
        assertTrue(
            "encoded base64 should be padded to a multiple of 4 chars",
            encoded.length % 4 == 0,
        )
    }
}
