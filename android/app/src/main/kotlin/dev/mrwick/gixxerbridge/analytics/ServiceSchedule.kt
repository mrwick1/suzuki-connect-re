package dev.mrwick.gixxerbridge.analytics

import androidx.compose.runtime.Immutable
import dev.mrwick.gixxerbridge.data.ServiceItem
import dev.mrwick.gixxerbridge.data.ServiceItemState

/** One service item's current "how much life is left" view in days + km space. */
@Immutable
data class ServiceItemHealth(
    val state: ServiceItemState,
    /** Days remaining until [ServiceItemState.daysThreshold] elapses; negative when overdue. Null when no baseline. */
    val daysRemaining: Int?,
    /** Kilometres remaining until [ServiceItemState.kmThreshold] elapses; negative when overdue. Null when item has no km gate or no baseline. */
    val kmRemaining: Int?,
    /**
     * Fraction of "life remaining" in [0.0, 1.0] where 1.0 = freshly serviced and
     * 0.0 = exactly at threshold. Values below 0.0 are clamped — the item is
     * already overdue and we don't differentiate "1 km over" from "1000 km over"
     * for the gauge. Null when no baseline is recorded for this item.
     */
    val remainingFraction: Double?,
)

/** Result of [ServiceSchedule.mostOverdue]: every item's health plus the worst one. */
@Immutable
data class ServiceScheduleHealth(
    val perItem: List<ServiceItemHealth>,
    /** The item with the smallest [ServiceItemHealth.remainingFraction]; null when no item has a baseline. */
    val worst: ServiceItemHealth?,
)

/**
 * Pure, side-effect-free service-schedule arithmetic.
 *
 * Mirrors the Suzuki Connect app's reminder model (DISCOVERIES.md 2026-05-25):
 * for each item with a recorded baseline, compute days-used and km-used as
 * fractions of their respective thresholds; the worst (smallest remaining)
 * gate drives the displayed gauge. Items with no baseline don't participate
 * in the worst-of comparison — they show as "no data" in the UI.
 */
object ServiceSchedule {

    /**
     * Compute per-item health for every item in [items], plus the worst one.
     *
     * [currentOdoKm] is the latest odometer reading or null when unknown
     * (bike never connected). [now] is the current wall-clock time in millis.
     */
    fun mostOverdue(
        items: Collection<ServiceItemState>,
        currentOdoKm: Int?,
        now: Long = System.currentTimeMillis(),
    ): ServiceScheduleHealth {
        val perItem = items.map { healthFor(it, currentOdoKm, now) }
        val worst = perItem
            .filter { it.remainingFraction != null }
            .minByOrNull { it.remainingFraction!! }
        return ServiceScheduleHealth(perItem = perItem, worst = worst)
    }

    /** Compute one item's [ServiceItemHealth]; null fractions when no baseline exists. */
    fun healthFor(
        state: ServiceItemState,
        currentOdoKm: Int?,
        now: Long = System.currentTimeMillis(),
    ): ServiceItemHealth {
        val daysRemaining: Int? = state.lastServiceDateMs?.let { lastMs ->
            val elapsedMs = (now - lastMs).coerceAtLeast(0L)
            val elapsedDays = (elapsedMs / MS_PER_DAY).toInt()
            state.daysThreshold - elapsedDays
        }
        val kmRemaining: Int? = run {
            val km = state.kmThreshold ?: return@run null
            val baseline = state.lastServiceOdoKm ?: return@run null
            val odo = currentOdoKm ?: return@run null
            val used = (odo - baseline).coerceAtLeast(0)
            km - used
        }
        // Days fraction is computable iff a date baseline exists.
        // Km fraction is computable iff an odo baseline exists, the item has a
        // km gate AND we know the current odo. The two fractions can be
        // populated independently — most items will have both, brake oil has
        // only days, and a brand-new install has neither.
        val daysFraction: Double? = state.lastServiceDateMs?.let { _ ->
            val denom = state.daysThreshold.coerceAtLeast(1).toDouble()
            (daysRemaining!!.toDouble() / denom).coerceIn(0.0, 1.0)
        }
        val kmFraction: Double? = if (kmRemaining != null) {
            val denom = state.kmThreshold!!.coerceAtLeast(1).toDouble()
            (kmRemaining.toDouble() / denom).coerceIn(0.0, 1.0)
        } else null
        val remainingFraction: Double? = when {
            daysFraction != null && kmFraction != null -> minOf(daysFraction, kmFraction)
            daysFraction != null -> daysFraction
            kmFraction != null -> kmFraction
            else -> null
        }
        return ServiceItemHealth(
            state = state,
            daysRemaining = daysRemaining,
            kmRemaining = kmRemaining,
            remainingFraction = remainingFraction,
        )
    }

    private const val MS_PER_DAY: Long = 24L * 60L * 60L * 1000L
}
