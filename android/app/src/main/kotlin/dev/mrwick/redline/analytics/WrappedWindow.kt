package dev.mrwick.redline.analytics

import java.time.LocalDate
import java.time.ZoneId

/**
 * The time window over which a Gixxer Wrapped recap is computed.
 *
 * [startInclusive] and [endInclusive] are local calendar dates. A [RideEntity]
 * is "in window" when its start date (in [zone]) falls within
 * [startInclusive]..[endInclusive] (both inclusive). A [FuelFillEntity] is "in
 * window" when its date (in [zone]) falls within the same range.
 *
 * The companion factory [ofYear] / [ofCalendarYear] / [ofRollingDays] produce
 * the most common windows without the caller having to manage date math.
 */
data class WrappedWindow(
    val startInclusive: LocalDate,
    val endInclusive: LocalDate,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    init {
        require(!endInclusive.isBefore(startInclusive)) {
            "endInclusive ($endInclusive) must not be before startInclusive ($startInclusive)"
        }
    }

    companion object {

        /**
         * The calendar year [year] in [zone], i.e. Jan 1 … Dec 31 of that year.
         * Use this for "your 2024 in review"-style recaps.
         */
        fun ofCalendarYear(
            year: Int,
            zone: ZoneId = ZoneId.systemDefault(),
        ): WrappedWindow = WrappedWindow(
            startInclusive = LocalDate.of(year, 1, 1),
            endInclusive = LocalDate.of(year, 12, 31),
            zone = zone,
        )

        /**
         * A rolling [days]-day window ending [today] (inclusive).
         *
         * For example [days]=365 with [today]=2025-06-07 gives
         * 2024-06-08…2025-06-07 — approximately "the last year" without being
         * calendar-year-aligned.
         */
        fun ofRollingDays(
            days: Int,
            today: LocalDate = LocalDate.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): WrappedWindow {
            require(days >= 1) { "days must be >= 1, was $days" }
            return WrappedWindow(
                startInclusive = today.minusDays((days - 1).toLong()),
                endInclusive = today,
                zone = zone,
            )
        }
    }
}
