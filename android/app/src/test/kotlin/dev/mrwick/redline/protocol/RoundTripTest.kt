package dev.mrwick.redline.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Encode -> decode -> encode round-trip coverage for every frame type, plus
 * the top-level [decodeFrame] dispatcher's error cases.
 */
class RoundTripTest {

    // ----- a531 NAV -----
    @Test fun navRoundtrip() {
        val original = NavFrame(
            maneuverId = 0x08,
            distNext = "0120",
            distNextUnit = "M",
            eta = "0528PM",
            distTotal = "05.1",
            distTotalUnit = "K",
            status = "1",
            continueFlag = "1",
        )
        val bytes = original.encode()
        assertTrue(isWellFormed(bytes))
        val decoded = NavFrame.decode(bytes)
        assertEquals(original.maneuverId, decoded.maneuverId)
        assertEquals(original.distNext, decoded.distNext)
        assertEquals(original.eta, decoded.eta)
        assertEquals(original.distTotal, decoded.distTotal)
        assertEquals(original.status, decoded.status)
        assertEquals(original.continueFlag, decoded.continueFlag)
        // encode-decode-encode stability
        assertArrayEquals(bytes, decoded.encode())
    }

    // ----- a532 CALL -----
    @Test fun callRoundtripCellular() {
        val f = CallFrame(number = "+919876543210", isWhatsapp = false, state = '1'.code)
        val out = f.encode()
        assertTrue(isWellFormed(out))
        assertEquals(0x4E.toByte(), out[22])
        val d = CallFrame.decode(out)
        assertEquals("+919876543210", d.number)
        assertFalse(d.isWhatsapp)
        assertEquals('1'.code, d.state)
        assertArrayEquals(out, d.encode())
    }

    @Test fun callRoundtripWhatsapp() {
        val f = CallFrame(number = "+919876543210", isWhatsapp = true, state = '2'.code)
        val out = f.encode()
        assertTrue(isWellFormed(out))
        assertEquals(0x57.toByte(), out[22])
        val d = CallFrame.decode(out)
        assertTrue(d.isWhatsapp)
        assertEquals('2'.code, d.state)
    }

    // ----- a533 HEARTBEAT -----
    @Test fun heartbeatRoundtrip() {
        val f = HeartbeatFrame(
            batteryBucket = "2",
            charging = "N",
            speedStr = "045",
            signalStatus = "3",
            timeHhmmss = "120000",
            smsPending = "N",
            callPending = "N",
            weather = 6,
            tempFPlus115 = 196,
            tailConst = 1,
        )
        val out = f.encode()
        assertTrue(isWellFormed(out))
        val d = HeartbeatFrame.decode(out)
        assertEquals("2", d.batteryBucket)
        assertEquals("N", d.charging)
        assertEquals("045", d.speedStr)
        assertEquals("3", d.signalStatus)
        assertEquals("120000", d.timeHhmmss)
        assertEquals(6, d.weather)
        assertEquals(196, d.tempFPlus115)
        assertArrayEquals(out, d.encode())
    }

    // ----- a534 MISSED CALL -----
    @Test fun missedCallRoundtripCellular() {
        val f = MissedCallFrame(name = "MOM", missedCount = 2, isWhatsapp = false)
        val out = f.encode()
        assertTrue(isWellFormed(out))
        assertEquals(0x4E.toByte(), out[24])
        assertEquals('Y'.code.toByte(), out[4])
        assertEquals('1'.code.toByte(), out[5])
        val d = MissedCallFrame.decode(out)
        assertEquals("MOM", d.name)
        assertEquals(2, d.missedCount)
        assertFalse(d.isWhatsapp)
        assertArrayEquals(out, d.encode())
    }

    @Test fun missedCallRoundtripWhatsapp() {
        val f = MissedCallFrame(name = "Boss", missedCount = 5, isWhatsapp = true)
        val out = f.encode()
        assertTrue(isWellFormed(out))
        assertEquals(0x57.toByte(), out[24])
        val d = MissedCallFrame.decode(out)
        assertEquals("Boss", d.name)
        assertEquals(5, d.missedCount)
        assertTrue(d.isWhatsapp)
    }

    // ----- a535 SMS -----
    @Test fun smsRoundtripSilenced() {
        val f = SmsFrame(sender = "John", messageCount = 3, silenced = true)
        val out = f.encode()
        assertTrue(isWellFormed(out))
        assertEquals(0x4E.toByte(), out[3])
        assertEquals(3.toByte(), out[4])
        val d = SmsFrame.decode(out)
        assertEquals("John", d.sender)
        assertEquals(3, d.messageCount)
        assertTrue(d.silenced)
        assertArrayEquals(out, d.encode())
    }

    @Test fun smsRoundtripNotSilenced() {
        val f = SmsFrame(sender = "Alice", messageCount = 1, silenced = false, typeByte = 0x57)
        val out = f.encode()
        assertTrue(isWellFormed(out))
        assertEquals(0x59.toByte(), out[3])
        val d = SmsFrame.decode(out)
        assertFalse(d.silenced)
        assertEquals(0x57, d.typeByte)
    }

    // ----- a536 IDENTITY -----
    @Test fun identityRoundtripFresh() {
        val f = IdentityFrame(name = "ARJUN", isFresh = true)
        val out = f.encode()
        assertTrue(isWellFormed(out))
        val d = IdentityFrame.decode(out)
        assertEquals("ARJUN", d.name)
        assertTrue(d.isFresh)
        assertArrayEquals(out, d.encode())
    }

    @Test fun identityRoundtripReconnect() {
        val f = IdentityFrame(name = "Arjun", isFresh = false)
        val out = f.encode()
        assertTrue(isWellFormed(out))
        val d = IdentityFrame.decode(out)
        assertFalse(d.isFresh)
        assertEquals("Arjun", d.name)
    }

    // ----- a537 TELEMETRY -----
    @Test fun telemetryRoundtripWithFuelEcon() {
        val f = TelemetryFrame(
            speedKmh = 42,
            odometerKm = 16729,
            tripAKm = 123.4,
            tripBKm = 56.7,
            fuelBars = 4,
            fuelEconKml = 50.5,
        )
        val out = f.encode()
        assertTrue(isWellFormed(out))
        val d = TelemetryFrame.decode(out)
        assertEquals(42, d.speedKmh)
        assertEquals(16729, d.odometerKm)
        assertArrayEquals(out, d.encode())
    }

    @Test fun telemetryRoundtripNullFuel() {
        val f = TelemetryFrame(
            speedKmh = 0,
            odometerKm = 1,
            tripAKm = 0.0,
            tripBKm = 0.0,
            fuelBars = null,
            fuelEconKml = null,
        )
        val out = f.encode()
        assertTrue(isWellFormed(out))
        val d = TelemetryFrame.decode(out)
        assertEquals(0, d.speedKmh)
        assertEquals(1, d.odometerKm)
        // fuelEconKmlV2 should be null because bytes 25-27 are 0xFF.
        assertEquals(null, d.fuelEconKmlV2)
    }

    // ----- Dispatcher errors -----
    @Test fun decodeRejectsWrongLength() {
        val sample = hex("a53108ff303132304d30353238504dffffff30352e314b3131ffffff1f7f")
        try {
            decodeFrame(sample.copyOfRange(0, 29))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun decodeRejectsUnknownType() {
        val sample = hex("a53108ff303132304d30353238504dffffff30352e314b3131ffffff1f7f")
        val bad = sample.copyOf()
        bad[1] = 0x39 // '9' — not a known type
        bad[28] = checksum(bad).toByte()
        try {
            decodeFrame(bad)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
