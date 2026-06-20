package dev.mrwick.redline.analytics

import dev.mrwick.redline.data.RideLocationEntity
import dev.mrwick.redline.data.RideSampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [SpeedTrack].
 *
 * No Room, no Android — hand-crafted [RideLocationEntity] / [RideSampleEntity]
 * with explicit tMillis values so the nearest-timestamp join is deterministic.
 */
class SpeedTrackTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun loc(tMillis: Long, lat: Double, lng: Double): RideLocationEntity =
        RideLocationEntity(
            id = 0,
            rideId = 1,
            tMillis = tMillis,
            lat = lat,
            lng = lng,
            altitudeM = null,
            accuracyM = null,
        )

    private fun sample(tMillis: Long, speedKmh: Int): RideSampleEntity =
        RideSampleEntity(
            id = 0,
            rideId = 1,
            tMillis = tMillis,
            speedKmh = speedKmh,
            odometerKm = 0,
            tripAKm = 0.0,
            tripBKm = 0.0,
            fuelBars = null,
            fuelEconKml = null,
        )

    // -----------------------------------------------------------------------
    // Edge cases: insufficient input
    // -----------------------------------------------------------------------

    @Test fun `empty locations returns empty list`() {
        val result = SpeedTrack.build(emptyList(), emptyList())
        assertTrue(result.isEmpty())
    }

    @Test fun `single location returns empty list`() {
        val result = SpeedTrack.build(
            locations = listOf(loc(1000L, 12.0, 77.0)),
            samples = listOf(sample(1000L, 50)),
        )
        assertTrue(result.isEmpty())
    }

    @Test fun `two locations with no samples returns one cool segment`() {
        val result = SpeedTrack.build(
            locations = listOf(loc(1000L, 12.0, 77.0), loc(2000L, 12.1, 77.1)),
            samples = emptyList(),
        )
        assertEquals(1, result.size)
        assertEquals(SpeedTrackColors.COLOR_COOL, result[0].colorArgb)
        assertEquals(0, result[0].speedKmh)
    }

    // -----------------------------------------------------------------------
    // Segment count
    // -----------------------------------------------------------------------

    @Test fun `N locations produce N-1 segments`() {
        val locs = (0..9).map { i -> loc(i * 1000L, i.toDouble(), i.toDouble()) }
        val result = SpeedTrack.build(locs, emptyList())
        assertEquals(9, result.size)
    }

    // -----------------------------------------------------------------------
    // Coordinate copying
    // -----------------------------------------------------------------------

    @Test fun `segment start and end coords match location pairs`() {
        val l1 = loc(1000L, 12.0, 77.5)
        val l2 = loc(2000L, 12.3, 77.8)
        val l3 = loc(3000L, 12.6, 78.1)
        val result = SpeedTrack.build(listOf(l1, l2, l3), emptyList())

        assertEquals(2, result.size)

        assertEquals(12.0, result[0].startLat, 1e-9)
        assertEquals(77.5, result[0].startLng, 1e-9)
        assertEquals(12.3, result[0].endLat, 1e-9)
        assertEquals(77.8, result[0].endLng, 1e-9)

        assertEquals(12.3, result[1].startLat, 1e-9)
        assertEquals(77.8, result[1].startLng, 1e-9)
        assertEquals(12.6, result[1].endLat, 1e-9)
        assertEquals(78.1, result[1].endLng, 1e-9)
    }

    // -----------------------------------------------------------------------
    // Nearest-timestamp join
    // -----------------------------------------------------------------------

    @Test fun `segment uses speed from nearest-timestamp sample`() {
        // Location at t=5000; samples at t=4000 (speed=20) and t=6000 (speed=90).
        // 5000 is equidistant, so the first (t=4000) should win — same delta.
        val locs = listOf(loc(1000L, 0.0, 0.0), loc(5000L, 0.1, 0.1))
        val samples = listOf(sample(4000L, 20), sample(6000L, 90))
        val result = SpeedTrack.build(locs, samples)

        assertEquals(1, result.size)
        // delta to t=4000 is 1000ms; delta to t=6000 is 1000ms → equidistant → first wins
        assertEquals(20, result[0].speedKmh)
    }

    @Test fun `segment uses closest sample when one is clearly nearer`() {
        // Location at t=5500; samples at t=3000 (speed=15, delta=2500) and t=5400 (speed=75, delta=100).
        val locs = listOf(loc(1000L, 0.0, 0.0), loc(5500L, 0.1, 0.1))
        val samples = listOf(sample(3000L, 15), sample(5400L, 75))
        val result = SpeedTrack.build(locs, samples)

        assertEquals(75, result[0].speedKmh)
    }

    @Test fun `each segment independently picks its nearest sample`() {
        // Three locations at t=0, 1000, 5000.
        // Samples: t=900 speed=30 (nearest to loc[1] at t=1000),
        //          t=4800 speed=110 (nearest to loc[2] at t=5000).
        val locs = listOf(
            loc(0L,    0.0, 0.0),
            loc(1000L, 0.1, 0.1),
            loc(5000L, 0.2, 0.2),
        )
        val samples = listOf(
            sample(900L,  30),
            sample(4800L, 110),
        )
        val result = SpeedTrack.build(locs, samples)

        assertEquals(2, result.size)
        // Segment 0 end = loc[1] at t=1000; nearest sample is t=900 (delta=100) vs t=4800 (delta=3800).
        assertEquals(30, result[0].speedKmh)
        // Segment 1 end = loc[2] at t=5000; nearest sample is t=4800 (delta=200) vs t=900 (delta=4100).
        assertEquals(110, result[1].speedKmh)
    }

    // -----------------------------------------------------------------------
    // Color assignment via SpeedTrackColors
    // -----------------------------------------------------------------------

    @Test fun `cool speed produces COLOR_COOL`() {
        val locs = listOf(loc(0L, 0.0, 0.0), loc(1000L, 0.1, 0.1))
        val samples = listOf(sample(1000L, 30)) // 30 < 60 → cool
        val result = SpeedTrack.build(locs, samples)
        assertEquals(SpeedTrackColors.COLOR_COOL, result[0].colorArgb)
    }

    @Test fun `mid speed produces COLOR_MID`() {
        val locs = listOf(loc(0L, 0.0, 0.0), loc(1000L, 0.1, 0.1))
        val samples = listOf(sample(1000L, 80)) // 60 ≤ 80 < 100 → mid
        val result = SpeedTrack.build(locs, samples)
        assertEquals(SpeedTrackColors.COLOR_MID, result[0].colorArgb)
    }

    @Test fun `hot speed produces COLOR_HOT`() {
        val locs = listOf(loc(0L, 0.0, 0.0), loc(1000L, 0.1, 0.1))
        val samples = listOf(sample(1000L, 110)) // 110 ≥ 100 → hot
        val result = SpeedTrack.build(locs, samples)
        assertEquals(SpeedTrackColors.COLOR_HOT, result[0].colorArgb)
    }

    @Test fun `threshold exact values map to correct zones`() {
        fun segmentColor(speed: Int): Int {
            val locs = listOf(loc(0L, 0.0, 0.0), loc(1000L, 0.1, 0.1))
            val samples = listOf(sample(1000L, speed))
            return SpeedTrack.build(locs, samples)[0].colorArgb
        }

        assertEquals(SpeedTrackColors.COLOR_COOL, segmentColor(59))  // just below cool→mid
        assertEquals(SpeedTrackColors.COLOR_MID,  segmentColor(60))  // at cool→mid
        assertEquals(SpeedTrackColors.COLOR_MID,  segmentColor(99))  // just below mid→hot
        assertEquals(SpeedTrackColors.COLOR_HOT,  segmentColor(100)) // at mid→hot
    }

    // -----------------------------------------------------------------------
    // Mixed-zone ride
    // -----------------------------------------------------------------------

    @Test fun `multi-segment ride shows correct zone sequence`() {
        // Simulate a ride: slow start → spirited → near-redline
        val locs = listOf(
            loc(0L,    12.900, 77.600),
            loc(5000L, 12.905, 77.605),
            loc(10000L, 12.910, 77.610),
            loc(15000L, 12.915, 77.615),
        )
        val samples = listOf(
            sample(5000L,  20),  // cool
            sample(10000L, 75),  // mid
            sample(15000L, 115), // hot
        )
        val result = SpeedTrack.build(locs, samples)

        assertEquals(3, result.size)
        assertEquals(SpeedTrackColors.COLOR_COOL, result[0].colorArgb)
        assertEquals(SpeedTrackColors.COLOR_MID,  result[1].colorArgb)
        assertEquals(SpeedTrackColors.COLOR_HOT,  result[2].colorArgb)
    }

    // -----------------------------------------------------------------------
    // speedKmh field is carried through
    // -----------------------------------------------------------------------

    @Test fun `segment carries the raw speed value`() {
        val locs = listOf(loc(0L, 0.0, 0.0), loc(1000L, 0.1, 0.1))
        val samples = listOf(sample(1000L, 83))
        val result = SpeedTrack.build(locs, samples)
        assertEquals(83, result[0].speedKmh)
    }
}
