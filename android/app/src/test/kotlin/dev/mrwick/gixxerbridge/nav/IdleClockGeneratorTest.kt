package dev.mrwick.gixxerbridge.nav

import dev.mrwick.gixxerbridge.protocol.NavFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/** Tests for [IdleClockGenerator]. */
class IdleClockGeneratorTest {

    @Test
    fun `morning clock encodes as AM with 12-hour rollover`() {
        val gen = IdleClockGenerator(clock = { LocalTime.of(10, 30) })
        val frame = gen.build(suzukiWeatherCode = 1, tempCelsius = 22.0)
        assertEquals("1030AM", frame.eta)
    }

    @Test
    fun `afternoon clock encodes as PM with 12-hour rollover`() {
        val gen = IdleClockGenerator(clock = { LocalTime.of(17, 5) })
        val frame = gen.build(suzukiWeatherCode = 2, tempCelsius = 27.5)
        assertEquals("0505PM", frame.eta)
    }

    @Test
    fun `noon and midnight clock edge cases`() {
        val noon = IdleClockGenerator(clock = { LocalTime.of(12, 0) }).build(0, null)
        assertEquals("1200PM", noon.eta)

        val midnight = IdleClockGenerator(clock = { LocalTime.of(0, 0) }).build(0, null)
        assertEquals("1200AM", midnight.eta)
    }

    @Test
    fun `weather code encodes into distNext (4-char zero-padded)`() {
        val frame = IdleClockGenerator(clock = { LocalTime.of(10, 30) })
            .build(suzukiWeatherCode = 2, tempCelsius = null)
        assertTrue("expected weather '02' substring in distNext '${frame.distNext}'",
            frame.distNext.contains("02"))
        assertEquals(" ", frame.distNextUnit)
    }

    @Test
    fun `temperature encodes into distTotal`() {
        val frame = IdleClockGenerator(clock = { LocalTime.of(10, 30) })
            .build(suzukiWeatherCode = 1, tempCelsius = 27.5)
        // 27.5 truncates to 27; padded to 4 chars → "0027"
        assertTrue("expected '0027' substring in distTotal '${frame.distTotal}'",
            frame.distTotal.contains("0027"))
        assertEquals("C", frame.distTotalUnit)
    }

    @Test
    fun `null temperature renders as 0000`() {
        val frame = IdleClockGenerator(clock = { LocalTime.of(10, 30) })
            .build(suzukiWeatherCode = 1, tempCelsius = null)
        assertEquals("0000", frame.distTotal)
    }

    @Test
    fun `frame has generic arrow and good status flags`() {
        val frame = IdleClockGenerator(clock = { LocalTime.of(10, 30) })
            .build(suzukiWeatherCode = 1, tempCelsius = 22.0)
        assertEquals(ManeuverMap.DEFAULT_CLUSTER_BYTE, frame.maneuverId)
        assertEquals("1", frame.status)
        assertEquals("1", frame.continueFlag)
    }

    @Test
    fun `built frame round-trips through encode-decode`() {
        val gen = IdleClockGenerator(clock = { LocalTime.of(14, 7) })
        val original = gen.build(suzukiWeatherCode = 4, tempCelsius = 31.0)
        val bytes = original.encode()
        val decoded = NavFrame.decode(bytes)
        assertEquals(original.maneuverId, decoded.maneuverId)
        assertEquals(original.distNext, decoded.distNext)
        assertEquals(original.eta, decoded.eta)
        assertEquals(original.distTotal, decoded.distTotal)
        assertEquals(original.distTotalUnit, decoded.distTotalUnit)
        assertEquals(original.status, decoded.status)
        assertEquals(original.continueFlag, decoded.continueFlag)
        assertEquals("0207PM", decoded.eta)
    }

    @Test
    fun `weather code is clamped to 0-99`() {
        // 150 should clamp to 99 and not crash
        val frame = IdleClockGenerator(clock = { LocalTime.of(10, 30) })
            .build(suzukiWeatherCode = 150, tempCelsius = null)
        assertTrue(frame.distNext.contains("99"))
    }
}
