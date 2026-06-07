package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.RideEntity
import kotlin.math.ceil

/** Which gate produced the displayed ETA. */
enum class EtaGate { KM, CALENDAR }

/**
 * A projected service due-date for one item.
 *
 * [daysAway] is clamped to ≥ 0 (an overdue item is "due now", never negative);
 * [isOverdue] preserves the "already past" signal for the UI. [gate] says whether
 * the rider's km pace or the calendar threshold is the binding constraint.
 */
data class ServiceEtaForecast(
    val daysAway: Int,
    val dueAtMillis: Long,
    val gate: EtaGate,
    val isOverdue: Boolean,
)

/**
 * Projects a calendar ETA for a service item from its [ServiceItemHealth] gates
 * (km-remaining + days-remaining) and the rider's recent daily km pace.
 *
 * Model: the km gate is converted to days via the pace
 * (`days = ceil(kmRemaining / kmPerDay)`); the calendar gate is `daysRemaining`
 * as-is. The binding constraint is whichever falls **sooner** — "calendar wins"
 * means a slow rider's far-off km date is capped by the time-based threshold.
 *
 * Div-by-zero guard: when [kmPerDay] ≤ 0 (no riding in the pace window) the km
 * projection is undefined and we fall back to the calendar gate alone. If neither
 * gate is projectable (no km gate / no recent rides AND no days gate) returns null.
 *
 * Pure JVM, deterministic — tested in ServiceEtaTest.
 */
object ServiceEta {

    private const val MS_PER_DAY: Long = 24L * 60L * 60L * 1000L

    /** Default pace window — see plan open-question #3 (30 days, user-tunable). */
    const val DEFAULT_PACE_WINDOW_DAYS: Long = 30L

    fun forecast(
        health: ServiceItemHealth,
        kmPerDay: Double,
        now: Long = System.currentTimeMillis(),
    ): ServiceEtaForecast? {
        // km candidate: days until the km gate is hit at the current pace.
        // Undefined when there's no km gate, or pace is non-positive / non-finite.
        val kmDays: Int? = health.kmRemaining?.let { kmLeft ->
            if (kmPerDay <= 0.0 || kmPerDay.isNaN() || kmPerDay.isInfinite()) return@let null
            ceil(kmLeft / kmPerDay).toInt()
        }
        val calDays: Int? = health.daysRemaining

        // Pick the sooner gate. Calendar wins ties (<=) and is the fallback when
        // km is undefined (zero-pace window).
        val (rawDays, gate) = when {
            kmDays != null && calDays != null ->
                if (calDays <= kmDays) calDays to EtaGate.CALENDAR else kmDays to EtaGate.KM
            kmDays != null -> kmDays to EtaGate.KM
            calDays != null -> calDays to EtaGate.CALENDAR
            else -> return null
        }

        val isOverdue = rawDays <= 0
        val daysAway = rawDays.coerceAtLeast(0)
        return ServiceEtaForecast(
            daysAway = daysAway,
            dueAtMillis = now + daysAway * MS_PER_DAY,
            gate = gate,
            isOverdue = isOverdue,
        )
    }

    /** Rolling [windowDays]-day km/day pace, from logged rides. 0.0 when none. */
    fun paceKmPerDay(
        rides: List<RideEntity>,
        windowDays: Long = DEFAULT_PACE_WINDOW_DAYS,
        now: Long = System.currentTimeMillis(),
    ): Double {
        if (windowDays <= 0L) return 0.0
        val km = RideAnalytics.totalsFor(rides, days = windowDays, now = now).km
        return km.toDouble() / windowDays.toDouble()
    }

    /** Relative phrase, e.g. "~18 days" or "due now" when overdue/at-zero. */
    fun formatRelative(f: ServiceEtaForecast): String =
        if (f.isOverdue || f.daysAway <= 0) "due now"
        else if (f.daysAway == 1) "~1 day"
        else "~${f.daysAway} days"
}
