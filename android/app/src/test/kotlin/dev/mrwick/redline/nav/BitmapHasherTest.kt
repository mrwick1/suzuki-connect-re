package dev.mrwick.redline.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [BitmapHasher]. Only the bit-twiddling helper
 * [BitmapHasher.hammingDistance] is exercised here — [BitmapHasher.aHash64]
 * touches `android.graphics.Bitmap` which is not available on the host JVM.
 * Bitmap-path coverage lives in instrumented tests.
 */
class BitmapHasherTest {

    @Test
    fun `hamming distance basics`() {
        assertEquals(0, BitmapHasher.hammingDistance(0L, 0L))
        assertEquals(64, BitmapHasher.hammingDistance(0L, -1L))
        assertEquals(1, BitmapHasher.hammingDistance(0L, 1L))
    }

    @Test
    fun `hamming distance is symmetric`() {
        val a = 0x0123_4567_89AB_CDEFL
        // Use ULong literal + toLong() since the unsigned value exceeds Long.MAX_VALUE.
        val b = 0xFEDC_BA98_7654_3210uL.toLong()
        assertEquals(BitmapHasher.hammingDistance(a, b), BitmapHasher.hammingDistance(b, a))
    }

    @Test
    fun `hamming distance counts only differing bits`() {
        // 0b1010 vs 0b0101 differ in 4 positions.
        assertEquals(4, BitmapHasher.hammingDistance(0b1010L, 0b0101L))
    }
}
