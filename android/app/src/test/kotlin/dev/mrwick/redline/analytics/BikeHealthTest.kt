package dev.mrwick.redline.analytics

import dev.mrwick.redline.data.RideEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [BikeHealth.compute]. No Android, no Room. */
class BikeHealthTest {

    private val now = 1_750_000_000_000L

    private fun ride(daysAgo: Long): RideEntity = RideEntity(
        id = 0,
        startedAtMillis = now - daysAgo * 86_400_000L,
        endedAtMillis = null,
        startOdoKm = 0,
        endOdoKm = null,
        maxSpeedKmh = 0,
        avgSpeedKmh = 0.0,
        sampleCount = 0,
        fuelBarsStart = null,
        fuelBarsEnd = null,
    )

    @Test fun freshlyServicedFullTankRiddenTodayIsPerfect() {
        val s = BikeHealth.compute(
            currentOdo = 1000,
            lastServiceOdo = 1000,
            serviceIntervalKm = 5000,
            fuelBars = 6,
            rides = listOf(ride(0)),
            now = now,
        )
        assertEquals(100, s.service)
        assertEquals(100, s.fuel)
        assertEquals(100, s.connection)
        // weights sum to 1.00 within rounding -> 99 or 100
        assertTrue("expected total ~100, got ${s.total}", s.total in 99..100)
        assertEquals("Excellent", s.grade)
    }

    @Test fun dueForServiceScoresFiftyAtExactInterval() {
        val s = BikeHealth.compute(
            currentOdo = 6000,
            lastServiceOdo = 1000,
            serviceIntervalKm = 5000,
            fuelBars = 6,
            rides = listOf(ride(0)),
            now = now,
        )
        // sinceService == interval -> ratio 1.0 -> (1 - 0.5) * 100 = 50
        assertEquals(50, s.service)
    }

    @Test fun longOverdueServiceFloorsToZero() {
        val s = BikeHealth.compute(
            currentOdo = 20000,
            lastServiceOdo = 1000,
            serviceIntervalKm = 5000,
            fuelBars = 3,
            rides = listOf(ride(0)),
            now = now,
        )
        assertEquals(0, s.service)
    }

    @Test fun unknownOdoAndFuelGiveNeutralFifty() {
        val s = BikeHealth.compute(
            currentOdo = null,
            lastServiceOdo = 0,
            serviceIntervalKm = 5000,
            fuelBars = null,
            rides = listOf(ride(0)),
            now = now,
        )
        assertEquals(50, s.service)
        assertEquals(50, s.fuel)
        assertEquals(100, s.connection)
    }

    @Test fun noRidesAtAllZerosConnection() {
        val s = BikeHealth.compute(
            currentOdo = 1000,
            lastServiceOdo = 1000,
            serviceIntervalKm = 5000,
            fuelBars = 6,
            rides = emptyList(),
            now = now,
        )
        assertEquals(0, s.connection)
        // service+fuel weights = 0.67, both at 100 -> total ~67
        assertTrue("expected total ~67, got ${s.total}", s.total in 65..68)
    }

    @Test fun connectionDecaysLinearlyOverSevenDays() {
        val s = BikeHealth.compute(
            currentOdo = 1000,
            lastServiceOdo = 1000,
            serviceIntervalKm = 5000,
            fuelBars = 6,
            rides = listOf(ride(daysAgo = 7)),
            now = now,
        )
        assertEquals(0, s.connection)
    }

    @Test fun fuelBarsMapLinearly() {
        val three = BikeHealth.compute(
            currentOdo = 1000, lastServiceOdo = 1000, serviceIntervalKm = 5000,
            fuelBars = 3, rides = listOf(ride(0)), now = now,
        )
        val zero = BikeHealth.compute(
            currentOdo = 1000, lastServiceOdo = 1000, serviceIntervalKm = 5000,
            fuelBars = 0, rides = listOf(ride(0)), now = now,
        )
        assertEquals(50, three.fuel)
        assertEquals(0, zero.fuel)
    }

    @Test fun gradeBucketsMatchBoundaries() {
        fun grade(total: Int) = BikeHealthScore(total, 0, 0, 0).grade
        assertEquals("Excellent", grade(85))
        assertEquals("Good", grade(65))
        assertEquals("Fair", grade(40))
        assertEquals("Needs attention", grade(39))
    }
}
