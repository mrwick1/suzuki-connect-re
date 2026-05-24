package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.FuelFillEntity

/**
 * Pure analytics over the manual fuel-fill log.
 *
 * Per-tank km/L: km travelled between fill N-1 and fill N, divided by THIS
 * fill's litres (i.e. how much fuel was needed to refill the tank back to
 * approximately full). This matches the rider's intuition at the pump — "the
 * litres I just paid for covered the last leg".
 *
 * Pure-JVM, deterministic, side-effect free — tested in
 * `app/src/test/.../MileageAnalyticsTest.kt`.
 */
object MileageAnalytics {

    /**
     * Compute per-tank km/L for each consecutive pair of fills.
     *
     * Returns a list of `(fillId, kmPerL)` ordered chronologically (oldest
     * pairing first). The first fill in chronological order has no previous
     * fill to pair with, so it is omitted.
     *
     * Skips a pair when `km <= 0` (rider entered a smaller odometer than the
     * previous fill — data-entry error) or `litres <= 0` (rejection).
     *
     * The input list may be in any order; this function sorts a copy by
     * [FuelFillEntity.tMillis] ascending before pairing.
     */
    fun perTankKmPerL(fills: List<FuelFillEntity>): List<Pair<Long, Double>> {
        val asc = fills.sortedBy { it.tMillis }
        val out = mutableListOf<Pair<Long, Double>>()
        for (i in 1 until asc.size) {
            val km = asc[i].odometerKm - asc[i - 1].odometerKm
            val l = asc[i].litres
            if (km > 0 && l > 0.0) out += asc[i].id to (km / l)
        }
        return out
    }

    /**
     * Trailing average km/L over the last [count] valid tanks. Returns null if
     * there are no valid tanks (e.g. fewer than 2 fills, or every pair was
     * rejected by [perTankKmPerL]'s guards).
     */
    fun averageKmPerL(fills: List<FuelFillEntity>, count: Int = 5): Double? {
        val pairs = perTankKmPerL(fills).takeLast(count)
        if (pairs.isEmpty()) return null
        return pairs.map { it.second }.average()
    }
}
