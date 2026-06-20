package dev.mrwick.redline.analytics

import androidx.compose.runtime.Immutable

/**
 * Coarse bucket describing how soon the rider should expect to refuel.
 *
 * Deliberately imprecise — "~2-3 days" rather than "Thursday" — so the UI can
 * never be read as a binding commitment when daily pace fluctuates.
 *
 * Ordering: [TODAY] < [ONE_DAY] < [TWO_THREE_DAYS] < [THIS_WEEK] < [OVER_A_WEEK].
 * [UNKNOWN] is returned when there is insufficient data to compute anything.
 */
enum class RefuelBucket {
    /** Range will run out today (< 1 day of riding left at current pace). */
    TODAY,
    /** Approximately one day of riding left. */
    ONE_DAY,
    /** Approximately two to three days of riding left. */
    TWO_THREE_DAYS,
    /** Somewhere within the next week (four to seven days). */
    THIS_WEEK,
    /** More than a week of riding left. */
    OVER_A_WEEK,
    /** No-ride-history or bad inputs — cannot predict. */
    UNKNOWN,
}

/**
 * Result from [RefuelPredictor.predict].
 *
 * [bucket] is the coarse refuel window.
 * [fillBeforeService] is true when at least one km-gated service is overdue or
 * will fall inside the refuel window — i.e. the rider should fill up before the
 * service appointment so that they can discuss fuel / range trends with the shop.
 * When [bucket] is [RefuelBucket.UNKNOWN] this flag is always false (no prediction
 * to hang it on).
 */
@Immutable
data class RefuelPrediction(
    val bucket: RefuelBucket,
    val fillBeforeService: Boolean,
)

/**
 * Pure, side-effect-free refuel-window predictor.
 *
 * Converts a continuous range estimate and a daily-pace into a deliberately
 * coarse [RefuelBucket] so the UI never implies precision we don't have.
 * Separately signals whether a km-gated service appointment is near enough that
 * the rider should fill before going to the shop ([fillBeforeService]).
 *
 * Pure JVM, no Android imports — tested in RefuelPredictorTest.
 */
object RefuelPredictor {

    // ASSUMED: km/L for the Gixxer SF 150 is ~45 km/L. Relevant only for
    // documentation; the actual km/L is passed in via [FuelTankEstimator] output
    // and is not hardcoded here.

    // ASSUMED: km/bar ~= 50 km for the Gixxer SF 150.
    // Exposed for callers that use it as a fallback pace denominator, but the
    // preferred input is a measured daily-pace from ride history.
    const val ASSUMED_KM_PER_BAR: Double = 50.0

    // Bucket thresholds in days-of-riding (not wall-clock days).
    private const val THRESHOLD_TODAY_DAYS = 1.0
    private const val THRESHOLD_ONE_DAY_DAYS = 2.0
    private const val THRESHOLD_TWO_THREE_DAYS = 4.0
    private const val THRESHOLD_THIS_WEEK_DAYS = 7.0

    // A service item is considered "near" when it falls within [SERVICE_NEAR_KM]
    // of the refuel window's outer edge. This adds a small buffer so "fill today
    // before the shop" is triggered slightly early rather than too late.
    // ASSUMED: 200 km buffer is a sane advance-warning margin; tune if needed.
    private const val SERVICE_NEAR_KM: Int = 200

    /**
     * Predict the refuel window.
     *
     * @param rangeKm          Estimated kilometres remaining in the tank, from
     *                         [FuelTankEstimator.estimate]. Pass null when the
     *                         estimate is unavailable.
     * @param dailyKmPace      Average km ridden per calendar day, derived from
     *                         recent ride history. Pass null when there are no
     *                         recent rides (guard: no-recent-rides).
     * @param serviceKmRemaining
     *                         The km remaining before the most-urgent km-gated
     *                         service item, from [ServiceSchedule] / [ServiceItemHealth.kmRemaining].
     *                         Negative values mean the item is already overdue.
     *                         Pass null when no km-gated service item has a
     *                         recorded baseline (i.e. the co-prompt is never
     *                         appropriate).
     */
    fun predict(
        rangeKm: Double?,
        dailyKmPace: Double?,
        serviceKmRemaining: Int?,
    ): RefuelPrediction {
        // Guard: no data → UNKNOWN, no co-prompt.
        if (rangeKm == null || rangeKm < 0.0) return RefuelPrediction(RefuelBucket.UNKNOWN, false)
        if (dailyKmPace == null || dailyKmPace <= 0.0) return RefuelPrediction(RefuelBucket.UNKNOWN, false)

        val daysOfRiding = rangeKm / dailyKmPace

        val bucket = when {
            daysOfRiding < THRESHOLD_TODAY_DAYS       -> RefuelBucket.TODAY
            daysOfRiding < THRESHOLD_ONE_DAY_DAYS     -> RefuelBucket.ONE_DAY
            daysOfRiding < THRESHOLD_TWO_THREE_DAYS   -> RefuelBucket.TWO_THREE_DAYS
            daysOfRiding < THRESHOLD_THIS_WEEK_DAYS   -> RefuelBucket.THIS_WEEK
            else                                       -> RefuelBucket.OVER_A_WEEK
        }

        // Co-prompt: is a km-gated service overdue, or will it fall before we
        // next fill up? "Before we next fill up" == within rangeKm + buffer.
        val fillBeforeService = serviceKmRemaining != null &&
            serviceKmRemaining <= (rangeKm + SERVICE_NEAR_KM).toInt()

        return RefuelPrediction(bucket, fillBeforeService)
    }

    /**
     * Convenience: compute [dailyKmPace] from a list of (distanceKm, durationMs)
     * ride pairs covering the recent window.
     *
     * Returns null when [rides] is empty or total elapsed time is zero (no-ride
     * guard). The result is a *riding-day* pace — total km / total elapsed days —
     * which is appropriate for "how many days until empty at this rate."
     *
     * The caller decides what window to pass (e.g. last 30 days). This function
     * applies no date filter itself.
     *
     * @param rides List of (distanceKm, elapsedMs) pairs. Rides with zero or
     *              negative distance are skipped; rides with zero or negative
     *              duration are also skipped.
     */
    fun dailyKmPaceFromRides(rides: List<Pair<Int, Long>>): Double? {
        // Numerator and denominator must use the SAME valid rides — otherwise a
        // negative/zero-duration ride contributes km without days, inflating pace.
        val valid = rides.filter { (km, ms) -> km > 0 && ms > 0L }
        val totalKm = valid.sumOf { (km, _) -> km }
        val totalDays = valid.sumOf { (_, ms) -> ms.toDouble() / MS_PER_DAY }
        if (totalKm <= 0 || totalDays <= 0.0) return null
        return totalKm / totalDays
    }

    private const val MS_PER_DAY: Double = 24.0 * 60.0 * 60.0 * 1000.0
}
