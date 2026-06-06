package dev.mrwick.gixxerbridge.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Pure-JVM tests for [RideLogger.autoName]. No Room, no Android — just
 * direct calls to the companion-object helper.
 *
 * Timestamps are derived from `LocalDateTime` in the system default zone so
 * the test matches how [autoName] resolves time-of-day on the running JVM.
 * This couples the test to the JVM's timezone — acceptable because both
 * autoName and this test use the same `ZoneId.systemDefault()`.
 */
class RideLoggerNamingTest {

    /** Helper: turn a local date-time into a UTC-epoch millis the way autoName expects. */
    private fun millisFor(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test fun morningWeekdayShortDistanceIsCommute() {
        // 2026-05-25 is a Monday; 08:00 falls in the Morning bucket.
        val name = RideLogger.autoName(millisFor(2026, 5, 25, 8), distance = 12)
        assertEquals("Morning commute (Mon)", name)
    }

    @Test fun eveningWeekdayShortDistanceIsCommuteHome() {
        val name = RideLogger.autoName(millisFor(2026, 5, 26, 18), distance = 15)
        assertEquals("Evening commute home (Tue)", name)
    }

    @Test fun longWeekdayRideIsNotACommute() {
        // 50 km on Wednesday morning -> not "commute" because distance>=20.
        val name = RideLogger.autoName(millisFor(2026, 5, 27, 7), distance = 50)
        assertEquals("Morning ride (Wed)", name)
    }

    @Test fun weekendLongDistanceIsRide() {
        // Saturday afternoon, 60 km ride.
        val name = RideLogger.autoName(millisFor(2026, 5, 30, 14), distance = 60)
        assertEquals("Day ride (Sat)", name)
    }

    @Test fun weekendShortDistanceIsStillRide() {
        // Sunday morning errand <= 30 km still labelled "ride" (the tag rule
        // only switches the weekend label when distance > 30).
        val name = RideLogger.autoName(millisFor(2026, 5, 31, 9), distance = 8)
        assertEquals("Morning ride (Sun)", name)
    }

    @Test fun lateNightFallsIntoLateNightBucket() {
        // 02:00 Friday -> Late night ride (Fri)
        val name = RideLogger.autoName(millisFor(2026, 5, 29, 2), distance = 5)
        assertEquals("Late night ride (Fri)", name)
    }

    @Test fun nightBucketIs1922() {
        // 21:00 Thursday -> Night
        val name = RideLogger.autoName(millisFor(2026, 5, 28, 21), distance = 5)
        assertTrue("expected Night bucket, got: $name", name.startsWith("Night "))
    }

    @Test fun dayBucketIs1015() {
        // 12:00 Monday, 25 km -> Day ride (Mon)
        val name = RideLogger.autoName(millisFor(2026, 5, 25, 12), distance = 25)
        assertEquals("Day ride (Mon)", name)
    }

    @Test fun weekdayMorningLongDistanceIsRideNotCommute() {
        // 30 km is at the boundary -> not commute (the rule is distance < 20).
        val name = RideLogger.autoName(millisFor(2026, 5, 25, 6), distance = 30)
        assertEquals("Morning ride (Mon)", name)
    }

    // ---------- shouldDiscard ----------

    @Test fun discardsRideThatNeverMoved() {
        // Never moved → always noise, regardless of distance/duration.
        assertTrue(RideLogger.shouldDiscard(everMoved = false, distanceKm = 0, durationMs = 600_000L))
        assertTrue(RideLogger.shouldDiscard(everMoved = false, distanceKm = 5, durationMs = 600_000L))
    }

    @Test fun keepsRealRideThatMovedAndCoveredDistance() {
        assertFalse(RideLogger.shouldDiscard(everMoved = true, distanceKm = 5, durationMs = 600_000L))
    }

    @Test fun discardsShortBlipEvenIfItMoved() {
        assertTrue(RideLogger.shouldDiscard(everMoved = true, distanceKm = 0, durationMs = 10_000L))
    }

    @Test fun keepsMoved1KmRideEvenIfShort() {
        // distanceKm == 1 is NOT a blip; the threshold is `< 1`, not `<= 1`.
        assertFalse(RideLogger.shouldDiscard(everMoved = true, distanceKm = 1, durationMs = 10_000L))
    }
}
