package dev.mrwick.redline.analytics

import dev.mrwick.redline.data.FuelFillEntity
import dev.mrwick.redline.data.ServiceLogEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [RunningCostAnalytics]. No Room, no Android. */
class RunningCostAnalyticsTest {

    private val day = 86_400_000L
    private val t0 = 1_750_000_000_000L

    private fun fill(id: Long, daysAfter: Long, odo: Int, litres: Double, rupees: Double?) =
        FuelFillEntity(id = id, tMillis = t0 + daysAfter * day, odometerKm = odo, litres = litres, rupees = rupees, note = null)

    private fun svc(id: Long, daysAfter: Long, odo: Int, rupees: Double?, type: String = "Oil change") =
        ServiceLogEntity(id = id, tMillis = t0 + daysAfter * day, odometerKm = odo, type = type, rupees = rupees, notes = null)

    // ---------- cost() ----------

    @Test fun blendedCostFromFuelAndService() {
        // Distance denominator = first->last fill odo = 1000 -> 2000 = 1000 km.
        // Fuel ₹ = 400 + 400 = 800. Service ₹ = 200. Total = 1000 over 1000 km => ₹1.0/km.
        val fills = listOf(fill(1, 0, 1000, 10.0, 400.0), fill(2, 10, 2000, 10.0, 400.0))
        val svcs = listOf(svc(1, 5, 1500, 200.0))
        val c = RunningCostAnalytics.cost(fills, svcs)!!
        assertEquals(1000, c.distanceKm)
        assertEquals(800.0, c.fuelRupees, 0.001)
        assertEquals(200.0, c.serviceRupees, 0.001)
        assertEquals(1.0, c.rupeesPerKm, 0.001)
        assertEquals(100.0, c.rupeesPer100Km, 0.001)
        assertEquals(0.8, c.fuelFraction, 0.001)
        assertEquals(0.2, c.serviceFraction, 0.001)
    }

    @Test fun nullRupeesExcludedAndDisclosedViaCoverage() {
        // Fill 2 has no price -> excluded from fuel ₹ but still counts for distance.
        val fills = listOf(fill(1, 0, 1000, 10.0, 400.0), fill(2, 10, 2000, 10.0, null))
        val c = RunningCostAnalytics.cost(fills, emptyList())!!
        assertEquals(400.0, c.fuelRupees, 0.001)
        assertEquals(1, c.fuelFillsPriced)
        assertEquals(2, c.fuelFillsTotal)
        assertEquals(0.5, c.fuelPricedFraction, 0.001) // 1 of 2 priced
    }

    @Test fun serviceFractionIsOneWhenNoFuelSpend() {
        val svcs = listOf(svc(1, 0, 1000, 500.0))
        // Need a distance denominator; with <2 fills, fall back to ride span (passed in).
        val c = RunningCostAnalytics.cost(emptyList(), svcs, fallbackDistanceKm = 1000)!!
        assertEquals(0.0, c.fuelRupees, 0.001)
        assertEquals(500.0, c.serviceRupees, 0.001)
        assertEquals(1.0, c.serviceFraction, 0.001)
        assertEquals(0.0, c.fuelFraction, 0.001)
        assertEquals(0.5, c.rupeesPerKm, 0.001)
    }

    @Test fun fallbackDistanceUsedWhenFewerThanTwoFills() {
        val fills = listOf(fill(1, 0, 1000, 10.0, 400.0))
        val c = RunningCostAnalytics.cost(fills, emptyList(), fallbackDistanceKm = 500)!!
        assertEquals(500, c.distanceKm) // single fill gives no odo delta -> fallback
    }

    @Test fun nullWhenNoDistanceAndNoFallback() {
        val fills = listOf(fill(1, 0, 1000, 10.0, 400.0)) // single fill, no delta
        assertNull(RunningCostAnalytics.cost(fills, emptyList(), fallbackDistanceKm = null))
    }

    @Test fun nullWhenNoSpendAtAll() {
        // Distance exists but every rupee field is null -> nothing to cost.
        val fills = listOf(fill(1, 0, 1000, 10.0, null), fill(2, 5, 1500, 10.0, null))
        assertNull(RunningCostAnalytics.cost(fills, emptyList()))
    }

    @Test fun distanceFromFillOdoDeltaIgnoresInputOrder() {
        val fills = listOf(
            fill(2, 10, 2000, 10.0, 400.0),
            fill(1, 0, 1000, 10.0, 400.0),
        )
        val c = RunningCostAnalytics.cost(fills, emptyList())!!
        assertEquals(1000, c.distanceKm)
    }

    @Test fun negativeOdoDeltaFallsBack() {
        // Backwards odo (data-entry error): max-min still positive, so guard on
        // last-min vs first using sorted order; ensure non-negative.
        val fills = listOf(fill(1, 0, 2000, 10.0, 400.0), fill(2, 5, 1000, 10.0, 400.0))
        val c = RunningCostAnalytics.cost(fills, emptyList(), fallbackDistanceKm = 300)!!
        assertTrue(c.distanceKm >= 0)
    }

    // ---------- monthlySpend() ----------

    @Test fun monthlySpendBucketsFuelAndServiceByCalendarMonth() {
        // now = t0; build entries in two distinct months relative to a fixed clock.
        val now = t0
        val fills = listOf(
            fill(1, 0, 1000, 10.0, 400.0),    // this month
            fill(2, -40, 500, 10.0, 300.0),   // ~prev month
        )
        val svcs = listOf(svc(1, 0, 1000, 200.0)) // this month
        val months = RunningCostAnalytics.monthlySpend(fills, svcs, months = 3, now = now)
        assertEquals(3, months.size)            // always exactly `months` buckets
        // Buckets are oldest-first and contiguous.
        val last = months.last()
        assertEquals(400.0, last.fuelRupees, 0.001)
        assertEquals(200.0, last.serviceRupees, 0.001)
        assertEquals(600.0, last.totalRupees, 0.001)
    }

    @Test fun monthlySpendIgnoresNullPricesAndOutOfWindowEntries() {
        val now = t0
        val fills = listOf(
            fill(1, 0, 1000, 10.0, null),     // priced null -> 0
            fill(2, -400, 100, 10.0, 999.0),  // far outside 3-month window -> dropped
        )
        val months = RunningCostAnalytics.monthlySpend(fills, emptyList(), months = 3, now = now)
        assertTrue(months.all { it.totalRupees == 0.0 })
    }
}
