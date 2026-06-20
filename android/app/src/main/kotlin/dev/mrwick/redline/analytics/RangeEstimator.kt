package dev.mrwick.redline.analytics

import dev.mrwick.redline.data.RideEntity

/**
 * Estimates remaining km from current fuel bars and historical km-per-bar.
 *
 * Pure JVM, no Android imports, no Room — easy to test with hand-crafted
 * [RideEntity] inputs.
 */
object RangeEstimator {

    /**
     * Compute km-per-fuel-bar from rides that have both `fuelBarsStart` and
     * `fuelBarsEnd` AND showed a positive bars-consumed delta. Returns null
     * when we have no usable history.
     *
     * Heuristic: median of (km / bars-consumed) across qualifying rides.
     * Median (over mean) keeps the estimate robust against outliers — e.g. a
     * ride that ended right as a bar dropped, inflating bars-used to 1 with
     * only 5 km traveled.
     */
    // ASSUMED: median across qualifying rides is the right central tendency
    // for a small sample (most users will have <50 rides). Mean would be
    // more accurate at large N but is fragile here.
    fun kmPerBar(rides: List<RideEntity>): Double? {
        val samples = rides.mapNotNull { r ->
            val end = r.endOdoKm ?: return@mapNotNull null
            val start = r.startOdoKm
            val barsStart = r.fuelBarsStart ?: return@mapNotNull null
            val barsEnd = r.fuelBarsEnd ?: return@mapNotNull null
            val km = end - start
            val barsUsed = barsStart - barsEnd
            if (km <= 0 || barsUsed <= 0) null else km.toDouble() / barsUsed
        }
        if (samples.isEmpty()) return null
        return samples.sorted().let { it[it.size / 2] }
    }

    /** Estimated km remaining given the current fuel bars and the historical km/bar. */
    fun estimateRemainingKm(currentFuelBars: Int?, kmPerBar: Double?): Double? {
        if (currentFuelBars == null || currentFuelBars <= 0) return null
        if (kmPerBar == null || kmPerBar <= 0) return null
        return currentFuelBars * kmPerBar
    }

    // ASSUMED: Gixxer SF 150 anecdotal ~50 km/bar (12L tank, 6 bars, ~45 km/L).
    // Used as a fallback when there's no ride history yet. Not currently wired
    // into the UI but exposed for callers that want a non-null default.
    const val FALLBACK_KM_PER_BAR: Double = 50.0
}
