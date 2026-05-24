package dev.mrwick.gixxerbridge.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BytesTest {

    // Captured a537 telemetry frame: a5 37 30 30 30 30 31 36 37 32 39 30 34 39 31 31
    //                                30 30 30 39 38 34 39 4e 34 4d ff ff 3a 7f
    // Bytes 2..25 are ASCII digits / letters (the VIN-ish body); 26..27 are 0xFF padding.
    private val sampleFrame: ByteArray = byteArrayOf(
        0xa5.toByte(), 0x37.toByte(),
        0x30, 0x30, 0x30, 0x30, 0x31, 0x36, 0x37, 0x32, 0x39, 0x30,
        0x34, 0x39, 0x31, 0x31, 0x30, 0x30, 0x30, 0x39, 0x38, 0x34,
        0x39, 0x4e, 0x34, 0x4d,
        0xff.toByte(), 0xff.toByte(),
        0x3a, 0x7f,
    )

    @Test fun sliceInclusive_matches_python_range() {
        val src = byteArrayOf(0, 1, 2, 3, 4, 5)
        // Python `src[1:4]` => indices 1,2,3 == sliceInclusive(1, 3).
        assertArrayEquals(byteArrayOf(1, 2, 3), src.sliceInclusive(1, 3))
        // Single-element slice.
        assertArrayEquals(byteArrayOf(2), src.sliceInclusive(2, 2))
        // Full range.
        assertArrayEquals(src, src.sliceInclusive(0, 5))
    }

    @Test fun toPrintableAscii_renders_printable_and_dots_binary() {
        val rendered = sampleFrame.toPrintableAscii()
        assertEquals(sampleFrame.size, rendered.length)

        // Header 0xA5 -> '.', type 0x37 -> '7', ASCII body, 0xFF pad -> '.', checksum 0x3A -> ':', terminator 0x7F -> '.'.
        assertEquals('.', rendered[0])             // 0xA5
        assertEquals('7', rendered[1])             // 0x37
        // ASCII digits / letters in bytes 2..25 are all printable.
        for (i in 2..25) {
            assertTrue("byte $i should render as printable", rendered[i] != '.')
        }
        assertEquals('.', rendered[26])            // 0xFF
        assertEquals('.', rendered[27])            // 0xFF
        assertEquals(':', rendered[28])            // 0x3A (checksum)
        assertEquals('.', rendered[29])            // 0x7F (DEL, non-printable)
    }

    @Test fun toPrintableAscii_handles_boundaries() {
        // 0x1F is non-printable, 0x20 (space) is printable, 0x7E (~) is printable, 0x7F (DEL) is not.
        val src = byteArrayOf(0x1F, 0x20, 0x7E, 0x7F)
        assertEquals(". ~.", src.toPrintableAscii())
    }

    @Test fun toPrintableAscii_empty() {
        assertEquals("", ByteArray(0).toPrintableAscii())
    }

    @Test fun suzukiChecksum_matches_captured_a537_frame() {
        // The captured frame's checksum byte (index 28) is 0x3A; suzukiChecksum sums bytes[1..27].
        assertEquals(0x3A, sampleFrame.suzukiChecksum())
    }

    @Test fun contentEqualsTo_structural_equality() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(1, 2, 3)
        val c = byteArrayOf(1, 2, 4)
        assertTrue(a.contentEqualsTo(b))
        assertTrue(!a.contentEqualsTo(c))
        // Same reference also returns true.
        assertTrue(a.contentEqualsTo(a))
    }

    @Test fun toByteUnsigned_round_trip_full_range() {
        for (i in 0..255) {
            val b = i.toByteUnsigned()
            assertEquals(i, b.toInt() and 0xFF)
        }
    }

    @Test fun toByteUnsigned_masks_high_bits() {
        // 0x1A5 -> 0xA5
        assertEquals(0xA5.toByte(), 0x1A5.toByteUnsigned())
        // -1 -> 0xFF (lowest 8 bits)
        assertEquals(0xFF.toByte(), (-1).toByteUnsigned())
    }
}
