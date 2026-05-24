package dev.mrwick.gixxerbridge.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the a536 [IdentityFrame] decoder/encoder. */
class IdentityFrameTest {

    private val sample = hex("a53641524a554e000000000000000000000000000000ffffffffff46f77f")

    @Test fun decode() {
        val f = IdentityFrame.decode(sample)
        assertEquals("ARJUN", f.name)
        assertTrue(f.isFresh)
    }

    @Test fun roundtripExactBytes() {
        val f = IdentityFrame.decode(sample)
        assertArrayEquals(sample, f.encode())
    }

    @Test fun dispatchReturnsIdentityFrame() {
        val f = decodeFrame(sample)
        assertTrue("expected IdentityFrame but got ${f::class}", f is IdentityFrame)
    }

    @Test fun freshBuiltFromConstructorEncodesFreshByte() {
        val f = IdentityFrame(name = "ARJUN", isFresh = true)
        val out = f.encode()
        assertEquals(0x46.toByte(), out[27])
        val decoded = IdentityFrame.decode(out)
        assertEquals("ARJUN", decoded.name)
        assertTrue(decoded.isFresh)
    }

    @Test fun reconnectFlagRoundTrips() {
        val f = IdentityFrame(name = "ARJUN", isFresh = false)
        val out = f.encode()
        val decoded = IdentityFrame.decode(out)
        assertEquals("ARJUN", decoded.name)
        assertFalse(decoded.isFresh)
        assertEquals(0x52.toByte(), out[27])
    }

    @Test fun oversizeNameTruncated() {
        val name = "ABCDEFGHIJKLMNOPQRSTUVWXYZ" // 26 chars
        val f = IdentityFrame(name = name, isFresh = true)
        val out = f.encode()
        // bytes 2..21 hold first 20 ASCII chars
        val payload = out.copyOfRange(2, 22)
        assertArrayEquals(name.take(20).toByteArray(Charsets.US_ASCII), payload)
        val decoded = IdentityFrame.decode(out)
        assertEquals(name.take(20), decoded.name)
    }
}
