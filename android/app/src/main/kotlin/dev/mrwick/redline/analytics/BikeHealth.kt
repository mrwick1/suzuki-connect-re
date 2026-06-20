package dev.mrwick.redline.analytics

import androidx.compose.runtime.Immutable
import dev.mrwick.redline.data.RideEntity

/**
 * Composite 0-100 bike-health score plus its three sub-scores and a textual grade.
 *
 * [insufficientData] is true when 2+ of the three inputs were unknown (so the
 * score leans on neutral-50 fallbacks). Callers should show "Not enough data"
 * rather than a flattering grade in that case.
 */
@Immutable
data class BikeHealthScore(
    val total: Int,
    val service: Int,
    val fuel: Int,
    val connection: Int,
    val insufficientData: Boolean = false,
) {
    val grade: String = when {
        insufficientData -> "Not enough data"
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
        // Count genuinely-unknown inputs: a null odo means service is a guess, a
        // null fuel means fuel is a guess, and no rides means connection is a guess.
        val unknowns = (if (currentOdo == null) 1 else 0) +
            (if (fuelBars == null) 1 else 0) +
            (if (rides.isEmpty()) 1 else 0)
        return BikeHealthScore(
            total = total,
            service = service,
            fuel = fuel,
            connection = connection,
            insufficientData = unknowns >= 2,
        )
    }
}
