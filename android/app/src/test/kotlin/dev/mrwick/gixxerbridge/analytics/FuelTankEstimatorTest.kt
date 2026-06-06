package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.FuelFillEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [FuelTankEstimator]. No Room, no Android. */
class FuelTankEstimatorTest {

    private val t0 = 1_750_000_000_000L
    private val day = 86_400_000L

    private fun fill(id: Long, daysAfter: Long, odo: Int, litres: Double) =
        FuelFillEntity(id = id, tMillis = t0 + daysAfter * day, odometerKm = odo, litres = litres, rupees = null, note = null)

    @Test fun justFilledReadsFullTank() {
        val fills = listOf(fill(1, 0, 1000, 12.0))
        val e = FuelTankEstimator.estimate(
            fills = fills, currentOdometerKm = 1000, avgKmPerL = null,
            bikeLiveKmPerL = null, bikeFuelBars = null, capacityL = 12.0, fallbackKmPerL = 45.0,
        )!!
        assertEquals(12.0, e.litresLeft, 0.001)
        assertEquals(1.0, e.percent, 0.001)
        assertEquals(540.0, e.rangeKm, 0.001) // 12 * 45
        assertFalse(e.isRough)
    }

    @Test fun midTankUsesMeasuredAvgKmPerL() {
        // Two fills: 1000 -> 1450 on 10 L => 45 km/L measured.
        val fills = listOf(fill(1, 0, 1000, 10.0), fill(2, 5, 1450, 10.0))
        val avg = MileageAnalytics.averageKmPerL(fills)
        assertEquals(45.0, avg!!, 0.001)
        // Ridden 90 km past the last fill => 2 L used => 10 L left.
        val e = FuelTankEstimator.estimate(
            fills = fills, currentOdometerKm = 1540, avgKmPerL = avg,
            bikeLiveKmPerL = null, bikeFuelBars = null, capacityL = 12.0, fallbackKmPerL = 45.0,
        )!!
        assertEquals(10.0, e.litresLeft, 0.001)
        assertEquals(450.0, e.rangeKm, 0.001) // 10 * 45
        assertEquals(45.0, e.kmPerLUsed, 0.001)
    }

    @Test fun emptyTankClampsToZero() {
        val fills = listOf(fill(1, 0, 1000, 12.0))
        val e = FuelTankEstimator.estimate(
            fills = fills, currentOdometerKm = 1000 + 100_000, avgKmPerL = 45.0,
            bikeLiveKmPerL = null, bikeFuelBars = null, capacityL = 12.0,
        )!!
        assertEquals(0.0, e.litresLeft, 0.001)
        assertEquals(0.0, e.rangeKm, 0.001)
    }

    @Test fun negativeKmSinceFillGuardsToFull() {
        val fills = listOf(fill(1, 0, 2000, 12.0))
        val e = FuelTankEstimator.estimate(
            fills = fills, currentOdometerKm = 1500, avgKmPerL = 45.0,
            bikeLiveKmPerL = null, bikeFuelBars = null, capacityL = 12.0,
        )!!
        assertEquals(12.0, e.litresLeft, 0.001)
    }

    @Test fun coldStartUsesBarsBootstrap() {
        val e = FuelTankEstimator.estimate(
            fills = emptyList(), currentOdometerKm = null, avgKmPerL = null,
            bikeLiveKmPerL = null, bikeFuelBars = 3, capacityL = 12.0, fallbackKmPerL = 45.0,
        )!!
        assertEquals(6.0, e.litresLeft, 0.001) // 3/6 * 12
        assertTrue(e.isRough)
        assertEquals(270.0, e.rangeKm, 0.001) // 6 * 45
    }

    @Test fun coldStartNoBarsIsUnavailable() {
        assertNull(
            FuelTankEstimator.estimate(
                fills = emptyList(), currentOdometerKm = null, avgKmPerL = null,
                bikeLiveKmPerL = null, bikeFuelBars = null, capacityL = 12.0,
            )
        )
    }

    @Test fun fallbackOrderingPrefersAvgThenBikeLive() {
        val fills = listOf(fill(1, 0, 1000, 12.0))
        val e = FuelTankEstimator.estimate(
            fills = fills, currentOdometerKm = 1000, avgKmPerL = null,
            bikeLiveKmPerL = 50.0, bikeFuelBars = null, capacityL = 12.0, fallbackKmPerL = 45.0,
        )!!
        assertEquals(50.0, e.kmPerLUsed, 0.001) // bike-live used over fallback
    }

    @Test fun zeroCapacityIsUnavailable() {
        assertNull(
            FuelTankEstimator.estimate(
                fills = listOf(fill(1, 0, 1000, 12.0)), currentOdometerKm = 1000, avgKmPerL = 45.0,
                bikeLiveKmPerL = null, bikeFuelBars = null, capacityL = 0.0,
            )
        )
    }
}
