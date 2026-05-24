package dev.mrwick.gixxerbridge.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HexTest {

    // Captured a537 telemetry frame used elsewhere in the project (see BytesTest).
    private val sampleFrame: ByteArray = byteArrayOf(
        0xa5.toByte(), 0x37.toByte(), 0x30, 0x30, 0x30, 0x30, 0x31, 0x36, 0x37, 0x32,
        0x39, 0x30, 0x34, 0x39, 0x31, 0x31, 0x30, 0x30, 0x30, 0x39, 0x38, 0x34, 0x39,
        0x4e, 0x34, 0x4d, 0xff.toByte(), 0xff.toByte(), 0x3a, 0x7f,
    )

    @Test fun encode_empty_returns_empty_string() {
        assertEquals("", Hex.encode(ByteArray(0)))
        assertEquals("", Hex.encode(ByteArray(0), separator = ""))
    }

    @Test fun encode_single_byte_two_chars() {
        assertEquals("a5", Hex.encode(byteArrayOf(0xA5.toByte())))
        assertEquals("00", Hex.encode(byteArrayOf(0x00)))
        assertEquals("ff", Hex.encode(byteArrayOf(0xFF.toByte())))
    }

    @Test fun encode_two_bytes_default_separator_is_space() {
        assertEquals("a5 37", Hex.encode(byteArrayOf(0xA5.toByte(), 0x37.toByte())))
    }

    @Test fun encode_two_bytes_empty_separator_is_concatenated() {
        assertEquals("a537", Hex.encode(byteArrayOf(0xA5.toByte(), 0x37.toByte()), separator = ""))
    }

    @Test fun encode_sample_frame_30_bytes_with_space() {
        val expected = "a5 37 30 30 30 30 31 36 37 32 39 30 34 39 31 31 30 30 30 39 38 34 39 4e 34 4d ff ff 3a 7f"
        assertEquals(expected, Hex.encode(sampleFrame))
    }

    @Test fun encodeByte_covers_signed_and_unsigned_range() {
        assertEquals("00", Hex.encodeByte(0))
        assertEquals("7f", Hex.encodeByte(0x7F))
        assertEquals("80", Hex.encodeByte(0x80.toByte()))
        assertEquals("ff", Hex.encodeByte(0xFF.toByte()))
        assertEquals("a5", Hex.encodeByte(0xA5.toByte()))
    }

    @Test fun decode_lowercase_no_separator() {
        assertArrayEquals(byteArrayOf(0xa5.toByte(), 0x37), Hex.decode("a537"))
    }

    @Test fun decode_with_spaces() {
        assertArrayEquals(byteArrayOf(0xa5.toByte(), 0x37, 0xff.toByte()), Hex.decode("a5 37 ff"))
    }

    @Test fun decode_uppercase() {
        assertArrayEquals(byteArrayOf(0xa5.toByte(), 0x37, 0xff.toByte()), Hex.decode("A537FF"))
    }

    @Test fun decode_colon_separator_uppercase() {
        assertArrayEquals(byteArrayOf(0xa5.toByte(), 0x31, 0xff.toByte()), Hex.decode("A5:31:FF"))
    }

    @Test fun decode_handles_0x_prefix() {
        assertArrayEquals(byteArrayOf(0xa5.toByte(), 0x37), Hex.decode("0xa537"))
    }

    @Test fun decode_handles_per_byte_0x_prefix() {
        assertArrayEquals(byteArrayOf(0xa5.toByte(), 0x37), Hex.decode("0xa5 0x37"))
    }

    @Test fun decode_empty_returns_empty_array() {
        assertArrayEquals(ByteArray(0), Hex.decode(""))
        assertArrayEquals(ByteArray(0), Hex.decode("   "))
    }

    @Test fun decode_odd_length_throws() {
        assertThrows(IllegalArgumentException::class.java) { Hex.decode("a5f") }
        assertThrows(IllegalArgumentException::class.java) { Hex.decode("a 5 f") }
    }

    @Test fun decode_non_hex_char_throws() {
        assertThrows(IllegalArgumentException::class.java) { Hex.decode("zz") }
        assertThrows(IllegalArgumentException::class.java) { Hex.decode("a5g7") }
    }

    @Test fun roundtrip_arbitrary_bytes() {
        val src = ByteArray(256) { it.toByte() } // 0x00..0xFF
        val encoded = Hex.encode(src, separator = "")
        assertArrayEquals(src, Hex.decode(encoded))
    }

    @Test fun roundtrip_with_space_separator() {
        val encoded = Hex.encode(sampleFrame)
        assertArrayEquals(sampleFrame, Hex.decode(encoded))
    }
}
