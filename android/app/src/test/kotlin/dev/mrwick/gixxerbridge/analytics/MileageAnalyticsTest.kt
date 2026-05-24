package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.FuelFillEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [MileageAnalytics]. No Room, no Android — just hand-crafted
 * [FuelFillEntity] inputs.
 */
class MileageAnalyticsTest {

    private val day: Long = 86_400_000L
    private val t0: Long = 1_750_000_000_000L // ~ 2025-06-15

    private fun fill(
        id: Long,
        daysAfterT0: Long,
        odometerKm: Int,
        litres: Double,
        rupees: Double? = null,
        note: String? = null,
    ): FuelFillEntity = FuelFillEntity(
        id = id,
        tMillis = t0 + daysAfterT0 * day,
        odometerKm = odometerKm,
        litres = litres,
        rupees = rupees,
        note = note,
    )

    // ---------- perTankKmPerL ----------

    @Test fun perTankEmptyIsEmpty() {
        assertTrue(MileageAnalytics.perTankKmPerL(emptyList()).isEmpty())
    }

    @Test fun perTankSingleFillIsEmpty() {
        val one = listOf(fill(id = 1, daysAfterT0 = 0, odometerKm = 1000, litres = 8.0))
        assertTrue(MileageAnalytics.perTankKmPerL(one).isEmpty())
    }

    @Test fun perTankMultipleFillsComputesKmL() {
        // 1000 -> 1320 km on 8 L = 40 km/L
        // 1320 -> 1620 km on 6 L = 50 km/L
        val fills = listOf(
            fill(id = 1, daysAfterT0 = 0, odometerKm = 1000, litres = 8.0),
            fill(id = 2, daysAfterT0 = 5, odometerKm = 1320, litres = 8.0),
            fill(id = 3, daysAfterT0 = 10, odometerKm = 1620, litres = 6.0),
        )
        val pairs = MileageAnalytics.perTankKmPerL(fills)
        assertEquals(2, pairs.size)
        assertEquals(2L, pairs[0].first)
        assertEquals(40.0, pairs[0].second, 0.001)
        assertEquals(3L, pairs[1].first)
        assertEquals(50.0, pairs[1].second, 0.001)
    }

    @Test fun perTankSortsByTimestampRegardlessOfInputOrder() {
        val fills = listOf(
            fill(id = 3, daysAfterT0 = 10, odometerKm = 1620, litres = 6.0),
            fill(id = 1, daysAfterT0 = 0, odometerKm = 1000, litres = 8.0),
            fill(id = 2, daysAfterT0 = 5, odometerKm = 1320, litres = 8.0),
        )
        val pairs = MileageAnalytics.perTankKmPerL(fills)
        assertEquals(2, pairs.size)
        assertEquals(2L, pairs[0].first)
        assertEquals(3L, pairs[1].first)
    }

    @Test fun perTankRejectsZeroLitres() {
        val fills = listOf(
            fill(id = 1, daysAfterT0 = 0, odometerKm = 1000, litres = 8.0),
            fill(id = 2, daysAfterT0 = 5, odometerKm = 1320, litres = 0.0),
        )
        assertTrue(MileageAnalytics.perTankKmPerL(fills).isEmpty())
    }

    @Test fun perTankRejectsNegativeOrZeroKm() {
        // Same odometer (rider entered wrong number / topped-up without riding)
        val sameOdo = listOf(
            fill(id = 1, daysAfterT0 = 0, odometerKm = 1000, litres = 8.0),
            fill(id = 2, daysAfterT0 = 1, odometerKm = 1000, litres = 2.0),
        )
        assertTrue(MileageAnalytics.perTankKmPerL(sameOdo).isEmpty())

        // Newer fill has lower odometer (data-entry error)
        val backwards = listOf(
            fill(id = 1, daysAfterT0 = 0, odometerKm = 1500, litres = 8.0),
            fill(id = 2, daysAfterT0 = 1, odometerKm = 1400, litres = 4.0),
        )
        assertTrue(MileageAnalytics.perTankKmPerL(backwards).isEmpty())
    }

    @Test fun perTankIgnoresRefuelBetweenRides() {
        // "Refuel between rides" reduces to: just two timestamps with a gap;
        // the analytics is rideless, so it should compute exactly like any pair.
        val fills = listOf(
            // Ride 1 finishes, fill up
            fill(id = 1, daysAfterT0 = 0, odometerKm = 5000, litres = 9.0),
            // Ride 2 runs, fill up again (the "between rides" fill)
            fill(id = 2, daysAfterT0 = 3, odometerKm = 5360, litres = 8.0), // 45 km/L
        )
        val pairs = MileageAnalytics.perTankKmPerL(fills)
        assertEquals(1, pairs.size)
        assertEquals(45.0, pairs[0].second, 0.001)
    }

    // ---------- averageKmPerL ----------

    @Test fun averageEmptyIsNull() {
        assertNull(MileageAnalytics.averageKmPerL(emptyList()))
    }

    @Test fun averageSingleFillIsNull() {
        val one = listOf(fill(id = 1, daysAfterT0 = 0, odometerKm = 1000, litres = 8.0))
        assertNull(MileageAnalytics.averageKmPerL(one))
    }

    @Test fun averageOverAllPairsWhenCountExceedsAvailable() {
        // 40 km/L and 50 km/L → average 45
        val fills = listOf(
            fill(id = 1, daysAfterT0 = 0, odometerKm = 1000, litres = 8.0),
            fill(id = 2, daysAfterT0 = 5, odometerKm = 1320, litres = 8.0),
            fill(id = 3, daysAfterT0 = 10, odometerKm = 1620, litres = 6.0),
        )
        val avg = MileageAnalytics.averageKmPerL(fills, count = 5)
        assertEquals(45.0, avg!!, 0.001)
    }

    @Test fun averageTakesTrailingCountOnly() {
        // 4 pairs with rates 30, 40, 50, 60 — trailing 2 = avg(50, 60) = 55
        val fills = listOf(
            fill(id = 1, daysAfterT0 = 0, odometerKm = 0, litres = 1.0),    // anchor
            fill(id = 2, daysAfterT0 = 1, odometerKm = 30, litres = 1.0),   // 30
            fill(id = 3, daysAfterT0 = 2, odometerKm = 70, litres = 1.0),   // 40
            fill(id = 4, daysAfterT0 = 3, odometerKm = 120, litres = 1.0),  // 50
            fill(id = 5, daysAfterT0 = 4, odometerKm = 180, litres = 1.0),  // 60
        )
        val avg = MileageAnalytics.averageKmPerL(fills, count = 2)
        assertEquals(55.0, avg!!, 0.001)
    }

    @Test fun averageReturnsNullWhenAllPairsRejected() {
        // All pairs have zero litres → no valid pairs → null
        val fills = listOf(
            fill(id = 1, daysAfterT0 = 0, odometerKm = 1000, litres = 8.0),
            fill(id = 2, daysAfterT0 = 1, odometerKm = 1100, litres = 0.0),
        )
        assertNull(MileageAnalytics.averageKmPerL(fills))
    }
}
