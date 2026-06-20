package dev.mrwick.redline.nav

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Average-hash (aHash) for icon-sized bitmaps:
 *   1. Scale to 8x8
 *   2. Convert to grayscale (luminance)
 *   3. Compute mean
 *   4. Bit i = 1 if pixel >= mean, else 0
 *
 * Robust to color variations and small rendering differences. Best for the
 * silhouette-driven turn icons Google Maps uses.
 */
object BitmapHasher {

    /**
     * Compute a 64-bit average-hash of [bitmap]. The output is deterministic
     * for a given bitmap regardless of original dimensions or color profile.
     */
    fun aHash64(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 8, 8, false)
        val pixels = IntArray(64)
        scaled.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        val grays = IntArray(64) { i ->
            val px = pixels[i]
            // Rec. 601 luma — matches Android's BitmapDrawable.getColorFilter expectations.
            (Color.red(px) * 299 + Color.green(px) * 587 + Color.blue(px) * 114) / 1000
        }
        val mean = grays.sum() / 64
        var hash = 0L
        for (i in 0 until 64) {
            if (grays[i] >= mean) hash = hash or (1L shl i)
        }
        // createScaledBitmap returns the original Bitmap when src dims already equal
        // dst dims; only recycle when we actually allocated a new one.
        if (scaled !== bitmap) scaled.recycle()
        return hash
    }

    /** Hamming distance between two 64-bit hashes (0 = identical, 64 = opposite). */
    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
