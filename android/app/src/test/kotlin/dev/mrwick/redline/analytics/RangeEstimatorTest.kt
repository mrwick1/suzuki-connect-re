package dev.mrwick.redline.analytics

import dev.mrwick.redline.data.RideEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-JVM tests for [RangeEstimator]. No Room, no Android. */
class RangeEstimatorTest {

    private fun ride(
        id: Long = 1,
        startOdo: Int,
        endOdo: Int?,
        barsStart: Int?,
        barsEnd: Int?,
    ): RideEntity = RideEntity(
        id = id,
        startedAtMillis = 0L,
        endedAtMillis = endOdo?.let { 1L },
        startOdoKm = startOdo,
        endOdoKm = endOdo,
        maxSpeedKmh = 0,
        avgSpeedKmh = 0.0,
        sampleCount = 0,
        fuelBarsStart = barsStart,
        fuelBarsEnd = barsEnd,
    )

    @Test fun `kmPerBar empty list returns null`() {
        assertNull(RangeEstimator.kmPerBar(emptyList()))
    }

    @Test fun `kmPerBar single ride returns km divided by bars used`() {
        // 100 km over 2 bars = 50 km/bar
        val rides = listOf(ride(startOdo = 1000, endOdo = 1100, barsStart = 6, barsEnd = 4))
        assertEquals(50.0, RangeEstimator.kmPerBar(rides)!!, 1e-9)
    }

    @Test fun `kmPerBar skips rides missing endOdo or fuel data`() {
        val rides = listOf(
            ride(id = 1, startOdo = 0, endOdo = null, barsStart = 6, barsEnd = 4),       // no end odo
            ride(id = 2, startOdo = 0, endOdo = 100, barsStart = null, barsEnd = 4),     // no barsStart
            ride(id = 3, startOdo = 0, endOdo = 100, barsStart = 6, barsEnd = null),     // no barsEnd
            ride(id = 4, startOdo = 0, endOdo = 80, barsStart = 6, barsEnd = 4),         // good: 40 km/bar
        )
        assertEquals(40.0, RangeEstimator.kmPerBar(rides)!!, 1e-9)
    }

    @Test fun `kmPerBar skips rides with non-positive km or bars used`() {
        val rides = listOf(
            ride(id = 1, startOdo = 100, endOdo = 100, barsStart = 6, barsEnd = 4), // 0 km
            ride(id = 2, startOdo = 100, endOdo = 200, barsStart = 4, barsEnd = 4), // 0 bars used
            ride(id = 3, startOdo = 100, endOdo = 200, barsStart = 4, barsEnd = 5), // refuel mid-ride
            ride(id = 4, startOdo = 0, endOdo = 60, barsStart = 6, barsEnd = 4),    // good: 30
        )
        assertEquals(30.0, RangeEstimator.kmPerBar(rides)!!, 1e-9)
    }

    @Test fun `kmPerBar returns median across multiple rides`() {
        // samples: 30, 40, 50, 60, 70 → median = 50
        val rides = listOf(
            ride(id = 1, startOdo = 0, endOdo = 60, barsStart = 6, barsEnd = 4),    // 30
            ride(id = 2, startOdo = 0, endOdo = 80, barsStart = 6, barsEnd = 4),    // 40
            ride(id = 3, startOdo = 0, endOdo = 100, barsStart = 6, barsEnd = 4),   // 50
            ride(id = 4, startOdo = 0, endOdo = 120, barsStart = 6, barsEnd = 4),   // 60
            ride(id = 5, startOdo = 0, endOdo = 140, barsStart = 6, barsEnd = 4),   // 70
        )
        assertEquals(50.0, RangeEstimator.kmPerBar(rides)!!, 1e-9)
    }

    @Test fun `estimateRemainingKm null inputs return null`() {
        assertNull(RangeEstimator.estimateRemainingKm(null, 50.0))
        assertNull(RangeEstimator.estimateRemainingKm(3, null))
        assertNull(RangeEstimator.estimateRemainingKm(null, null))
    }

    @Test fun `estimateRemainingKm non-positive inputs return null`() {
        assertNull(RangeEstimator.estimateRemainingKm(0, 50.0))
        assertNull(RangeEstimator.estimateRemainingKm(-1, 50.0))
        assertNull(RangeEstimator.estimateRemainingKm(3, 0.0))
        assertNull(RangeEstimator.estimateRemainingKm(3, -10.0))
    }

    @Test fun `estimateRemainingKm valid inputs return bars times kmPerBar`() {
        val result = RangeEstimator.estimateRemainingKm(3, 45.0)
        assertNotNull(result)
        assertEquals(135.0, result!!, 1e-9)
    }
}
