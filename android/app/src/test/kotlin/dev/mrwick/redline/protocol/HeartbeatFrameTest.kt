package dev.mrwick.redline.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil

/** Tests for the a533 [HeartbeatFrame] decoder/encoder. */
class HeartbeatFrameTest {

    private val sample = hex("a5333359323134003035303135344e4effffffffff010001ffffffff1a7f")

    @Test fun decode() {
        val f = HeartbeatFrame.decode(sample)
        assertEquals("3", f.batteryBucket)
        assertEquals("Y", f.charging)
        assertEquals("214", f.speedStr)
        assertEquals("0", f.signalStatus)
        assertEquals("050154", f.timeHhmmss)
        assertEquals("N", f.smsPending)
        assertEquals("N", f.callPending)
        assertEquals(1, f.weather)
        assertEquals(0, f.tempFPlus115)
        assertEquals(1, f.tailConst)
        assertNull(f.tempCelsius)
    }

    @Test fun tempDecodeRoundtrip() {
        val fahrenheit = ceil((9.0 * 27.0) / 5.0 + 32.0).toInt() // 81
        val f = HeartbeatFrame(
            batteryBucket = "3",
            charging = "Y",
            speedStr = "",
            signalStatus = "0",
            timeHhmmss = "120000",
            smsPending = "N",
            callPending = "N",
            weather = 1,
            tempFPlus115 = fahrenheit + 115,
        )
        assertEquals(196, f.tempFPlus115)
        val out = f.encode()
        val decoded = HeartbeatFrame.decode(out)
        assertEquals(196, decoded.tempFPlus115)
        // Celsius: 196 → F=81 → C=(81-32)*5/9 ≈ 27.22
        val c = decoded.tempCelsius
        assertNotNull(c)
        assertTrue("expected ~27.22 got $c", abs(c!! - 27.22) < 0.1)
    }

    @Test fun roundtripExactBytes() {
        val f = HeartbeatFrame.decode(sample)
        assertArrayEquals(sample, f.encode())
    }

    @Test fun dispatchReturnsHeartbeatFrame() {
        val f = decodeFrame(sample)
        assertTrue("expected HeartbeatFrame but got ${f::class}", f is HeartbeatFrame)
    }

    @Test fun encodeSpeedZeroUsesFfPadding() {
        val f = HeartbeatFrame(
            batteryBucket = "3",
            charging = "Y",
            speedStr = "",
            signalStatus = "0",
            timeHhmmss = "050154",
            smsPending = "N",
            callPending = "N",
        )
        val out = f.encode()
        assertEquals(0xFF.toByte(), out[4])
        assertEquals(0xFF.toByte(), out[5])
        assertEquals(0xFF.toByte(), out[6])
    }

    @Test fun encodeEmptyTimeUsesFfPadding() {
        val f = HeartbeatFrame(
            batteryBucket = "3",
            charging = "Y",
            speedStr = "",
            signalStatus = "0",
            timeHhmmss = "",
            smsPending = "N",
            callPending = "N",
        )
        val out = f.encode()
        for (i in 8..13) {
            assertEquals("byte $i should be 0xFF", 0xFF.toByte(), out[i])
        }
    }
}
