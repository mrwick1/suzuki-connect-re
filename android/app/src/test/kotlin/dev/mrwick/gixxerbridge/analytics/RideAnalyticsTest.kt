package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.RideEntity
import dev.mrwick.gixxerbridge.data.RideSampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Pure-JVM tests for [RideAnalytics]. No Room, no Android — just hand-crafted
 * [RideEntity] / [RideSampleEntity] inputs and fixed timestamps.
 *
 * "Now" is fixed to 2025-06-15T12:00:00Z so the rolling-window tests don't
 * depend on the wall clock.
 */
class RideAnalyticsTest {

    private val nowMillis: Long = 1_750_000_000_000L // ~ 2025-06-15
    private val zoneUtc: ZoneId = ZoneOffset.UTC

    private fun ride(
        id: Long,
        startedDaysAgo: Long,
        durationMinutes: Long,
        startOdo: Int,
        distanceKm: Int,
        maxSpeed: Int = 60,
        avgSpeed: Double = 35.0,
    ): RideEntity {
        val startedAt = nowMillis - startedDaysAgo * 86_400_000L
        return RideEntity(
            id = id,
            startedAtMillis = startedAt,
            endedAtMillis = startedAt + durationMinutes * 60_000L,
            startOdoKm = startOdo,
            endOdoKm = startOdo + distanceKm,
            maxSpeedKmh = maxSpeed,
            avgSpeedKmh = avgSpeed,
            sampleCount = 10,
            fuelBarsStart = 4,
            fuelBarsEnd = 3,
        )
    }

    private fun sample(rideId: Long, speed: Int, fuelEcon: Double? = null): RideSampleEntity =
        RideSampleEntity(
            id = 0,
            rideId = rideId,
            tMillis = nowMillis,
            speedKmh = speed,
            odometerKm = 1000,
            tripAKm = 0.0,
            tripBKm = 0.0,
            fuelBars = 4,
            fuelEconKml = fuelEcon,
        )

    // ---------- totalsFor ----------

    @Test fun totalsForEmptyIsZero() {
        val t = RideAnalytics.totalsFor(emptyList(), days = 7, now = nowMillis)
        assertEquals(WeeklyTotal(0, 0.0, 0), t)
    }

    @Test fun totalsForFiltersByWindow() {
        val rides = listOf(
            ride(1, startedDaysAgo = 2, durationMinutes = 60, startOdo = 100, distanceKm = 25),
            ride(2, startedDaysAgo = 5, durationMinutes = 30, startOdo = 125, distanceKm = 10),
            // Outside the 7-day window:
            ride(3, startedDaysAgo = 10, durationMinutes = 90, startOdo = 200, distanceKm = 50),
        )
        val t = RideAnalytics.totalsFor(rides, days = 7, now = nowMillis)
        assertEquals(2, t.rides)
        assertEquals(35, t.km)
        assertEquals(1.5, t.hours, 0.001)
    }

    @Test fun totalsForInProgressRideContributesZeroKm() {
        val inProgress = RideEntity(
            id = 1,
            startedAtMillis = nowMillis - 30 * 60_000L,
            endedAtMillis = null,
            startOdoKm = 500,
            endOdoKm = null,
            maxSpeedKmh = 50,
            avgSpeedKmh = 30.0,
            sampleCount = 5,
            fuelBarsStart = 4,
            fuelBarsEnd = null,
        )
        val t = RideAnalytics.totalsFor(listOf(inProgress), days = 7, now = nowMillis)
        assertEquals(1, t.rides)
        assertEquals(0, t.km)
        assertEquals(0.0, t.hours, 0.001)
    }

    // ---------- speedHistogram ----------

    @Test fun speedHistogramEmptyHasZeroCounts() {
        val h = RideAnalytics.speedHistogram(emptyList())
        assertEquals(15, h.size) // 150 / 10
        assertTrue(h.all { it.sampleCount == 0 })
        assertEquals(0, h[0].lowKmh)
        assertEquals(10, h[0].highKmh)
    }

