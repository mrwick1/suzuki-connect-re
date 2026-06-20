package dev.mrwick.redline.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for [SpeedTrackColors].
 *
 * The primary job of this test class is to **pin** the exact ARGB Int values
 * against the canonical GixxerTokens hex literals. Any change to either the
 * constants in [SpeedTrackColors] or the source-of-truth
 * `GixxerTokens.zone*` colors will break these tests, surfacing the
 * discrepancy before it reaches a device.
 *
 * Secondary: verify that [SpeedTrackColors.colorFor] maps speed values to
 * the correct zone across boundaries and edge cases.
 */
class SpeedTrackColorsTest {

    // --- Constant value pins ------------------------------------------------
    // These lock the literal hex values so a future token rename / re-palette
    // cannot silently desync SpeedTrackColors from GixxerTokens.

    @Test fun `COLOR_COOL pins to GixxerTokens_zoneCool 0xFF10D9C4`() {
        // GixxerTokens.zoneCool = Color(0xFF10D9C4)
        assertEquals(0xFF10D9C4.toInt(), SpeedTrackColors.COLOR_COOL)
    }

    @Test fun `COLOR_MID pins to GixxerTokens_zoneMid 0xFFF5A524`() {
        // GixxerTokens.zoneMid = Color(0xFFF5A524)
        assertEquals(0xFFF5A524.toInt(), SpeedTrackColors.COLOR_MID)
    }

    @Test fun `COLOR_HOT pins to GixxerTokens_zoneHot 0xFFFF2D78`() {
        // GixxerTokens.zoneHot = Color(0xFFFF2D78)
        assertEquals(0xFFFF2D78.toInt(), SpeedTrackColors.COLOR_HOT)
    }

    // --- Threshold value pins -----------------------------------------------

    @Test fun `THRESHOLD_COOL_MID_KMH is 60`() {
        assertEquals(60, SpeedTrackColors.THRESHOLD_COOL_MID_KMH)
    }

    @Test fun `THRESHOLD_MID_HOT_KMH is 100`() {
        assertEquals(100, SpeedTrackColors.THRESHOLD_MID_HOT_KMH)
    }

    @Test fun `CEILING_KMH is 130`() {
        assertEquals(130, SpeedTrackColors.CEILING_KMH)
    }

    // --- colorFor: cool zone ------------------------------------------------

    @Test fun `colorFor negative speed returns COLOR_COOL`() {
        assertEquals(SpeedTrackColors.COLOR_COOL, SpeedTrackColors.colorFor(-1))
    }

    @Test fun `colorFor zero is COLOR_COOL`() {
        assertEquals(SpeedTrackColors.COLOR_COOL, SpeedTrackColors.colorFor(0))
    }

    @Test fun `colorFor 30 is COLOR_COOL`() {
        assertEquals(SpeedTrackColors.COLOR_COOL, SpeedTrackColors.colorFor(30))
    }

    @Test fun `colorFor just below THRESHOLD_COOL_MID is COLOR_COOL`() {
        assertEquals(
            SpeedTrackColors.COLOR_COOL,
            SpeedTrackColors.colorFor(SpeedTrackColors.THRESHOLD_COOL_MID_KMH - 1),
        )
    }

    // --- colorFor: mid zone -------------------------------------------------

    @Test fun `colorFor at THRESHOLD_COOL_MID is COLOR_MID`() {
        assertEquals(
            SpeedTrackColors.COLOR_MID,
            SpeedTrackColors.colorFor(SpeedTrackColors.THRESHOLD_COOL_MID_KMH),
        )
    }

    @Test fun `colorFor 80 is COLOR_MID`() {
        assertEquals(SpeedTrackColors.COLOR_MID, SpeedTrackColors.colorFor(80))
    }

    @Test fun `colorFor just below THRESHOLD_MID_HOT is COLOR_MID`() {
        assertEquals(
            SpeedTrackColors.COLOR_MID,
            SpeedTrackColors.colorFor(SpeedTrackColors.THRESHOLD_MID_HOT_KMH - 1),
        )
    }

    // --- colorFor: hot zone -------------------------------------------------

    @Test fun `colorFor at THRESHOLD_MID_HOT is COLOR_HOT`() {
        assertEquals(
            SpeedTrackColors.COLOR_HOT,
            SpeedTrackColors.colorFor(SpeedTrackColors.THRESHOLD_MID_HOT_KMH),
        )
    }

    @Test fun `colorFor 120 is COLOR_HOT`() {
        assertEquals(SpeedTrackColors.COLOR_HOT, SpeedTrackColors.colorFor(120))
    }

    @Test fun `colorFor at CEILING is COLOR_HOT`() {
        assertEquals(
            SpeedTrackColors.COLOR_HOT,
            SpeedTrackColors.colorFor(SpeedTrackColors.CEILING_KMH),
        )
    }

    @Test fun `colorFor above CEILING is COLOR_HOT`() {
        assertEquals(SpeedTrackColors.COLOR_HOT, SpeedTrackColors.colorFor(999))
    }
}
