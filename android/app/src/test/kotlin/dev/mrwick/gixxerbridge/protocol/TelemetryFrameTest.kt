package dev.mrwick.gixxerbridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Tests for the a537 [TelemetryFrame] decoder/encoder. */
class TelemetryFrameTest {

    // Real captured a537 from M0: engine off, no nav.
    private val sample = hex("a5373030303031363732393034393131303030393834394e3439ffff267f")

    @Test fun decodeEngineOff() {
        val f = TelemetryFrame.decode(sample)
        assertEquals(0, f.speedKmh)
        assertEquals(16729, f.odometerKm)
        assertEquals(4911.0, f.tripAKm, 1e-9)
        assertEquals(984.9, f.tripBKm, 1e-9)
        assertEquals(0x4E, f.byte23)
        assertEquals(4, f.fuelBars)
        // byte 25 = 0x4D → fuelEconKmlV2 = 77/2 = 38.5. Wait — bytes 25-27 in this sample are
        // `34 39 ff ff` from the right side? Actually looking at the hex: ...4e 34 39 ff ff 26 7f
        // → byte 23=0x4E, byte 24=0x34 ('4'), byte 25=0x39 ('9'), byte 26=0xFF, byte 27=0xFF.
        // So fuelEconKmlV2 = 0x39 / 2 = 57 / 2 = 28.5.
        val v2 = f.fuelEconKmlV2
        assertNotNull(v2)
        assertEquals(28.5, v2!!, 1e-9)
        // Legacy decode: NOT the full-FF sentinel since b25 = 0x39, so it produces a value.
        assertNotNull(f.fuelEconKml)
    }

    @Test fun decodeFullSentinelFuelEcon() {
        val raw = sample.copyOf()
        raw[25] = 0xFF.toByte()
        raw[26] = 0xFF.toByte()
        raw[27] = 0xFF.toByte()
        raw[28] = checksum(raw).toByte()
        val f = TelemetryFrame.decode(raw)
        assertNull(f.fuelEconKml)
        assertNull(f.fuelEconKmlV2)
    }

    @Test fun dispatchReturnsTelemetryFrame() {
        val f = decodeFrame(sample)
        assertTrue("expected TelemetryFrame but got ${f::class}", f is TelemetryFrame)
    }

    @Test fun roundtripBasicConstruct() {
        val f = TelemetryFrame(
            speedKmh = 42,
            odometerKm = 16729,
            tripAKm = 123.4,
            tripBKm = 56.7,
            fuelBars = 4,
            fuelEconKml = 50.5,
        )
        val out = f.encode()
        assertEquals(FRAME_LEN, out.size)
        assertTrue("frame must be well-formed", isWellFormed(out))
        val decoded = TelemetryFrame.decode(out)
        assertEquals(42, decoded.speedKmh)
        assertEquals(16729, decoded.odometerKm)
        assertTrue(abs(decoded.tripAKm - 123.4) < 0.001)
        assertTrue(abs(decoded.tripBKm - 56.7) < 0.001)
        assertEquals(4, decoded.fuelBars)
        val econ = decoded.fuelEconKml
        assertNotNull(econ)
        assertTrue("expected ~50.5 got $econ", abs(econ!! - 50.5) < 0.01)
    }

    @Test fun encodeNullFuelEconWritesFfPadding() {
        val f = TelemetryFrame(
            speedKmh = 0,
            odometerKm = 0,
            tripAKm = 0.0,
            tripBKm = 0.0,
            fuelBars = null,
            fuelEconKml = null,
        )
        val out = f.encode()
        assertEquals(0x00.toByte(), out[24])
        assertEquals(0xFF.toByte(), out[25])
        assertEquals(0xFF.toByte(), out[26])
        assertEquals(0xFF.toByte(), out[27])
    }
}
