package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.RideEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Pure-JVM tests for [RideStreak.compute]. Anchored to a fixed "today" in UTC. */
class RideStreakTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val today: LocalDate = LocalDate.of(2025, 6, 15)

    private fun ride(date: LocalDate, id: Long = 0): RideEntity = RideEntity(
        id = id,
        startedAtMillis = date.atStartOfDay(zone).toInstant().toEpochMilli(),
        endedAtMillis = null,
        startOdoKm = 0,
        endOdoKm = null,
        maxSpeedKmh = 0,
        avgSpeedKmh = 0.0,
        sampleCount = 0,
        fuelBarsStart = null,
        fuelBarsEnd = null,
    )

    @Test fun emptyHistoryIsZeroZero() {
        val s = RideStreak.compute(emptyList(), zone = zone, today = today)
        assertEquals(StreakInfo(0, 0), s)
    }

    @Test fun rodeTodayOnlyIsOneOne() {
        val s = RideStreak.compute(listOf(ride(today)), zone = zone, today = today)
        assertEquals(1, s.current)
        assertEquals(1, s.longest)
    }

    @Test fun rodeYesterdayButNotTodayStillCounts() {
        // Grace period: streak survives a not-yet-ridden today.
        val s = RideStreak.compute(
            listOf(ride(today.minusDays(1)), ride(today.minusDays(2))),
            zone = zone, today = today,
        )
        assertEquals(2, s.current)
        assertEquals(2, s.longest)
    }

    @Test fun gapOfTwoDaysBreaksCurrentStreak() {
        // No ride on day-1, so the chain back from today (or yesterday) is broken at day 0.
        val s = RideStreak.compute(
            listOf(ride(today.minusDays(2)), ride(today.minusDays(3))),
            zone = zone, today = today,
        )
        assertEquals(0, s.current)
        assertEquals(2, s.longest)
    }

    @Test fun longestStreakPicksLongestRun() {
        val rides = listOf(
            // Old 5-day streak
            ride(today.minusDays(20)), ride(today.minusDays(19)), ride(today.minusDays(18)),
            ride(today.minusDays(17)), ride(today.minusDays(16)),
            // Recent 3-day streak ending today
            ride(today.minusDays(2)), ride(today.minusDays(1)), ride(today),
        )
        val s = RideStreak.compute(rides, zone = zone, today = today)
        assertEquals(3, s.current)
        assertEquals(5, s.longest)
    }

    @Test fun multipleRidesSameDayCountAsOneDay() {
        val rides = listOf(
            ride(today, id = 1),
            ride(today, id = 2),
            ride(today, id = 3),
            ride(today.minusDays(1), id = 4),
        )
        val s = RideStreak.compute(rides, zone = zone, today = today)
        assertEquals(2, s.current)
        assertEquals(2, s.longest)
    }
}
