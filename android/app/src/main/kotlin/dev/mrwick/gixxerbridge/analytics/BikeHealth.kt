package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.RideEntity

/** Composite 0-100 bike-health score plus its three sub-scores and a textual grade. */
data class BikeHealthScore(val total: Int, val service: Int, val fuel: Int, val connection: Int) {
    val grade: String = when {
        total >= 85 -> "Excellent"
        total >= 65 -> "Good"
        total >= 40 -> "Fair"
        else -> "Needs attention"
    }
}

/**
 * Pure, side-effect-free computation of the home-screen Bike Health badge.
 *
 * Three components, each 0-100, blended via fixed weights (service 34%, fuel 33%, connection 33%):
 *   - service: 100 when sinceService = 0, linearly to 0 at +interval over (sinceService = 2 * interval).
 *   - fuel: 100 at 6 bars, 0 at 0 bars, linear in between.
 *   - connection: 100 if a ride is logged today, falling linearly to 0 over 7 days of silence.
 *
 * When a component's input is unknown (null odo / null fuel), that component scores a neutral 50
 * rather than punishing the rider for missing data.
 */
object BikeHealth {
    /** Compute the composite score from current telemetry + service settings + ride history. */
    fun compute(
        currentOdo: Int?,
        lastServiceOdo: Int,
        serviceIntervalKm: Int,
        fuelBars: Int?,
        rides: List<RideEntity>,
        now: Long = System.currentTimeMillis(),
    ): BikeHealthScore {
        val service = if (currentOdo == null) 50 else {
            val sinceService = (currentOdo - lastServiceOdo).coerceAtLeast(0)
            val interval = serviceIntervalKm.coerceAtLeast(1).toDouble()
            val ratio = (sinceService.toDouble() / interval).coerceIn(0.0, 2.0)
            ((1.0 - ratio / 2.0) * 100).toInt().coerceIn(0, 100)
        }
        val fuel = if (fuelBars == null) 50 else (fuelBars.coerceIn(0, 6) * 100 / 6)
        val connection = run {
            val mostRecent = rides.maxOfOrNull { it.startedAtMillis } ?: return@run 0
            val ageMs = (now - mostRecent).coerceAtLeast(0L)
            val ageDays = ageMs / (24 * 60 * 60 * 1000.0)
            ((1.0 - (ageDays / 7.0).coerceIn(0.0, 1.0)) * 100).toInt().coerceIn(0, 100)
        }
        val total = ((service * 0.34) + (fuel * 0.33) + (connection * 0.33))
            .toInt().coerceIn(0, 100)
        return BikeHealthScore(total = total, service = service, fuel = fuel, connection = connection)
    }
}
