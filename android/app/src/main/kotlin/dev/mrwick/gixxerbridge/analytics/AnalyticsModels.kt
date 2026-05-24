package dev.mrwick.gixxerbridge.analytics

import androidx.compose.runtime.Immutable

/**
 * Aggregate totals over an arbitrary window (e.g. last 7 / 30 / 365 days).
 *
 * Distance is whole km because [dev.mrwick.gixxerbridge.data.RideEntity] stores
 * odometers as Int km. Hours is a Double because typical commute rides are well
 * under one hour.
 */
@Immutable
data class WeeklyTotal(
    val km: Int,
    val hours: Double,
    val rides: Int,
)

/**
 * One bucket of the speed histogram. The interval is half-open: a sample with
 * speed exactly equal to [highKmh] belongs to the next bucket.
 */
@Immutable
data class SpeedBucket(
    val lowKmh: Int,
    val highKmh: Int,
    val sampleCount: Int,
)

/**
 * One day in the calendar heatmap. [epochDay] is the day index since 1970-01-01
 * in the requested zone; [km] is the sum of distance from rides that *started*
 * that local day (a ride that crosses midnight counts entirely on its start day).
 */
@Immutable
data class CalendarDay(
    val epochDay: Long,
    val km: Int,
)

/**
 * UI-friendly per-ride summary used by the recent-rides charts.
 *
 * [fuelEconKml] is nullable because fuel-economy reporting depends on the bike
 * having moved enough for the cluster to compute it; brand-new rides usually
 * have no samples with a non-null fuelEconKml value.
 */
@Immutable
data class RideSummary(
    val rideId: Long,
    val date: Long,
    val km: Int,
    val maxSpeed: Int,
    val avgSpeed: Double,
    val fuelEconKml: Double?,
)

/**
 * Lifetime "personal best" markers. Each field is nullable so we can render a
 * placeholder when the history is empty (or no fuel-economy samples exist).
 */
@Immutable
data class PersonalBests(
    val longestRideKm: Int?,
    val topSpeedKmh: Int?,
    val bestFuelEconKml: Double?,
    val mostRidesInDay: Int?,
)
