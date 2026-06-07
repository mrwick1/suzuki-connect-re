package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.FuelFillEntity
import dev.mrwick.gixxerbridge.data.RideEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

/**
 * Pure analytics aggregating a time-windowed "Gixxer Wrapped" recap from
 * existing [RideEntity] and [FuelFillEntity] data.
 *
 * Everything here is deterministic, side-effect-free, and JVM-only — no
 * Android imports — so tests in `src/test` can exercise it directly.
 *
 * ## Honesty flags (no-assumptions rule)
 *
 * Two fields in [WrappedResult] carry explicit uncertainty flags:
 *
 * - [WrappedResult.litresBurnt] carries [LitresEstimate.isEstimate] = true
 *   because litres are derived from km ÷ km/L, never directly measured. The
 *   cluster's "fuel bars" and the fill-ledger km/L both introduce uncertainty.
 *
 * - [WrappedResult.totalSpend] carries [SpendTotal.coverage] counting only
 *   fills where [FuelFillEntity.rupees] was non-null. When coverage < total
 *   fills, the figure is explicitly PARTIAL and the UI must label it as such.
 */
object WrappedAnalytics {

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    /**
     * Compute the full [WrappedResult] for [window] from raw ride + fill data.
     *
     * The time zone used for all local-date conversions is [WrappedWindow.zone],
     * so callers only need to set the zone once (when building the window).
     *
     * @param rides      All persisted rides (any order; may include in-progress
     *                   rides that have no [RideEntity.endOdoKm]).
     * @param fills      All persisted fuel fills (any order).
     * @param avgKmPerL  Fill-ledger average km/L for the window (pass
     *                   [MileageAnalytics.averageKmPerL] result, may be null).
     *
     * Returns null only when the window contains zero ended rides (can't
     * produce a meaningful recap with no data at all). A single ended ride is
     * sufficient.
     */
    fun compute(
        rides: List<RideEntity>,
        fills: List<FuelFillEntity>,
        avgKmPerL: Double?,
        window: WrappedWindow,
    ): WrappedResult? {
        val zone = window.zone
        val windowedRides = rides
            .filter { it.endOdoKm != null } // in-progress rides excluded
            .filter { it.startedAtMillis.toLocalDate(zone) in window }
        if (windowedRides.isEmpty()) return null

        val windowedFills = fills
            .filter { it.tMillis.toLocalDate(zone) in window }

        val totalKm = windowedRides.sumOf { rideKm(it) }
        val totalHours = windowedRides.sumOf { rideDurationHours(it) }
        val rideCount = windowedRides.size
        val longestRide = windowedRides.maxByOrNull { rideKm(it) }!!.let { r ->
            LongestRide(
                km = rideKm(r),
                date = r.startedAtMillis.toLocalDate(zone),
                name = r.name,
            )
        }
        val topSpeed = topSpeedEntry(windowedRides, zone)
        val busiestMonth = busiestMonth(windowedRides, zone)
        val busiestWeekday = busiestWeekday(windowedRides, zone)
        val tankStats = tankStats(windowedFills)
        val litresBurnt = litresBurnt(totalKm, avgKmPerL)
        val totalSpend = totalSpend(windowedFills)
        val longestStreak = longestStreakInWindow(windowedRides, zone)

        return WrappedResult(
            window = window,
            totalKm = totalKm,
            saddleHours = totalHours,
            rideCount = rideCount,
            longestRide = longestRide,
            topSpeed = topSpeed,
            busiestMonth = busiestMonth,
            busiestWeekday = busiestWeekday,
            bestTank = tankStats?.best,
            worstTank = tankStats?.worst,
            litresBurnt = litresBurnt,
            totalSpend = totalSpend,
            longestStreak = longestStreak,
        )
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun rideKm(r: RideEntity): Int =
        max(0, (r.endOdoKm ?: r.startOdoKm) - r.startOdoKm)

    private fun rideDurationHours(r: RideEntity): Double {
        val endMs = r.endedAtMillis ?: r.startedAtMillis
        return max(0L, endMs - r.startedAtMillis) / 3_600_000.0
    }

    private fun Long.toLocalDate(zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

    private operator fun WrappedWindow.contains(date: LocalDate): Boolean =
        !date.isBefore(startInclusive) && !date.isAfter(endInclusive)

    private fun topSpeedEntry(rides: List<RideEntity>, zone: ZoneId): TopSpeedEntry {
        val best = rides.maxByOrNull { it.maxSpeedKmh }!!
        return TopSpeedEntry(
            speedKmh = best.maxSpeedKmh,
            date = best.startedAtMillis.toLocalDate(zone),
            rideName = best.name,
        )
    }

    /**
     * Returns the calendar month with the most total km ridden, expressed as
     * a (year, month) pair. Ties broken by taking the most-recent month.
     */
    private fun busiestMonth(rides: List<RideEntity>, zone: ZoneId): BusiestPeriod {
        // Group by (year, month), sum km
        val byMonth = LinkedHashMap<Pair<Int, Int>, Int>()
        for (r in rides) {
            val ld = r.startedAtMillis.toLocalDate(zone)
            val key = ld.year to ld.monthValue
            byMonth[key] = (byMonth[key] ?: 0) + rideKm(r)
        }
        // Pick the entry with the highest km; on tie keep the latest month
        val (yearMonth, km) = byMonth.entries.maxWith(
            compareBy({ it.value }, { it.key.first * 100 + it.key.second })
        )
        return BusiestPeriod(
            label = "%04d-%02d".format(yearMonth.first, yearMonth.second),
            totalKm = km,
        )
    }

    /**
     * Returns the weekday (Mon=1..Sun=7, ISO) with the most total km ridden.
     * Ties broken by taking the weekday with the higher ISO value (later in the
     * week).
     */
    private fun busiestWeekday(rides: List<RideEntity>, zone: ZoneId): BusiestPeriod {
        val byDow = IntArray(7) // index 0 = Monday (ISO dow 1)
        for (r in rides) {
            val dow = r.startedAtMillis.toLocalDate(zone).dayOfWeek.value // 1=Mon…7=Sun
            byDow[dow - 1] += rideKm(r)
        }
        val isoDow = byDow.indices.maxByOrNull { byDow[it] }!! + 1  // 1..7
        val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        return BusiestPeriod(
            label = names[isoDow - 1],
            totalKm = byDow[isoDow - 1],
        )
    }

    private data class TankStats(val best: TankResult, val worst: TankResult)

    /**
     * Best and worst fill-to-fill efficiency from the windowed fill ledger.
     * Returns null when fewer than 2 fills exist (can't compute a tank interval).
     *
     * The per-tank model matches [MileageAnalytics.perTankKmPerL]: km covered
     * between consecutive fills, divided by *this* fill's litres.
     */
    private fun tankStats(fills: List<FuelFillEntity>): TankStats? {
        val asc = fills.sortedBy { it.tMillis }
        val tanks = mutableListOf<Pair<FuelFillEntity, Double>>() // (fill, kmPerL)
        for (i in 1 until asc.size) {
            val km = asc[i].odometerKm - asc[i - 1].odometerKm
            val l = asc[i].litres
            if (km > 0 && l > 0.0) tanks += asc[i] to (km.toDouble() / l)
        }
        if (tanks.isEmpty()) return null
        val best = tanks.maxByOrNull { it.second }!!
        val worst = tanks.minByOrNull { it.second }!!
        return TankStats(
            best = TankResult(
                kmPerL = best.second,
                fillId = best.first.id,
                odometerKm = best.first.odometerKm,
            ),
            worst = TankResult(
                kmPerL = worst.second,
                fillId = worst.first.id,
                odometerKm = worst.first.odometerKm,
            ),
        )
    }

    /**
     * Estimate total litres burnt over [totalKm].
     *
     * ALWAYS sets [LitresEstimate.isEstimate] = true because litres are never
     * directly measured — they are inferred from distance ÷ km/L. The km/L
     * figure itself may be from fill-ledger averages (better) or entirely
     * absent (null returned).
     *
     * Returns null when [avgKmPerL] is null/non-positive or [totalKm] == 0,
     * so the UI can show a "not enough data" state rather than a fake number.
     */
    private fun litresBurnt(totalKm: Int, avgKmPerL: Double?): LitresEstimate? {
        if (totalKm <= 0) return null
        val kmPerL = avgKmPerL?.takeIf { it > 0.0 } ?: return null
        return LitresEstimate(
            litres = totalKm / kmPerL,
            isEstimate = true, // never directly measured — honesty flag
        )
    }

    /**
     * Sum of fill costs (rupees) over the window.
     *
     * [SpendTotal.coveredFills] = fills with non-null rupees. When
     * [SpendTotal.coveredFills] < [SpendTotal.totalFills] the total is
     * explicitly PARTIAL. The UI must label it accordingly.
     */
    private fun totalSpend(fills: List<FuelFillEntity>): SpendTotal {
        val coveredFills = fills.filter { it.rupees != null }
        val sum = coveredFills.sumOf { it.rupees!! }
        return SpendTotal(
            rupees = sum,
            coveredFills = coveredFills.size,
            totalFills = fills.size,
        )
    }

    /**
     * Longest consecutive-day ride streak entirely within the window.
     *
     * Only calendar days inside [window] are considered — a streak that
     * started before the window is NOT counted from before [window.startInclusive].
     * This ensures the figure reflects the recap period, not lifetime history.
     */
    private fun longestStreakInWindow(rides: List<RideEntity>, zone: ZoneId): Int {
        if (rides.isEmpty()) return 0
        val daysRidden = rides
            .map { it.startedAtMillis.toLocalDate(zone) }
            .toSortedSet()
        var longest = 0
        var run = 0
        var prev: LocalDate? = null
        for (day in daysRidden) {
            run = if (prev != null && day == prev.plusDays(1)) run + 1 else 1
            longest = maxOf(longest, run)
            prev = day
        }
        return longest
    }
}

// =============================================================================
// Result model
// =============================================================================

/**
 * Full Gixxer Wrapped recap for one [WrappedWindow].
 *
 * All nullable fields indicate "not enough data in this window" and should be
 * rendered as a "–" placeholder in the UI.
 */
data class WrappedResult(
    val window: WrappedWindow,

    /** Total km ridden across all ended rides in the window. */
    val totalKm: Int,

    /** Total saddle time in hours (sum of ride durations). */
    val saddleHours: Double,

    /** Number of ended rides in the window. */
    val rideCount: Int,

    /** Longest single ride in the window. Always non-null when rideCount ≥ 1. */
    val longestRide: LongestRide,

    /** Ride with the highest [RideEntity.maxSpeedKmh]. Always non-null when rideCount ≥ 1. */
    val topSpeed: TopSpeedEntry,

    /** Calendar month with the most km ridden. Always non-null when rideCount ≥ 1. */
    val busiestMonth: BusiestPeriod,

    /** ISO weekday (Mon…Sun) with the most km ridden. Always non-null when rideCount ≥ 1. */
    val busiestWeekday: BusiestPeriod,

    /**
     * Best fill-to-fill tank efficiency in the window. Null when < 2 fills in
     * window (can't form even one tank interval).
     */
    val bestTank: TankResult?,

    /**
     * Worst fill-to-fill tank efficiency in the window. Null under the same
     * condition as [bestTank].
     */
    val worstTank: TankResult?,

    /**
     * Estimated total litres burnt. ALWAYS [LitresEstimate.isEstimate] = true —
     * litres are never directly measured on this bike (BLE-only, no flow sensor).
     * Null when no km/L source is available.
     */
    val litresBurnt: LitresEstimate?,

    /**
     * Sum of rupees spent on fuel in this window. [SpendTotal.coveredFills]
     * records how many fills had a price logged; when < [SpendTotal.totalFills]
     * the figure is explicitly PARTIAL.
     */
    val totalSpend: SpendTotal,

    /** Longest consecutive calendar-day streak fully within the window. */
    val longestStreak: Int,
)

/** The longest single ride in the window. */
data class LongestRide(
    val km: Int,
    val date: LocalDate,
    /** Human-readable name of the ride, or null if not set (legacy or unnamed). */
    val name: String?,
)

/** Ride where the peak speed was recorded. */
data class TopSpeedEntry(
    val speedKmh: Int,
    val date: LocalDate,
    /** Human-readable name of the ride, or null if not set. */
    val rideName: String?,
)

/** A calendar period (month or weekday) ranked by total km. */
data class BusiestPeriod(
    /**
     * "yyyy-MM" for months, "Mon"…"Sun" for weekdays.
     * Exact format documented on the calling helper.
     */
    val label: String,
    val totalKm: Int,
)

/** One fill-to-fill efficiency datapoint. */
data class TankResult(
    /** km/L over this tank interval. */
    val kmPerL: Double,
    /** [FuelFillEntity.id] of the closing fill for this interval. */
    val fillId: Long,
    /** [FuelFillEntity.odometerKm] at the closing fill. */
    val odometerKm: Int,
)

/**
 * Estimated total litres consumed over the recap window.
 *
 * [isEstimate] is ALWAYS true for this bike: there is no flow sensor or
 * direct measurement; litres = km ÷ km/L (both inputs carry uncertainty).
 * The UI must always label this as an estimate ("~X L").
 */
data class LitresEstimate(
    val litres: Double,
    val isEstimate: Boolean,
)

/**
 * Fuel spend total over the recap window.
 *
 * [coveredFills] is the count of fills where [FuelFillEntity.rupees] was
 * non-null. When [coveredFills] < [totalFills] the [rupees] sum is PARTIAL
 * and the UI must display it as "≥ ₹X (N of M fills recorded)".
 */
data class SpendTotal(
    val rupees: Double,
    val coveredFills: Int,
    val totalFills: Int,
) {
    /** True when every fill in the window had a price logged. */
    val isComplete: Boolean get() = coveredFills == totalFills
}
