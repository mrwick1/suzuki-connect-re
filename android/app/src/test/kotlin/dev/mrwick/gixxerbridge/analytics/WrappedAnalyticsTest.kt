package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.FuelFillEntity
import dev.mrwick.gixxerbridge.data.RideEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

// Note: WrappedAnalytics.compute() has no zone parameter — the zone is
// embedded in the WrappedWindow. Tests pass utc into WrappedWindow factories.

/**
 * Pure-JVM tests for [WrappedAnalytics.compute].
 *
 * Fixed "today" = 2025-06-07 UTC. All timestamps derived from this anchor so
 * tests are deterministic regardless of wall clock.
 *
 * Naming convention: describe the scenario, not "test" prefix.
 */
class WrappedAnalyticsTest {

    private val utc = ZoneOffset.UTC

    // Anchor: 2025-06-07 00:00:00 UTC in millis
    private val anchor: Long = LocalDate.of(2025, 6, 7)
        .atStartOfDay(utc).toInstant().toEpochMilli()

    private val day: Long = 86_400_000L

    // Full-year 2025 window used by many tests
    private val year2025 = WrappedWindow.ofCalendarYear(2025, utc)

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Build a minimal ended [RideEntity]. daysAgo is relative to [anchor]. */
    private fun ride(
        id: Long,
        daysAgo: Long,
        startOdo: Int,
        distanceKm: Int,
        durationMinutes: Long = 30,
        maxSpeedKmh: Int = 80,
        avgSpeedKmh: Double = 50.0,
        name: String? = null,
        fuelBarsStart: Int? = 4,
        fuelBarsEnd: Int? = 3,
    ): RideEntity {
        val start = anchor - daysAgo * day
        return RideEntity(
            id = id,
            startedAtMillis = start,
            endedAtMillis = start + durationMinutes * 60_000L,
            startOdoKm = startOdo,
            endOdoKm = startOdo + distanceKm,
            maxSpeedKmh = maxSpeedKmh,
            avgSpeedKmh = avgSpeedKmh,
            sampleCount = 10,
            fuelBarsStart = fuelBarsStart,
            fuelBarsEnd = fuelBarsEnd,
            name = name,
        )
    }

    /** Build a [FuelFillEntity]. daysAgo is relative to [anchor]. */
    private fun fill(
        id: Long,
        daysAgo: Long,
        odometerKm: Int,
        litres: Double,
        rupees: Double? = null,
        note: String? = null,
    ): FuelFillEntity = FuelFillEntity(
        id = id,
        tMillis = anchor - daysAgo * day,
        odometerKm = odometerKm,
        litres = litres,
        rupees = rupees,
        note = note,
    )

    // -------------------------------------------------------------------------
    // No data
    // -------------------------------------------------------------------------

    @Test fun noRidesReturnsNull() {
        val result = WrappedAnalytics.compute(
            rides = emptyList(),
            fills = emptyList(),
            avgKmPerL = null,
            window = year2025,
        )
        assertNull(result)
    }

    @Test fun onlyInProgressRidesReturnNull() {
        // In-progress ride has endOdoKm = null — excluded from wrapped
        val inProgress = RideEntity(
            id = 1,
            startedAtMillis = anchor - 1 * day,
            endedAtMillis = null,
            startOdoKm = 1000,
            endOdoKm = null,   // no end odo
            maxSpeedKmh = 60,
            avgSpeedKmh = 40.0,
            sampleCount = 5,
            fuelBarsStart = 4,
            fuelBarsEnd = null,
        )
        val result = WrappedAnalytics.compute(
            rides = listOf(inProgress),
            fills = emptyList(),
            avgKmPerL = null,
            window = year2025,
        )
        assertNull(result)
    }

    @Test fun ridesOutsideWindowExcluded() {
        // Ride in 2024, window is 2025
        val oldRide = ride(id = 1, daysAgo = 400, startOdo = 100, distanceKm = 50)
        val result = WrappedAnalytics.compute(
            rides = listOf(oldRide),
            fills = emptyList(),
            avgKmPerL = null,
            window = year2025,
        )
        assertNull(result)
    }

    // -------------------------------------------------------------------------
    // Basic aggregate fields
    // -------------------------------------------------------------------------