    @Test fun speedHistogramBucketsByTen() {
        val samples = listOf(
            sample(1, 0),   // bucket 0-10
            sample(1, 9),   // bucket 0-10
            sample(1, 10),  // bucket 10-20
            sample(1, 15),  // bucket 10-20
            sample(1, 19),  // bucket 10-20
            sample(1, 55),  // bucket 50-60
        )
        val h = RideAnalytics.speedHistogram(samples, bucketSizeKmh = 10, maxKmh = 60)
        assertEquals(6, h.size)
        assertEquals(2, h[0].sampleCount)
        assertEquals(3, h[1].sampleCount)
        assertEquals(0, h[2].sampleCount)
        assertEquals(1, h[5].sampleCount)
    }

    @Test fun speedHistogramClampsOverMax() {
        val samples = listOf(sample(1, 200), sample(1, 500))
        val h = RideAnalytics.speedHistogram(samples, bucketSizeKmh = 10, maxKmh = 100)
        assertEquals(2, h.last().sampleCount)
    }

    // ---------- calendarMap ----------

    @Test fun calendarMapHasExactlyWeeksTimesSevenDays() {
        val days = RideAnalytics.calendarMap(emptyList(), weeks = 12, now = nowMillis, zone = zoneUtc)
        assertEquals(84, days.size)
    }

