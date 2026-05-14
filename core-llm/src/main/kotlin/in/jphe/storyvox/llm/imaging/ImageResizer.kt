package `in`.jphe.storyvox.llm.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Issue #215 — downscale + JPEG-encode an inbound image so the
 * provider payload stays well under per-request size caps. Anthropic
 * accepts up to ~5 MB images, OpenAI up to 20 MB, but oversized
 * images frequently 400 on Claude (related to anthropic-claude#59095
 * — TLS / proxy buffering on the upload path is more reliable below
 * ~1 MB). We target ~500 KB after resize as the safe band across
 * every multi-modal provider we care about.
 *
 * Pipeline:
 *  1. [computeTargetSize] (pure) — figures out the post-scale W×H so
 *     the long edge doesn't exceed [maxLongEdge].
 *  2. Decode the bytes via `BitmapFactory.decodeByteArray` with the
 *     matching `inSampleSize` to avoid loading a 12 MP photo into
 *     a Bitmap just to shrink it.
 *  3. `Bitmap.createScaledBitmap` to land on the exact target size.
 *  4. `Bitmap.compress(JPEG, [jpegQuality])` to bytes.
 *
 * The pure-Kotlin entry points ([computeTargetSize], [Base64.encode])
 * are unit-testable on the JVM without Android stubs. The full
 * [encodeForUpload] entry point uses `BitmapFactory`, which the
 * Android JUnit runner treats as a stub — wrap it under Robolectric
 * for end-to-end coverage, or call the pure helpers in tests.
 */
object ImageResizer {

    /** Default max long edge for outbound images. Mirrors the
     *  screenshot-compress hook's 1280px cap — the smallest size
     *  where text and faces are still legible on the model's side. */
    const val DEFAULT_MAX_LONG_EDGE: Int = 1280

    /** Default JPEG quality. 85 is the sweet spot — visually
     *  indistinguishable from 90+ but ~30 % smaller payload. */
    const val DEFAULT_JPEG_QUALITY: Int = 85

    /**
     * Pure math: given source dimensions and a max-long-edge cap,
     * return the post-scale dimensions. Pass-through when the source
     * already fits inside the cap (no upscaling — we never grow an
     * image).
     *
     * Aspect ratio is preserved to within a single pixel; the result
     * floors the short edge so the bitmap allocator doesn't ever
     * over-allocate. Caller is responsible for rejecting zero / negative
     * inputs — we coerce to `1` for safety and let the caller surface
     * the error if it cares.
     */
    fun computeTargetSize(
        srcWidth: Int,
        srcHeight: Int,
        maxLongEdge: Int = DEFAULT_MAX_LONG_EDGE,
    ): IntArray {
        val w = srcWidth.coerceAtLeast(1)
        val h = srcHeight.coerceAtLeast(1)
        val longEdge = maxOf(w, h)
        if (longEdge <= maxLongEdge) return intArrayOf(w, h)
        val scale = maxLongEdge.toDouble() / longEdge.toDouble()
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        return intArrayOf(newW, newH)
    }

    /**
     * Compute the smallest `inSampleSize` that, when passed to
     * [BitmapFactory.Options], keeps the decoded bitmap ≥ the target
     * dimensions. We over-decode by up to 2x then `createScaledBitmap`
     * for an exact fit — that's the standard Android decode pattern.
     *
     * The math: `inSampleSize` is constrained to be a power of two by
     * the decoder, with values >1 quartering the decoded buffer per
     * step. We pick the largest pow-of-2 ≤ (srcLong / dstLong).
     */
    fun computeInSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
    ): Int {
        val srcLong = maxOf(srcWidth, srcHeight)
        val dstLong = maxOf(dstWidth, dstHeight)
        if (srcLong <= dstLong) return 1
        var sample = 1
        // Stop one step before the bitmap shrinks past the target so
        // we still have pixels to scale from.
        while ((srcLong / (sample * 2)) >= dstLong) {
            sample *= 2
        }
        return sample
    }

    /**
     * Full pipeline. Decodes [srcBytes], downscales to fit
     * [maxLongEdge], JPEG-encodes at [jpegQuality], base64-encodes,
     * and returns the result.
     *
     * Throws [IllegalArgumentException] when [srcBytes] doesn't
     * decode (corrupt / unsupported format / not actually an image).
     */
    fun encodeForUpload(
        srcBytes: ByteArray,
        maxLongEdge: Int = DEFAULT_MAX_LONG_EDGE,
        jpegQuality: Int = DEFAULT_JPEG_QUALITY,
    ): Encoded {
        // Pass 1 — bounds only, no pixel allocation.
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(srcBytes, 0, srcBytes.size, boundsOpts)
        val srcW = boundsOpts.outWidth
        val srcH = boundsOpts.outHeight
        require(srcW > 0 && srcH > 0) {
            "Image bytes did not decode to a valid bitmap"
        }
        val target = computeTargetSize(srcW, srcH, maxLongEdge)
        val sample = computeInSampleSize(srcW, srcH, target[0], target[1])

        // Pass 2 — actually decode (using the sample-size hint to
        // avoid loading a 4032 × 3024 photo into a heap-busting bitmap
        // when we're shrinking to 1280 anyway).
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(
            srcBytes, 0, srcBytes.size, decodeOpts,
        ) ?: throw IllegalArgumentException(
            "Image bytes did not decode to a valid bitmap",
        )
        val scaled = if (decoded.width == target[0] && decoded.height == target[1]) {
            decoded
        } else {
            val out = Bitmap.createScaledBitmap(decoded, target[0], target[1], true)
            if (out != decoded) decoded.recycle()
            out
        }

        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
        scaled.recycle()
        val jpegBytes = baos.toByteArray()
        val b64 = Base64.getEncoder().encodeToString(jpegBytes)
        return Encoded(
            base64 = b64,
            mimeType = "image/jpeg",
            widthPx = target[0],
            heightPx = target[1],
            byteCount = jpegBytes.size,
        )
    }

    /** Result of a successful [encodeForUpload]. */
    data class Encoded(
        val base64: String,
        val mimeType: String,
        val widthPx: Int,
        val heightPx: Int,
        val byteCount: Int,
    )
}