    @Test fun singleRideTotals() {
        val r = ride(id = 1, daysAgo = 1, startOdo = 1000, distanceKm = 40, durationMinutes = 60)
        val result = WrappedAnalytics.compute(
            rides = listOf(r),
            fills = emptyList(),
            avgKmPerL = null,
            window = year2025,
        )!!
        assertEquals(40, result.totalKm)
        assertEquals(1.0, result.saddleHours, 0.001)
        assertEquals(1, result.rideCount)
    }

    @Test fun multipleRidesSumKmAndHours() {
        val rides = listOf(
            ride(id = 1, daysAgo = 5, startOdo = 100, distanceKm = 20, durationMinutes = 30),
            ride(id = 2, daysAgo = 3, startOdo = 120, distanceKm = 50, durationMinutes = 60),
            ride(id = 3, daysAgo = 1, startOdo = 170, distanceKm = 10, durationMinutes = 15),
        )
        val result = WrappedAnalytics.compute(
            rides = rides,
            fills = emptyList(),
            avgKmPerL = null,
            window = year2025,
        )!!
        assertEquals(80, result.totalKm)
        assertEquals(3, result.rideCount)
        assertEquals(1.75, result.saddleHours, 0.001)
    }

    // -------------------------------------------------------------------------
    // Longest ride
    // -------------------------------------------------------------------------

    @Test fun longestRidePicksMaxKm() {
        val rides = listOf(
            ride(id = 1, daysAgo = 10, startOdo = 100, distanceKm = 20, name = "Short"),
            ride(id = 2, daysAgo = 5, startOdo = 120, distanceKm = 80, name = "Epic run"),
            ride(id = 3, daysAgo = 1, startOdo = 200, distanceKm = 30, name = "Medium"),
        )
        val result = WrappedAnalytics.compute(
            rides = rides,
            fills = emptyList(),
            avgKmPerL = null,
            window = year2025,
        )!!
        assertEquals(80, result.longestRide.km)
        assertEquals("Epic run", result.longestRide.name)
    }

    @Test fun longestRideNameCanBeNull() {
        val r = ride(id = 1, daysAgo = 2, startOdo = 500, distanceKm = 30, name = null)
        val result = WrappedAnalytics.compute(
            rides = listOf(r),
            fills = emptyList(),
            avgKmPerL = null,
            window = year2025,
        )!!
        assertNull(result.longestRide.name)
    }

    @Test fun longestRideDateIsStartDate() {
        // daysAgo = 2 → date is anchor - 2 days
        val expectedDate = LocalDate.of(2025, 6, 5)
        val r = ride(id = 1, daysAgo = 2, startOdo = 500, distanceKm = 30)
        val result = WrappedAnalytics.compute(
            rides = listOf(r),
            fills = emptyList(),
            avgKmPerL = null,
            window = year2025,
        )!!
        assertEquals(expectedDate, result.longestRide.date)
    }

    // -------------------------------------------------------------------------
    // Top speed
    // -------------------------------------------------------------------------

    @Test fun topSpeedPicksHighestMaxSpeedKmh() {
        val rides = listOf(
            ride(id = 1, daysAgo = 5, startOdo = 100, distanceKm = 20, maxSpeedKmh = 90),
            ride(id = 2, daysAgo = 3, startOdo = 120, distanceKm = 30, maxSpeedKmh = 130, name = "Highway blast"),
            ride(id = 3, daysAgo = 1, startOdo = 150, distanceKm = 10, maxSpeedKmh = 70),
        )
        val result = WrappedAnalytics.compute(
            rides = rides,
            fills = emptyList(),
            avgKmPerL = null,
            window = year2025,
        )!!
        assertEquals(130, result.topSpeed.speedKmh)
        assertEquals("Highway blast", result.topSpeed.rideName)
    }

    @Test fun topSpeedRideNameNullableWhenUnnamed() {
        val r = ride(id = 1, daysAgo = 1, startOdo = 200, distanceKm = 15, maxSpeedKmh = 95, name = null)
        val result = WrappedAnalytics.compute(
            rides = listOf(r),
            fills = emptyList(),
            avgKmPerL = null,
            window = year2025,
        )!!
        assertNull(result.topSpeed.rideName)
        assertEquals(95, result.topSpeed.speedKmh)
    }

    // -------------------------------------------------------------------------
    // Busiest month
    // -------------------------------------------------------------------------

