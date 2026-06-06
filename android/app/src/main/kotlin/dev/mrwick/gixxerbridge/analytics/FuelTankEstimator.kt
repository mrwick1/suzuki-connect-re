package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.FuelFillEntity

/**
 * Estimated tank state: litres remaining, fill fraction, and resulting range.
 *
 * [isRough] is true for the pre-first-fill bootstrap (derived from the bike's
 * coarse 6-bar gauge rather than the fill ledger).
 */
data class FuelEstimate(
    val litresLeft: Double,
    val percent: Double, // 0.0..1.0
    val rangeKm: Double,
    val kmPerLUsed: Double,
    val isRough: Boolean,
)

/**
 * Estimates litres remaining in the tank + range, from the manual fuel-fill log
 * and the current odometer.
 *
 * Model: the rider always fills to full, so the most-recent fill re-anchors the
 * tank to [capacityL]. We subtract litres consumed since that fill, where
 * consumed = (current odo - fill odo) / km-per-L. The km/L used is the measured
 * fill-to-fill average ([MileageAnalytics.averageKmPerL]); callers pass bike-live
 * and fixed-default fallbacks for the window before 2 fills exist.
 *
 * Pure JVM, deterministic - tested in FuelTankEstimatorTest.
 */
object FuelTankEstimator {

    // ASSUMED: ~45 km/L for the Gixxer SF 150 - unverified; only used for the
    // pre-first-fill bootstrap when the bike isn't live. Tune once measured.
    const val DEFAULT_FALLBACK_KM_PER_L: Double = 45.0

    private const val FUEL_BARS_MAX = 6.0

    fun estimate(
        fills: List<FuelFillEntity>,
        currentOdometerKm: Int?,
        avgKmPerL: Double?,
        bikeLiveKmPerL: Double?,
        bikeFuelBars: Int?,
        capacityL: Double,
        fallbackKmPerL: Double = DEFAULT_FALLBACK_KM_PER_L,
    ): FuelEstimate? {
        if (capacityL <= 0.0) return null
        val kmPerL = listOfNotNull(avgKmPerL, bikeLiveKmPerL).firstOrNull { it > 0.0 } ?: fallbackKmPerL
        if (kmPerL <= 0.0 || kmPerL.isNaN()) return null

        val anchor = fills.maxByOrNull { it.tMillis }
        if (anchor != null && currentOdometerKm != null) {
            val kmSince = (currentOdometerKm - anchor.odometerKm).coerceAtLeast(0)
            val litres = (capacityL - kmSince / kmPerL).coerceIn(0.0, capacityL)
            return FuelEstimate(litres, litres / capacityL, litres * kmPerL, kmPerL, isRough = false)
        }

        // Cold-start: no fills (or no odometer yet) -> rough bars->litres bootstrap.
        val bars = bikeFuelBars ?: return null
        if (bars < 0) return null
        val litres = ((bars / FUEL_BARS_MAX) * capacityL).coerceIn(0.0, capacityL)
        return FuelEstimate(litres, litres / capacityL, litres * kmPerL, kmPerL, isRough = true)
    }
}
