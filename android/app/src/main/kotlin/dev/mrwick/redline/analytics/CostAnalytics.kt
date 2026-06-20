package dev.mrwick.redline.analytics

import dev.mrwick.redline.data.FuelFillEntity

/**
 * Rolling and lifetime running cost, with coverage disclosure.
 *
 * [rollingRupeesPerKm] averages the last N priced tanks; [lifetimeRupeesPerKm]
 * averages all priced tanks. [pricedIntervals]/[totalIntervals] expose how many
 * fill-to-fill intervals actually had a price, so the UI can flag a partial
 * figure when the rider logged some price-less fills.
 */
data class CostStats(
    val rollingRupeesPerKm: Double,
    val lifetimeRupeesPerKm: Double,
    val pricedIntervals: Int,
    val totalIntervals: Int,
) {
    val rollingRupeesPer100Km: Double get() = rollingRupeesPerKm * 100.0
    val lifetimeRupeesPer100Km: Double get() = lifetimeRupeesPerKm * 100.0
    /** True when some intervals were excluded for lacking a price. */
    val isPartialCoverage: Boolean get() = pricedIntervals < totalIntervals
}

/**
 * Pure analytics over the manual fuel-fill log's [FuelFillEntity.rupees].
 *
 * Mirrors [MileageAnalytics.perTankKmPerL] exactly — same consecutive-pair,
 * sort-by-timestamp, km>0 guard — but divides the closing fill's *rupees* by the
 * interval's km to get ₹/km (true rider-recorded running cost) instead of km/L.
 *
 * Price-less intervals (closing fill's [FuelFillEntity.rupees] null or <= 0) are
 * **excluded**, never zero-filled — a missing price must not drag the average to
 * zero. km is measured from odometer deltas only, so an unpriced *opening* fill
 * does not invalidate the following interval.
 *
 * Pure-JVM, deterministic, side-effect free — tested in CostAnalyticsTest.
 */
object CostAnalytics {

    /**
     * Count of consecutive valid-km pairs (denominator for coverage). Mirrors
     * the pair count from [MileageAnalytics.perTankKmPerL]'s km>0 guard, but does
     * NOT apply the price filter — this is the "how many tanks could have had a
     * cost" total.
     */
    fun totalIntervals(fills: List<FuelFillEntity>): Int {
        val asc = fills.sortedBy { it.tMillis }
        var n = 0
        for (i in 1 until asc.size) {
            if (asc[i].odometerKm - asc[i - 1].odometerKm > 0) n++
        }
        return n
    }

    /**
     * `(fillId, rupeesPerKm)` for each consecutive pair whose km>0 AND whose
     * closing fill has a positive price. Ordered chronologically (oldest first).
     */
    fun perTankRupeesPerKm(fills: List<FuelFillEntity>): List<Pair<Long, Double>> {
        val asc = fills.sortedBy { it.tMillis }
        val out = mutableListOf<Pair<Long, Double>>()
        for (i in 1 until asc.size) {
            val km = asc[i].odometerKm - asc[i - 1].odometerKm
            val rupees = asc[i].rupees
            if (km > 0 && rupees != null && rupees > 0.0) {
                out += asc[i].id to (rupees / km)
            }
        }
        return out
    }

    /**
     * Rolling (last [count] priced tanks) + lifetime ₹/km, with coverage. Returns
     * null when no interval has a usable price (so the UI shows an em-dash).
     */
    fun stats(fills: List<FuelFillEntity>, count: Int = 5): CostStats? {
        val priced = perTankRupeesPerKm(fills)
        if (priced.isEmpty()) return null
        val rolling = priced.takeLast(count).map { it.second }.average()
        val lifetime = priced.map { it.second }.average()
        return CostStats(
            rollingRupeesPerKm = rolling,
            lifetimeRupeesPerKm = lifetime,
            pricedIntervals = priced.size,
            totalIntervals = totalIntervals(fills),
        )
    }
}