    @Test fun calendarMapLastDayIsToday() {
        val days = RideAnalytics.calendarMap(emptyList(), weeks = 4, now = nowMillis, zone = zoneUtc)
        val expectedToday = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), zoneUtc).toEpochDay()
        assertEquals(expectedToday, days.last().epochDay)
    }

    @Test fun calendarMapSumsKmPerStartDay() {
        val today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), zoneUtc)
        val rides = listOf(
            ride(1, startedDaysAgo = 0, durationMinutes = 30, startOdo = 100, distanceKm = 20),
            ride(2, startedDaysAgo = 0, durationMinutes = 15, startOdo = 120, distanceKm = 5),
            ride(3, startedDaysAgo = 3, durationMinutes = 45, startOdo = 200, distanceKm = 40),
        )
        val days = RideAnalytics.calendarMap(rides, weeks = 2, now = nowMillis, zone = zoneUtc)
        val byEpoch = days.associateBy { it.epochDay }
        assertEquals(25, byEpoch[today.toEpochDay()]!!.km)
        assertEquals(40, byEpoch[today.minusDays(3).toEpochDay()]!!.km)
        assertEquals(0, byEpoch[today.minusDays(1).toEpochDay()]!!.km)
    }

    // ---------- personalBests ----------

    @Test fun personalBestsEmptyIsAllNull() {
        val pb = RideAnalytics.personalBests(emptyList(), emptyList())
        assertNull(pb.longestRideKm)
        assertNull(pb.topSpeedKmh)
        assertNull(pb.bestFuelEconKml)
        assertNull(pb.mostRidesInDay)
    }

    @Test fun personalBestsFindsMaxima() {
        val rides = listOf(
            ride(1, startedDaysAgo = 0, durationMinutes = 30, startOdo = 100, distanceKm = 20, maxSpeed = 90),
            ride(2, startedDaysAgo = 0, durationMinutes = 30, startOdo = 120, distanceKm = 5, maxSpeed = 50),
            ride(3, startedDaysAgo = 1, durationMinutes = 90, startOdo = 200, distanceKm = 80, maxSpeed = 110),
        )
        val samples = listOf(
            sample(1, 60, fuelEcon = 40.0),
            sample(3, 90, fuelEcon = 55.5),
            sample(3, 100, fuelEcon = null),
        )
        val pb = RideAnalytics.personalBests(rides, samples)
        assertEquals(80, pb.longestRideKm)
        assertEquals(110, pb.topSpeedKmh)
        assertEquals(55.5, pb.bestFuelEconKml!!, 0.001)
        assertEquals(2, pb.mostRidesInDay)
    }

    @Test fun personalBestsHandlesAllNullFuelEcon() {
        val rides = listOf(ride(1, startedDaysAgo = 0, durationMinutes = 30, startOdo = 100, distanceKm = 20))
        val samples = listOf(sample(1, 50, fuelEcon = null))
        val pb = RideAnalytics.personalBests(rides, samples)
        assertNull(pb.bestFuelEconKml)
    }

    // ---------- summarize ----------

    @Test fun summarizePullsLatestNonNullFuelEcon() {
        val r = ride(7, startedDaysAgo = 1, durationMinutes = 45, startOdo = 300, distanceKm = 18, maxSpeed = 85, avgSpeed = 42.5)
        val samples = listOf(
            sample(7, 40, fuelEcon = 38.0),
            sample(7, 60, fuelEcon = 42.0),
            sample(7, 70, fuelEcon = null),
        )
        val s = RideAnalytics.summarize(r, samples)
        assertEquals(7L, s.rideId)
        assertEquals(18, s.km)
        assertEquals(85, s.maxSpeed)
        assertEquals(42.5, s.avgSpeed, 0.001)
        assertEquals(42.0, s.fuelEconKml!!, 0.001)
    }

    @Test fun summarizeFuelEconIsNullWhenAllSamplesNull() {
        val r = ride(7, startedDaysAgo = 1, durationMinutes = 45, startOdo = 300, distanceKm = 18)
        val samples = listOf(sample(7, 40, fuelEcon = null))
        val s = RideAnalytics.summarize(r, samples)
        assertNull(s.fuelEconKml)
    }

    @Test fun summarizeInProgressRideHasZeroKm() {
        val inProgress = RideEntity(
            id = 9,
            startedAtMillis = nowMillis,
            endedAtMillis = null,
            startOdoKm = 500,
            endOdoKm = null,
            maxSpeedKmh = 0,
            avgSpeedKmh = 0.0,
            sampleCount = 0,
            fuelBarsStart = 4,
            fuelBarsEnd = null,
        )
        val s = RideAnalytics.summarize(inProgress, emptyList())
        assertEquals(0, s.km)
        assertNull(s.fuelEconKml)
    }

    // ---------- avgBikeEcon ----------

    @Test fun avgBikeEconIgnoresNullAndNonPositive() {
        val samples = listOf(
            sample(1, 40, fuelEcon = 30.0),
            sample(1, 50, fuelEcon = null),
            sample(1, 0, fuelEcon = 0.0),
            sample(1, 30, fuelEcon = -5.0),
            sample(1, 60, fuelEcon = 50.0),
        )
        assertEquals(40.0, RideAnalytics.avgBikeEcon(samples)!!, 1e-9)
    }

    @Test fun avgBikeEconNullWhenNoUsableReadings() {
        assertNull(RideAnalytics.avgBikeEcon(listOf(sample(1, 40, fuelEcon = null))))
        assertNull(RideAnalytics.avgBikeEcon(emptyList()))
    }

    // ---------- fuelBurnt ----------

    @Test fun fuelBurntPrefersFillsOverBike() {
        val burn = RideAnalytics.fuelBurnt(distanceKm = 100, fillKmPerL = 50.0, bikeKmPerL = 40.0)!!
        assertEquals(2.0, burn.litres, 1e-9)
        assertEquals(FuelBurnSource.FILLS, burn.source)
    }

    @Test fun fuelBurntFallsBackToBikeWhenNoFills() {
        val burn = RideAnalytics.fuelBurnt(distanceKm = 80, fillKmPerL = null, bikeKmPerL = 40.0)!!
        assertEquals(2.0, burn.litres, 1e-9)
        assertEquals(FuelBurnSource.BIKE, burn.source)
    }

    @Test fun fuelBurntNullWhenNoSourceOrNoDistance() {
        assertNull(RideAnalytics.fuelBurnt(100, null, null))
        assertNull(RideAnalytics.fuelBurnt(0, 50.0, 40.0))
        assertNull(RideAnalytics.fuelBurnt(100, 0.0, 0.0))
    }
}
