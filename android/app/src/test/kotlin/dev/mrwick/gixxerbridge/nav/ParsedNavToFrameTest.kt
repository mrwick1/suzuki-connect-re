package dev.mrwick.gixxerbridge.nav

import dev.mrwick.gixxerbridge.protocol.NavFrame
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for [ParsedNavData.toNavFrame] and the helper normalizers. */
class ParsedNavToFrameTest {

    @Test
    fun `toNavFrame copies fields and sets status and continue`() {
        val parsed = ParsedNavData(
            maneuverId = 3,
            distNext = "0220",
            distNextUnit = "M",
            eta = "0432PM",
            distTotal = "01.2",
            distTotalUnit = "K",
            streetName = "MG Road",
        )
        val frame = parsed.toNavFrame()
        assertEquals(3, frame.maneuverId)
        assertEquals("0220", frame.distNext)
        assertEquals("M", frame.distNextUnit)
        assertEquals("0432PM", frame.eta)
        assertEquals("01.2", frame.distTotal)
        assertEquals("K", frame.distTotalUnit)
        assertEquals("1", frame.status)
        assertEquals("1", frame.continueFlag)
    }

    @Test
    fun `toNavFrame round-trips through encode-decode`() {
        val parsed = ParsedNavData(
            maneuverId = 3,
            distNext = "0220",
            distNextUnit = "M",
            eta = "0432PM",
            distTotal = "01.2",
            distTotalUnit = "K",
            streetName = "MG Road",
        )
        val bytes = parsed.toNavFrame().encode()
        val decoded = NavFrame.decode(bytes)
        assertEquals(3, decoded.maneuverId)
        assertEquals("0220", decoded.distNext)
        assertEquals("M", decoded.distNextUnit)
        assertEquals("0432PM", decoded.eta)
        assertEquals("01.2", decoded.distTotal)
        assertEquals("K", decoded.distTotalUnit)
        assertEquals("1", decoded.status)
        assertEquals("1", decoded.continueFlag)
    }

    // ----- normalizeDistance ------------------------------------------------

    @Test
    fun `normalizeDistance handles meters whole numbers`() {
        assertEquals("0220" to "M", normalizeDistance("220 m"))
        assertEquals("0085" to "M", normalizeDistance("85 m"))
        assertEquals("0001" to "M", normalizeDistance("1 m"))
    }

    @Test
    fun `normalizeDistance handles km with one decimal`() {
        assertEquals("01.2" to "K", normalizeDistance("1.2 km"))
        assertEquals("12.0" to "K", normalizeDistance("12.0 km"))
    }

    @Test
    fun `normalizeDistance handles comma decimal (european locale)`() {
        assertEquals("01.5" to "K", normalizeDistance("1,5 km"))
    }

    @Test
    fun `normalizeDistance returns 0000 on null or empty or junk`() {
        assertEquals("0000" to "M", normalizeDistance(null))
        assertEquals("0000" to "M", normalizeDistance(""))
        assertEquals("0000" to "M", normalizeDistance("not a distance"))
    }

    @Test
    fun `normalizeDistance folds miles and feet to K and M`() {
        assertEquals("01.2" to "K", normalizeDistance("1.2 mi"))
        assertEquals("0500" to "M", normalizeDistance("500 ft"))
    }

    @Test
    fun `normalizeDistance handles km whole numbers up to 4 digits`() {
        assertEquals("1234" to "K", normalizeDistance("1234 km"))
    }

    // ----- normalizeEta -----------------------------------------------------

    @Test
    fun `normalizeEta extracts clock from full nav_time string in 12h`() {
        val input = "5 min · 1.2 km · 4:32 PM"
        assertEquals("0432PM", normalizeEta(input, twelveHour = true))
    }

    @Test
    fun `normalizeEta extracts clock from full nav_time string in 24h`() {
        val input = "5 min · 1.2 km · 16:32"
        assertEquals("163200", normalizeEta(input, twelveHour = false))
    }

    @Test
    fun `normalizeEta handles AM noon and midnight boundaries`() {
        // 12:00 AM = midnight = 00:00 in 24h, 12:00AM in 12h
        assertEquals("1200AM", normalizeEta("ETA 12:00 AM", twelveHour = true))
        // 12:00 PM = noon = 12:00 in 24h, 12:00PM in 12h
        assertEquals("1200PM", normalizeEta("ETA 12:00 PM", twelveHour = true))
        // 1:30 PM = 13:30 in 24h
        assertEquals("133000", normalizeEta("ETA 1:30 PM", twelveHour = false))
    }
}