    @Test fun busiestMonthPicksHighestKmMonth() {
        // Three rides: two in May 2025 (60 km total), one in June 2025 (15 km)
        // anchor = 2025-06-07
        val rides = listOf(
            ride(id = 1, daysAgo = 38, startOdo = 100, distanceKm = 35), // ~2025-04-30 → still April
            ride(id = 2, daysAgo = 20, startOdo = 135, distanceKm = 25), // 2025-05-18
            ride(id = 3, daysAgo = 15, startOdo = 160, distanceKm = 35), // 2025-05-23
            ride(id = 4, daysAgo = 1, startOdo = 195, distanceKm = 15),  // 2025-06-06
        )
        val result = WrappedAnalytics.compute(
            rides = rides,
            fills = emptyList(),
            avgKmPerL = null,
            window = year2025,
        )!!
        assertEquals("2025-05", result.busiestMonth.label)
        assertEquals(60, result.busiestMonth.totalKm)
    }

    @Test fun busiestMonthSingleMonth() {
        val r = ride(id = 1, daysAgo = 5, startOdo = 100, distanceKm = 40)
        val result = WrappedAnalytics.compute(
            rides = listOf(r),
            fills = emptyList(),
            avgKmPerL = null,
            window = year2025,
        )!!
        // The label should be "2025-06" (anchor minus 5 days is still June 2025)
        assertEquals("2025-06", result.busiestMonth.label)
        assertEquals(40, result.busiestMonth.totalKm)
    }

    // -------------------------------------------------------------------------
    // Busiest weekday
    // -------------------------------------------------------------------------

    @Test fun busiestWeekdayPicksHighestKmDay() {
        // anchor = 2025-06-07 (Saturday, ISO = 6)
        // daysAgo = 1 → 2025-06-06 (Friday, ISO = 5)
        // daysAgo = 8 → 2025-05-30 (Friday, ISO = 5)
        // Total Friday: 50 + 30 = 80 km > Saturday (20 km)
        val rides = listOf(
            ride(id = 1, daysAgo = 1, startOdo = 100, distanceKm = 50),  // Fri
            ride(id = 2, daysAgo = 8, startOdo = 150, distanceKm = 30),  // Fri
            ride(id = 3, daysAgo = 0, startOdo = 180, distanceKm = 20),  // Sat
        )
        val result = WrappedAnalytics.compute(
            rides = rides,
            fills = emptyList(),
            avgKmPerL = null,
            window = year2025,
        )!!
        assertEquals("Fri", result.busiestWeekday.label)
        assertEquals(80, result.busiestWeekday.totalKm)
    }

    @Test fun busiestWeekdayLabelsAreCorrect() {
        // One ride per weekday; each covers different distance. Biggest = Mon.
        // Weekdays from anchor (Sat 2025-06-07):
        //   daysAgo = 5 → Mon 2025-06-02
        //   daysAgo = 4 → Tue 2025-06-03
        //   daysAgo = 3 → Wed 2025-06-04
        //   daysAgo = 2 → Thu 2025-06-05
        //   daysAgo = 1 → Fri 2025-06-06
        //   daysAgo = 0 → Sat 2025-06-07
        //   daysAgo = 6 → Sun 2025-06-01
        val days = listOf(
            5L to 100, // Mon 100 km
            4L to 10,  // Tue
            3L to 10,  // Wed
            2L to 10,  // Thu
            1L to 10,  // Fri
            0L to 10,  // Sat
            6L to 10,  // Sun
        )
        val rides = days.mapIndexed { idx, (daysAgo, km) ->
            ride(id = idx.toLong() + 1, daysAgo = daysAgo, startOdo = idx * 200, distanceKm = km)
        }
        val result = WrappedAnalytics.compute(
            rides = rides,
            fills = emptyList(),
            avgKmPerL = null,
            window = year2025,
        )!!
        assertEquals("Mon", result.busiestWeekday.label)
        assertEquals(100, result.busiestWeekday.totalKm)
    }

    // -------------------------------------------------------------------------
    // Tank stats — best and worst
    // -------------------------------------------------------------------------

