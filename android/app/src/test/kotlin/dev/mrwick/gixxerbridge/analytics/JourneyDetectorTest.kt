package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.RideEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyDetectorTest {

    private val cfg = JourneyConfig(gapMaxMin = 120, minSegments = 3, minTotalKm = 80)

    /** Build an ended ride. Times in minutes-from-zero for readability. */
    private fun ride(id: Long, startMin: Long, endMin: Long, startOdo: Int, endOdo: Int) =
        RideEntity(
            id = id,
            startedAtMillis = startMin * 60_000L,
            endedAtMillis = endMin * 60_000L,
            startOdoKm = startOdo, endOdoKm = endOdo,
            maxSpeedKmh = 0, avgSpeedKmh = 0.0, sampleCount = 0,
            fuelBarsStart = null, fuelBarsEnd = null,
        )

    @Test fun detectsLongContiguousJourney() {
        // 4 segments, gaps 15/15/15 min, odo 0→120 (120 km)
        val rides = listOf(
            ride(1, 0, 30, 0, 40),
            ride(2, 45, 75, 40, 70),
            ride(3, 90, 120, 70, 100),
            ride(4, 135, 165, 100, 120),
        )
        val s = JourneyDetector.detect(rides, cfg)
        assertEquals(1, s.size)
        assertEquals(listOf(1L, 2L, 3L, 4L), s[0].rideIds)
        assertEquals(120, s[0].totalKm)
        assertEquals(0L, s[0].startMillis)
    }

    @Test fun ignoresShortErrandDay() {
        // 4 segments, small gaps but only 30 km total → below minTotalKm
        val rides = listOf(
            ride(1, 0, 10, 0, 8),
            ride(2, 20, 30, 8, 15),
            ride(3, 40, 50, 15, 22),
            ride(4, 60, 70, 22, 30),
        )
        assertTrue(JourneyDetector.detect(rides, cfg).isEmpty())
    }

    @Test fun splitsRunAtLargeGap() {
        // first 3 chain (0→90), then a 3h gap, then 2 more — only the first run
        // qualifies (90km, 3 segs); second run is 2 segs → rejected by minSegments
        val rides = listOf(
            ride(1, 0, 30, 0, 30),
            ride(2, 45, 75, 30, 60),
            ride(3, 90, 120, 60, 90),
            ride(4, 300, 330, 90, 120),
            ride(5, 345, 375, 120, 150),
        )
        val s = JourneyDetector.detect(rides, cfg)
        assertEquals(1, s.size)
        assertEquals(listOf(1L, 2L, 3L), s[0].rideIds)
    }

    @Test fun breaksRunWhenOdometerDoesNotChain() {
        // gap in odometer between seg 2 and 3 (60 != 65) splits the run
        val rides = listOf(
            ride(1, 0, 30, 0, 30),
            ride(2, 45, 75, 30, 60),
            ride(3, 90, 120, 65, 95),
            ride(4, 135, 165, 95, 125),
        )
        // run1 = {1,2} (2 segs, rejected); run2 = {3,4} (2 segs, rejected)
        assertTrue(JourneyDetector.detect(rides, cfg).isEmpty())
    }

    @Test fun gapHintFormatting() {
        assertEquals("15 min later", gapHintLabel(15))
        assertEquals("1 h 5 min later", gapHintLabel(65))
        assertEquals("2 h later", gapHintLabel(120))
    }

    @Test fun excludesAlreadyMergedRideFromDetection() {
        // A merged parent (isMerged=true) sitting among normal rides must not be
        // pulled into a run — it is already a journey. With seg 3 excluded the
        // chain breaks (2 ends odo 60, 4 starts odo 90), leaving runs {1,2} and
        // {4}, neither of which reaches minSegments=3.
        val rides = listOf(
            ride(1, 0, 30, 0, 30),
            ride(2, 45, 75, 30, 60),
            ride(3, 90, 120, 60, 90).copy(isMerged = true),
            ride(4, 135, 165, 90, 120),
        )
        assertTrue(JourneyDetector.detect(rides, cfg).isEmpty())
    }
}
