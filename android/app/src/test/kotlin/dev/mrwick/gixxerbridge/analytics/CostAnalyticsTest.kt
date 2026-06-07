package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.FuelFillEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [CostAnalytics]. No Room, no Android — hand-crafted
 * [FuelFillEntity] inputs. Mirrors MileageAnalyticsTest's structure: same
 * consecutive-pair logic, but rupees/km instead of km/litres.
 */
class CostAnalyticsTest {

    private val day: Long = 86_400_000L
    private val t0: Long = 1_750_000_000_000L

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

    // ---------- perTankRupeesPerKm ----------

    @Test fun perTankEmptyIsEmpty() {
        assertTrue(CostAnalytics.perTankRupeesPerKm(emptyList()).isEmpty())
    }

    @Test fun perTankSingleFillIsEmpty() {
        val one = listOf(fill(1, 0, 1000, 8.0, rupees = 800.0))
        assertTrue(CostAnalytics.perTankRupeesPerKm(one).isEmpty())
    }

    @Test fun perTankComputesRupeesPerKm() {
        // 1000 -> 1320 km (320 km), closing fill cost ₹640 -> 2.0 ₹/km
        // 1320 -> 1620 km (300 km), closing fill cost ₹600 -> 2.0 ₹/km
        val fills = listOf(
            fill(1, 0, 1000, 8.0, rupees = 800.0),
            fill(2, 5, 1320, 8.0, rupees = 640.0),
            fill(3, 10, 1620, 6.0, rupees = 600.0),
        )
        val pairs = CostAnalytics.perTankRupeesPerKm(fills)
        assertEquals(2, pairs.size)
        assertEquals(2L, pairs[0].first)
        assertEquals(2.0, pairs[0].second, 0.001)
        assertEquals(3L, pairs[1].first)
        assertEquals(2.0, pairs[1].second, 0.001)
    }

    @Test fun perTankSortsByTimestampRegardlessOfInputOrder() {
        val fills = listOf(
            fill(3, 10, 1620, 6.0, rupees = 600.0),
            fill(1, 0, 1000, 8.0, rupees = 800.0),
            fill(2, 5, 1320, 8.0, rupees = 640.0),
        )
        val pairs = CostAnalytics.perTankRupeesPerKm(fills)
        assertEquals(2, pairs.size)
        assertEquals(2L, pairs[0].first)
        assertEquals(3L, pairs[1].first)
    }

    @Test fun perTankExcludesNullPriceInterval() {
        // Closing fill of the only interval has no price -> excluded entirely.
        val fills = listOf(
            fill(1, 0, 1000, 8.0, rupees = 800.0),
            fill(2, 5, 1320, 8.0, rupees = null),
        )
        assertTrue(CostAnalytics.perTankRupeesPerKm(fills).isEmpty())
    }

    @Test fun perTankExcludesZeroOrNegativePrice() {
        val zero = listOf(
            fill(1, 0, 1000, 8.0, rupees = 800.0),
            fill(2, 5, 1320, 8.0, rupees = 0.0),
        )
        assertTrue(CostAnalytics.perTankRupeesPerKm(zero).isEmpty())
    }

    @Test fun perTankRejectsNonPositiveKm() {
        val sameOdo = listOf(
            fill(1, 0, 1000, 8.0, rupees = 800.0),
            fill(2, 1, 1000, 2.0, rupees = 200.0),
        )
        assertTrue(CostAnalytics.perTankRupeesPerKm(sameOdo).isEmpty())

        val backwards = listOf(
            fill(1, 0, 1500, 8.0, rupees = 800.0),
            fill(2, 1, 1400, 4.0, rupees = 400.0),
        )
        assertTrue(CostAnalytics.perTankRupeesPerKm(backwards).isEmpty())
    }

    @Test fun perTankKeepsPricedDropsUnpricedInMixedLedger() {
        // priced -> priced (valid), priced -> unpriced (excluded), unpriced -> priced (valid).
        // Note: km is measured purely from odometer deltas, independent of price
        // on the *previous* fill, so an unpriced opener does NOT poison the next interval.
        val fills = listOf(
            fill(1, 0, 0, 1.0, rupees = 100.0),   // odo 0
            fill(2, 1, 100, 1.0, rupees = 200.0), // 100 km, ₹200 -> 2.0 ₹/km  (kept)
            fill(3, 2, 220, 1.0, rupees = null),  // 120 km, no price          (dropped)
            fill(4, 3, 320, 1.0, rupees = 300.0), // 100 km, ₹300 -> 3.0 ₹/km  (kept)
        )
        val pairs = CostAnalytics.perTankRupeesPerKm(fills)
        assertEquals(2, pairs.size)
        assertEquals(2L, pairs[0].first)
        assertEquals(2.0, pairs[0].second, 0.001)
        assertEquals(4L, pairs[1].first)
        assertEquals(3.0, pairs[1].second, 0.001)
    }

    // ---------- stats ----------

    @Test fun statsNullWhenNoPricedIntervals() {
        val fills = listOf(
            fill(1, 0, 1000, 8.0, rupees = null),
            fill(2, 5, 1320, 8.0, rupees = null),
        )
        assertNull(CostAnalytics.stats(fills))
    }

    @Test fun statsRollingAndLifetimeAndCoverage() {
        // 4 priced intervals: 1.0, 2.0, 3.0, 4.0 ₹/km. count=2 -> rolling avg(3,4)=3.5.
        // lifetime avg(1,2,3,4)=2.5. coverage 4 of 4.
        val fills = listOf(
            fill(1, 0, 0, 1.0, rupees = 0.0).copy(rupees = null), // anchor, no closing role
            fill(2, 1, 100, 1.0, rupees = 100.0), // 1.0
            fill(3, 2, 200, 1.0, rupees = 200.0), // 2.0
            fill(4, 3, 300, 1.0, rupees = 300.0), // 3.0
            fill(5, 4, 400, 1.0, rupees = 400.0), // 4.0
        )
        val s = CostAnalytics.stats(fills, count = 2)!!
        assertEquals(3.5, s.rollingRupeesPerKm, 0.001)
        assertEquals(2.5, s.lifetimeRupeesPerKm, 0.001)
        assertEquals(4, s.pricedIntervals)
        assertEquals(4, s.totalIntervals)
        // ₹/100km is just ×100.
        assertEquals(350.0, s.rollingRupeesPer100Km, 0.001)
        assertEquals(250.0, s.lifetimeRupeesPer100Km, 0.001)
    }

    @Test fun statsCoverageReportsPartial() {
        // 2 intervals total, only 1 priced.
        val fills = listOf(
            fill(1, 0, 0, 1.0, rupees = 100.0),
            fill(2, 1, 100, 1.0, rupees = 200.0), // priced 2.0 ₹/km
            fill(3, 2, 200, 1.0, rupees = null),  // unpriced
        )
        val s = CostAnalytics.stats(fills)!!
        assertEquals(2.0, s.rollingRupeesPerKm, 0.001)
        assertEquals(1, s.pricedIntervals)
        assertEquals(2, s.totalIntervals)
        assertTrue(s.isPartialCoverage)
    }
}