    @Test fun tankStatsNullWhenFewerThanTwoFillsInWindow() {
        val f = fill(id = 1, daysAgo = 5, odometerKm = 1000, litres = 8.0)
        val r = ride(id = 1, daysAgo = 3, startOdo = 1000, distanceKm = 30)
        val result = WrappedAnalytics.compute(
            rides = listOf(r),
            fills = listOf(f),
            avgKmPerL = null,
            window = year2025,
        )!!
        assertNull(result.bestTank)
        assertNull(result.worstTank)
    }

    @Test fun tankStatsTwoFillsComputeOneInterval() {
        // 1000 -> 1360 = 360 km on 8 L = 45 km/L
        val fills = listOf(
            fill(id = 1, daysAgo = 20, odometerKm = 1000, litres = 9.0),
            fill(id = 2, daysAgo = 10, odometerKm = 1360, litres = 8.0),
        )
        val r = ride(id = 1, daysAgo = 5, startOdo = 1360, distanceKm = 20)
        val result = WrappedAnalytics.compute(
            rides = listOf(r),
            fills = fills,
            avgKmPerL = null,
            window = year2025,
        )!!
        assertNotNull(result.bestTank)
        assertNotNull(result.worstTank)
        // With only one interval, best and worst are the same
        assertEquals(45.0, result.bestTank!!.kmPerL, 0.001)
        assertEquals(45.0, result.worstTank!!.kmPerL, 0.001)
        assertEquals(2L, result.bestTank!!.fillId)
    }

    @Test fun tankStatsMultipleIntervalsBestAndWorst() {
        // Interval 1: 1000→1360 = 360 km / 8 L = 45.0 km/L
        // Interval 2: 1360→1560 = 200 km / 5 L = 40.0 km/L
        // Interval 3: 1560→1860 = 300 km / 6 L = 50.0 km/L
        val fills = listOf(
            fill(id = 1, daysAgo = 30, odometerKm = 1000, litres = 9.0),
            fill(id = 2, daysAgo = 20, odometerKm = 1360, litres = 8.0),
            fill(id = 3, daysAgo = 10, odometerKm = 1560, litres = 5.0),
            fill(id = 4, daysAgo = 3, odometerKm = 1860, litres = 6.0),
        )
        val r = ride(id = 1, daysAgo = 1, startOdo = 1860, distanceKm = 10)
        val result = WrappedAnalytics.compute(
            rides = listOf(r),
            fills = fills,
            avgKmPerL = null,
            window = year2025,
        )!!
        assertEquals(50.0, result.bestTank!!.kmPerL, 0.001)
        assertEquals(4L, result.bestTank!!.fillId)
        assertEquals(40.0, result.worstTank!!.kmPerL, 0.001)
        assertEquals(3L, result.worstTank!!.fillId)
    }

    @Test fun tankStatsSkipsZeroKmIntervals() {
        // Fill at same odometer → zero km → rejected
        val fills = listOf(
            fill(id = 1, daysAgo = 20, odometerKm = 1000, litres = 8.0),
            fill(id = 2, daysAgo = 10, odometerKm = 1000, litres = 5.0), // same odo
        )
        val r = ride(id = 1, daysAgo = 5, startOdo = 1000, distanceKm = 30)
        val result = WrappedAnalytics.compute(
            rides = listOf(r),
            fills = fills,
            avgKmPerL = null,
            window = year2025,
        )!!
        assertNull(result.bestTank)
        assertNull(result.worstTank)
    }

    @Test fun tankStatsOnlyWindowFillsConsidered() {
        // Fill in 2024 (outside window) should be excluded
        val oldFill = fill(id = 1, daysAgo = 400, odometerKm = 500, litres = 8.0)
        val newFill = fill(id = 2, daysAgo = 10, odometerKm = 1000, litres = 9.0)
        val r = ride(id = 1, daysAgo = 5, startOdo = 1000, distanceKm = 30)
        val result = WrappedAnalytics.compute(
            rides = listOf(r),
            fills = listOf(oldFill, newFill),
            avgKmPerL = null,
            window = year2025,
        )!!
        // Only one fill in window → no tank interval
        assertNull(result.bestTank)
        assertNull(result.worstTank)
    }

    // -------------------------------------------------------------------------
    // Litres burnt — estimate flag
    // -------------------------------------------------------------------------

    @Test fun litresBurntNullWhenNoKmPerL() {
        val r = ride(id = 1, daysAgo = 1, startOdo = 100, distanceKm = 50)
        val result = WrappedAnalytics.compute(
            rides = listOf(r),
            fills = emptyList(),
            avgKmPerL = null,
            window = year2025,
        )!!
        assertNull(result.litresBurnt)
    }

