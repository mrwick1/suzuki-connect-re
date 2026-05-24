package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.RideEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Current + longest consecutive-day ride streaks (Duolingo-style). */
data class StreakInfo(val current: Int, val longest: Int)

/** Pure, side-effect-free streak math over the ride history. */
object RideStreak {
    /**
     * Compute current + longest streaks (in local-zone calendar days).
     *
     * Current streak counts back from [today]; if no ride happened today the count
     * starts from yesterday so a non-zero streak survives an as-yet rideless day.
     */
    fun compute(
        rides: List<RideEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): StreakInfo {
        if (rides.isEmpty()) return StreakInfo(0, 0)
        val daysRidden = rides
            .map { Instant.ofEpochMilli(it.startedAtMillis).atZone(zone).toLocalDate() }
            .toSortedSet()
        var current = 0
        var d = today
        if (d !in daysRidden) d = d.minusDays(1)
        while (d in daysRidden) {
            current++
            d = d.minusDays(1)
        }
        var longest = 0
        var run = 0
        var prev: LocalDate? = null
        for (day in daysRidden) {
            run = if (prev != null && day == prev.plusDays(1)) run + 1 else 1
            longest = maxOf(longest, run)
            prev = day
        }
        return StreakInfo(current = current, longest = longest)
    }
}
