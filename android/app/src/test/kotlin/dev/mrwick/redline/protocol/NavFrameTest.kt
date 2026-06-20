package dev.mrwick.redline.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Tests for the a531 [NavFrame] decoder/encoder. */
class NavFrameTest {

    private val degraded = hex("a5312eff303038304d30353137504dffffff30352e364b3031ffffff4c7f")
    private val real = hex("a53108ff303132304d30353238504dffffff30352e314b3131ffffff1f7f")

    @Test fun degradedDecode() {
        val f = NavFrame.decode(degraded)
        assertEquals(0x2E, f.maneuverId)
        assertEquals("0080", f.distNext)
        assertEquals("M", f.distNextUnit)
        assertEquals("0517PM", f.eta)
        assertEquals("05.6", f.distTotal)
        assertEquals("K", f.distTotalUnit)
        assertEquals("0", f.status)
        assertEquals("1", f.continueFlag)
    }

    @Test fun realManeuverDecode() {
        val f = NavFrame.decode(real)
        assertEquals(0x08, f.maneuverId)
        assertEquals("0120", f.distNext)
        assertEquals("0528PM", f.eta)
        assertEquals("05.1", f.distTotal)
        assertEquals("K", f.distTotalUnit)
        assertEquals("1", f.status)
        assertEquals("1", f.continueFlag)
    }

    @Test fun dispatchReturnsNavFrame() {
        val f = decodeFrame(real)
        assertTrue("expected NavFrame but got ${f::class}", f is NavFrame)
    }

    @Test fun roundtripDegraded() {
        val f = NavFrame.decode(degraded)
        assertArrayEquals(degraded, f.encode())
    }

    @Test fun roundtripReal() {
        val f = NavFrame.decode(real)
        assertArrayEquals(real, f.encode())
    }

    @Test fun decodeRejectsWrongType() {
        val bad = degraded.copyOf()
        bad[1] = FrameType.HEARTBEAT.code
        // checksum will then be wrong → well-formed fails first
        try {
            NavFrame.decode(bad)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