    @Test fun litresBurntComputedFromAvgKmPerL() {
        // 100 km / 50.0 km/L = 2.0 L
        val r = ride(id = 1, daysAgo = 1, startOdo = 0, distanceKm = 100)
        val result = WrappedAnalytics.compute(
            rides = listOf(r),
            fills = emptyList(),
            avgKmPerL = 50.0,
            window = year2025,
        )!!
        assertNotNull(result.litresBurnt)
        assertEquals(2.0, result.litresBurnt!!.litres, 0.001)
    }

    @Test fun litresBurntIsAlwaysEstimate() {
        // No matter how precise the km/L, the flag must be true (honesty rule)
        val r = ride(id = 1, daysAgo = 1, startOdo = 0, distanceKm = 80)
        val result = WrappedAnalytics.compute(
            rides = listOf(r),
            fills = emptyList(),
            avgKmPerL = 40.0,
            window = year2025,
        )!!
        assertTrue(result.litresBurnt!!.isEstimate)
    }

    @Test fun litresBurntNullWhenTotalKmIsZero() {
        // Rides all have 0 km (e.g. in-progress, excluded by filter — but even
        // if ended with 0 distance, litres should be null)
        val zeroKmRide = ride(id = 1, daysAgo = 1, startOdo = 100, distanceKm = 0)
        val result = WrappedAnalytics.compute(
            rides = listOf(zeroKmRide),
            fills = emptyList(),
            avgKmPerL = 45.0,
            window = year2025,
        )!!
        assertNull(result.litresBurnt)
    }

    @Test fun litresBurntNullWhenKmPerLIsZero() {
        val r = ride(id = 1, daysAgo = 1, startOdo = 0, distanceKm = 50)
        val result = WrappedAnalytics.compute(
            rides = listOf(r),
            fills = emptyList(),
            avgKmPerL = 0.0, // non-positive → rejected
            window = year2025,
        )!!
        assertNull(result.litresBurnt)
    }

    // -------------------------------------------------------------------------
    // Total spend — partial / complete
    // -------------------------------------------------------------------------

    @Test fun totalSpendZeroWhenNoFills() {
        val r = ride(id = 1, daysAgo = 1, startOdo = 100, distanceKm = 30)
        val result = WrappedAnalytics.compute(
            rides = listOf(r),
            fills = emptyList(),
            avgKmPerL = null,
            window = year2025,
        )!!
        assertEquals(0.0, result.totalSpend.rupees, 0.0)
        assertEquals(0, result.totalSpend.coveredFills)
        assertEquals(0, result.totalSpend.totalFills)
        assertTrue(result.totalSpend.isComplete) // 0 of 0 → vacuously complete
    }

    @Test fun totalSpendSumsNonNullRupees() {
        val fills = listOf(
            fill(id = 1, daysAgo = 15, odometerKm = 1000, litres = 8.0, rupees = 800.0),
            fill(id = 2, daysAgo = 10, odometerKm = 1360, litres = 7.5, rupees = 750.0),
        )
        val r = ride(id = 1, daysAgo = 5, startOdo = 1360, distanceKm = 30)
        val result = WrappedAnalytics.compute(
            rides = listOf(r),
            fills = fills,
            avgKmPerL = null,
            window = year2025,
        )!!
        assertEquals(1550.0, result.totalSpend.rupees, 0.001)
        assertEquals(2, result.totalSpend.coveredFills)
        assertEquals(2, result.totalSpend.totalFills)
        assertTrue(result.totalSpend.isComplete)
    }

    @Test fun totalSpendPartialWhenSomeFillsHaveNoRupees() {
        val fills = listOf(
            fill(id = 1, daysAgo = 15, odometerKm = 1000, litres = 8.0, rupees = 800.0),
            fill(id = 2, daysAgo = 10, odometerKm = 1360, litres = 7.5, rupees = null), // no price
        )
        val r = ride(id = 1, daysAgo = 5, startOdo = 1360, distanceKm = 30)
        val result = WrappedAnalytics.compute(
            rides = listOf(r),
            fills = fills,
            avgKmPerL = null,
            window = year2025,
        )!!
        assertEquals(800.0, result.totalSpend.rupees, 0.001)
        assertEquals(1, result.totalSpend.coveredFills)
        assertEquals(2, result.totalSpend.totalFills)
        assertFalse(result.totalSpend.isComplete)
    }

