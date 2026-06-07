package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.FuelFillEntity
import dev.mrwick.gixxerbridge.data.ServiceLogEntity
import java.time.Instant
import java.time.ZoneId

/**
 * Blended running cost of ownership over the fuel + service ledgers.
 *
 * [rupeesPerKm] / [rupeesPer100Km] : total spend / distance.
 * [fuelFraction] / [serviceFraction] : split of total spend (sum to 1.0; only
 *   meaningful when [totalRupees] > 0 — [RunningCostAnalytics.cost] returns null
 *   when total spend is 0).
 * [fuelPricedFraction] : fraction of fills that carried a price — disclosed so
 *   the UI can warn that the figure may undercount (rupees is nullable on both
 *   entities).
 *
 * Pure JVM, deterministic — tested in RunningCostAnalyticsTest.
 */
data class RunningCost(
    val distanceKm: Int,
    val fuelRupees: Double,
    val serviceRupees: Double,
    val totalRupees: Double,
    val rupeesPerKm: Double,
    val rupeesPer100Km: Double,
    val fuelFraction: Double,
    val serviceFraction: Double,
    val fuelFillsPriced: Int,
    val fuelFillsTotal: Int,
    val servicesPriced: Int,
    val servicesTotal: Int,
    val fuelPricedFraction: Double,
)

/** Fuel + service spend in one calendar month ("yyyy-MM"). */
data class MonthSpend(
    val month: String,
    val fuelRupees: Double,
    val serviceRupees: Double,
) {
    val totalRupees: Double get() = fuelRupees + serviceRupees
}

object RunningCostAnalytics {

    /**
     * Compute blended running cost. [fallbackDistanceKm] is used when fewer than
     * two fills exist (no odo delta available) — pass the ride-history odometer
     * span there (see RunningCostViewModel). Returns null when no distance is
     * available or when no rupee figure was ever logged.
     *
     * Distance denominator = (max fill odo − min fill odo) when ≥ 2 fills and
     * positive, else [fallbackDistanceKm]. An odo delta that is zero or negative
     * (data-entry error) also falls back to [fallbackDistanceKm].
     */
    fun cost(
        fills: List<FuelFillEntity>,
        services: List<ServiceLogEntity>,
        fallbackDistanceKm: Int? = null,
    ): RunningCost? {
        val odos = fills.map { it.odometerKm }
        val fillDelta = if (odos.size >= 2) (odos.max() - odos.min()) else 0
        val distanceKm = (if (fillDelta > 0) fillDelta else fallbackDistanceKm ?: 0)
            .coerceAtLeast(0)
        if (distanceKm <= 0) return null

        val fuelPriced = fills.mapNotNull { it.rupees }.filter { it >= 0.0 }
        val svcPriced = services.mapNotNull { it.rupees }.filter { it >= 0.0 }
        val fuelRupees = fuelPriced.sum()
        val serviceRupees = svcPriced.sum()
        val total = fuelRupees + serviceRupees
        if (total <= 0.0) return null

        return RunningCost(
            distanceKm = distanceKm,
            fuelRupees = fuelRupees,
            serviceRupees = serviceRupees,
            totalRupees = total,
            rupeesPerKm = total / distanceKm,
            rupeesPer100Km = total / distanceKm * 100.0,
            fuelFraction = fuelRupees / total,
            serviceFraction = serviceRupees / total,
            fuelFillsPriced = fuelPriced.size,
            fuelFillsTotal = fills.size,
            servicesPriced = svcPriced.size,
            servicesTotal = services.size,
            fuelPricedFraction = if (fills.isEmpty()) 0.0 else fuelPriced.size.toDouble() / fills.size,
        )
    }

    /**
     * Fuel + service spend per calendar month for the last [months] months,
     * oldest-first. Buckets exactly like [RideAnalytics.monthlyKm] — same
     * firstMonth / LinkedHashMap / "%04d-%02d" key approach. Null prices
     * contribute 0; entries outside the window are dropped.
     */
    fun monthlySpend(
        fills: List<FuelFillEntity>,
        services: List<ServiceLogEntity>,
        months: Int = 6,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<MonthSpend> {
        val firstMonth = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .withDayOfMonth(1).minusMonths((months - 1).toLong())
        val fuel = LinkedHashMap<String, Double>()
        val svc = LinkedHashMap<String, Double>()
        for (i in 0 until months) {
            val m = firstMonth.plusMonths(i.toLong())
            val key = "%04d-%02d".format(m.year, m.monthValue)
            fuel[key] = 0.0
            svc[key] = 0.0
        }
        fun bucket(tMillis: Long): String? {
            val d = Instant.ofEpochMilli(tMillis).atZone(zone).toLocalDate()
            val key = "%04d-%02d".format(d.year, d.monthValue)
            return if (fuel.containsKey(key)) key else null
        }
        for (f in fills) {
            val key = bucket(f.tMillis) ?: continue
            fuel[key] = (fuel[key] ?: 0.0) + (f.rupees ?: 0.0).coerceAtLeast(0.0)
        }
        for (s in services) {
            val key = bucket(s.tMillis) ?: continue
            svc[key] = (svc[key] ?: 0.0) + (s.rupees ?: 0.0).coerceAtLeast(0.0)
        }
        return fuel.keys.map { MonthSpend(it, fuel[it] ?: 0.0, svc[it] ?: 0.0) }
    }
}