    @Test fun totalSpendIsCompleteReturnsFalseOnPartial() {
        val fills = listOf(
            fill(id = 1, daysAgo = 10, odometerKm = 1000, litres = 8.0, rupees = null),
        )
        val r = ride(id = 1, daysAgo = 5, startOdo = 1000, distanceKm = 20)
        val result = WrappedAnalytics.compute(
            rides = listOf(r),
            fills = fills,
            avgKmPerL = null,
            window = year2025,
        )!!
        assertEquals(0.0, result.totalSpend.rupees, 0.0)
        assertEquals(0, result.totalSpend.coveredFills)
        assertEquals(1, result.totalSpend.totalFills)
        assertFalse(result.totalSpend.isComplete)
    }

    // -------------------------------------------------------------------------
    // Longest streak within window
    // -------------------------------------------------------------------------

    @Test fun longestStreakZeroWhenNoRides() {
        // compute() returns null when no rides, so test a single-ride window
        val r = ride(id = 1, daysAgo = 5, startOdo = 100, distanceKm = 20)
        val result = WrappedAnalytics.compute(
            rides = listOf(r),
            fills = emptyList(),
            avgKmPerL = null,
            window = year2025,
        )!!
        assertEquals(1, result.longestStreak)
    }

    @Test fun longestStreakConsecutiveDays() {
        // Rides on day-5, day-4, day-3 = 3 consecutive days
        val rides = listOf(
            ride(id = 1, daysAgo = 5, startOdo = 100, distanceKm = 20),
            ride(id = 2, daysAgo = 4, startOdo = 120, distanceKm = 25),
            ride(id = 3, daysAgo = 3, startOdo = 145, distanceKm = 30),
        )
        val result = WrappedAnalytics.compute(
            rides = rides,
            fills = emptyList(),
            avgKmPerL = null,
            window = year2025,
        )!!
        assertEquals(3, result.longestStreak)
    }

    @Test fun longestStreakGapBreaksChain() {
        // day-5, day-4 (streak 2), gap at day-3, day-1 (streak 1) → longest = 2
        val rides = listOf(
            ride(id = 1, daysAgo = 5, startOdo = 100, distanceKm = 20),
            ride(id = 2, daysAgo = 4, startOdo = 120, distanceKm = 25),
            // gap at day-3
            ride(id = 3, daysAgo = 1, startOdo = 145, distanceKm = 10),
        )
        val result = WrappedAnalytics.compute(
            rides = rides,
            fills = emptyList(),
            avgKmPerL = null,
            window = year2025,
        )!!
        assertEquals(2, result.longestStreak)
    }

    @Test fun longestStreakMultipleRidesSameDayCountAsOne() {
        // Two rides on day-5, one on day-4 → streak = 2, not 3
        val rides = listOf(
            ride(id = 1, daysAgo = 5, startOdo = 100, distanceKm = 10),
            ride(id = 2, daysAgo = 5, startOdo = 110, distanceKm = 10),
            ride(id = 3, daysAgo = 4, startOdo = 120, distanceKm = 20),
        )
        val result = WrappedAnalytics.compute(
            rides = rides,
            fills = emptyList(),
            avgKmPerL = null,
            window = year2025,
        )!!
        assertEquals(2, result.longestStreak)
    }

    @Test fun longestStreakOutsideWindowNotCounted() {
        // A streak that starts before the window: only the portion inside counts
        // Window: 2025-01-01 .. 2025-12-31
        // We put rides at day-400 (outside 2025) + day-5 to make sure the
        // cross-window streak doesn't inflate the count
        val window = WrappedWindow.ofCalendarYear(2025, utc)
        val rides = listOf(
            // Outside window
            ride(id = 1, daysAgo = 400, startOdo = 0, distanceKm = 10),
            // Inside window — 2 consecutive
            ride(id = 2, daysAgo = 5, startOdo = 100, distanceKm = 20),
            ride(id = 3, daysAgo = 4, startOdo = 120, distanceKm = 20),
        )
        val result = WrappedAnalytics.compute(
            rides = rides,
            fills = emptyList(),
            avgKmPerL = null,
            window = window,
        )!!
        // The ride at day-400 is excluded from the windowed set → streak = 2
        assertEquals(2, result.longestStreak)
    }

    // -------------------------------------------------------------------------
    // Window boundary — inclusive edges
    // -------------------------------------------------------------------------

    @Test fun rideOnFirstDayOfWindowIsIncluded() {
        // Window: 2025-01-01 .. 2025-12-31
        // Ride exactly on 2025-01-01
        val jan1Millis = LocalDate.of(2025, 1, 1).atStartOfDay(utc).toInstant().toEpochMilli()
        val r = RideEntity(
            id = 1,
            startedAtMillis = jan1Millis,
            endedAtMillis = jan1Millis + 30 * 60_000L,
            startOdoKm = 100,
            endOdoKm = 130,
            maxSpeedKmh = 80,
            avgSpeedKmh = 50.0,
            sampleCount = 5,
            fuelBarsStart = 4,
            fuelBarsEnd = 3,
        )
        val result = WrappedAnalytics.compute(
            rides = listOf(r),
            fills = emptyList(),
            avgKmPerL = null,
            window = WrappedWindow.ofCalendarYear(2025, utc),
        )
        assertNotNull(result)
        assertEquals(30, result!!.totalKm)
    }

    @Test fun rideOnLastDayOfWindowIsIncluded() {
        val dec31Millis = LocalDate.of(2025, 12, 31).atStartOfDay(utc).toInstant().toEpochMilli()
        val r = RideEntity(
            id = 2,
            startedAtMillis = dec31Millis,
            endedAtMillis = dec31Millis + 45 * 60_000L,
            startOdoKm = 200,
            endOdoKm = 245,
            maxSpeedKmh = 90,
            avgSpeedKmh = 55.0,
            sampleCount = 8,
            fuelBarsStart = 5,
            fuelBarsEnd = 4,
        )
        val result = WrappedAnalytics.compute(
            rides = listOf(r),
            fills = emptyList(),
            avgKmPerL = null,
            window = WrappedWindow.ofCalendarYear(2025, utc),
        )
        assertNotNull(result)
        assertEquals(45, result!!.totalKm)
    }

    @Test fun rideOneMillisBeforeWindowIsExcluded() {
        // 2024-12-31T23:59:59.999Z → still 2024 in UTC → outside 2025 window
        val lastMsOf2024 = LocalDate.of(2025, 1, 1).atStartOfDay(utc).toInstant().toEpochMilli() - 1L
        val r = RideEntity(
            id = 3,
            startedAtMillis = lastMsOf2024,
            endedAtMillis = lastMsOf2024 + 30 * 60_000L,
            startOdoKm = 50,
            endOdoKm = 80,
            maxSpeedKmh = 70,
            avgSpeedKmh = 45.0,
            sampleCount = 4,
            fuelBarsStart = 3,
            fuelBarsEnd = 3,
        )
        val result = WrappedAnalytics.compute(
            rides = listOf(r),
            fills = emptyList(),
            avgKmPerL = null,
            window = WrappedWindow.ofCalendarYear(2025, utc),
        )
        assertNull(result)
    }

    // -------------------------------------------------------------------------
    // SpendTotal.isComplete helper
    // -------------------------------------------------------------------------

    @Test fun spendIsCompleteWhenNoBothZero() {
        val s = SpendTotal(rupees = 0.0, coveredFills = 0, totalFills = 0)
        assertTrue(s.isComplete)
    }

    @Test fun spendIsNotCompleteWhenPartial() {
        val s = SpendTotal(rupees = 500.0, coveredFills = 1, totalFills = 3)
        assertFalse(s.isComplete)
    }

    @Test fun spendIsCompleteWhenAllCovered() {
        val s = SpendTotal(rupees = 1500.0, coveredFills = 3, totalFills = 3)
        assertTrue(s.isComplete)
    }

    // -------------------------------------------------------------------------
    // Window field is preserved in result
    // -------------------------------------------------------------------------

    @Test fun resultPreservesWindow() {
        val r = ride(id = 1, daysAgo = 1, startOdo = 0, distanceKm = 10)
        val result = WrappedAnalytics.compute(
            rides = listOf(r),
            fills = emptyList(),
            avgKmPerL = null,
            window = year2025,
        )!!
        assertEquals(year2025, result.window)
    }
}
