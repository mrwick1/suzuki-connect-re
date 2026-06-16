package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.RideEntity
import dev.mrwick.gixxerbridge.data.RideSampleEntity
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max

/**
 * Pure analytics over Room-persisted ride history.
 *
 * Everything in here is deterministic, side-effect free, and JVM-only — there
 * are no Android imports — so tests in `src/test` can exercise it directly with
 * hand-crafted [RideEntity] / [RideSampleEntity] inputs.
 */
object RideAnalytics {

    /**
     * Totals for the last [days] days, where "day" is a fixed 86_400_000 ms
     * window (not calendar-day aligned — that's a deliberate trade-off for
     * simplicity since the totals row is "rolling N days", not "this calendar
     * month").
     *
     * Distance for an in-progress ride uses [RideEntity.startOdoKm] as both
     * start and end (so it contributes 0 km), since [RideEntity.endOdoKm] is
     * not known yet.
     */
    fun totalsFor(
        rides: List<RideEntity>,
        days: Long,
        now: Long = System.currentTimeMillis(),
    ): WeeklyTotal = totalsSince(rides, now - days * 86_400_000L)

    /**
     * Totals for the current calendar day in [zone] — from local midnight up to
     * [now]. Unlike [totalsFor] this is calendar-aligned, so a ride from
     * yesterday evening drops off at midnight instead of lingering in the count
     * for a further 24 h. This is what the Home "Today" figure uses.
     */
    fun totalsToday(
        rides: List<RideEntity>,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): WeeklyTotal {
        val startOfToday = Instant.ofEpochMilli(now).atZone(zone)
            .toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        return totalsSince(rides, startOfToday)
    }

    /** Totals over every ride started at or after [cutoffMillis]. */
    private fun totalsSince(rides: List<RideEntity>, cutoffMillis: Long): WeeklyTotal {
        val window = rides.filter { it.startedAtMillis >= cutoffMillis }
        val km = window.sumOf { ride ->
            val end = ride.endOdoKm ?: ride.startOdoKm
            max(0, end - ride.startOdoKm)
        }
        val hours = window.sumOf { ride ->
            val endedAt = ride.endedAtMillis ?: ride.startedAtMillis
            max(0L, endedAt - ride.startedAtMillis) / 3_600_000.0
        }
        return WeeklyTotal(km = km, hours = hours, rides = window.size)
    }

    /**
     * Bucket [samples] into a histogram of [bucketSizeKmh]-wide bins from 0 up
     * to (but not including) [maxKmh].
     *
     * Samples above [maxKmh] are clamped into the top bucket so they remain
     * visible (a ride that hits 130 km/h with maxKmh=120 would otherwise just
     * disappear off the chart, which is misleading for a "speed distribution"
     * view).
     */
    fun speedHistogram(
        samples: List<RideSampleEntity>,
        bucketSizeKmh: Int = 10,
        maxKmh: Int = 150,
    ): List<SpeedBucket> {
        require(bucketSizeKmh > 0) { "bucketSizeKmh must be positive" }
        require(maxKmh > 0) { "maxKmh must be positive" }
        val bucketCount = (maxKmh + bucketSizeKmh - 1) / bucketSizeKmh
        val counts = IntArray(bucketCount)
        for (s in samples) {
            val clamped = s.speedKmh.coerceIn(0, maxKmh - 1)
            val idx = (clamped / bucketSizeKmh).coerceIn(0, bucketCount - 1)
            counts[idx]++
        }
        return (0 until bucketCount).map { i ->
            SpeedBucket(
                lowKmh = i * bucketSizeKmh,
                highKmh = (i + 1) * bucketSizeKmh,
                sampleCount = counts[i],
            )
        }
    }

    /**
     * Calendar heatmap data covering the last [weeks] full weeks ending today
     * (inclusive) in [zone]. Returns one [CalendarDay] per day in chronological
     * order (oldest first). Days with no rides have km = 0 so the heatmap can
     * still render a dim cell.
     */
    fun calendarMap(
        rides: List<RideEntity>,
        weeks: Int = 12,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<CalendarDay> {
        require(weeks > 0) { "weeks must be positive" }
        val totalDays = weeks * 7
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val startDay = today.minusDays((totalDays - 1).toLong())
        val perDay = HashMap<Long, Int>(totalDays)
        for (ride in rides) {
            val rideDay = Instant.ofEpochMilli(ride.startedAtMillis)
                .atZone(zone).toLocalDate()
            // Skip rides that started outside the visible window.
            if (rideDay.isBefore(startDay) || rideDay.isAfter(today)) continue
            val distance = max(0, (ride.endOdoKm ?: ride.startOdoKm) - ride.startOdoKm)
            val key = rideDay.toEpochDay()
            perDay[key] = (perDay[key] ?: 0) + distance
        }
        return (0 until totalDays).map { offset ->
            val day = startDay.plusDays(offset.toLong()).toEpochDay()
            CalendarDay(epochDay = day, km = perDay[day] ?: 0)
        }
    }

    /**
     * Lifetime "best" markers. Returns nulls if [rides] is empty (or, for
     * [PersonalBests.bestFuelEconKml], if no sample has a non-null
     * fuelEconKml value).
     *
     * "Most rides in a day" is computed in UTC days because we don't have a
     * zone here — for a single-rider single-bike app this is close enough to
     * the local-day grouping the user sees in the heatmap.
     */
    fun personalBests(
        rides: List<RideEntity>,
        samples: List<RideSampleEntity>,
    ): PersonalBests {
        if (rides.isEmpty()) {
            return PersonalBests(null, null, null, null)
        }
        val longest = rides.maxOf { r ->
            max(0, (r.endOdoKm ?: r.startOdoKm) - r.startOdoKm)
        }
        val topSpeed = rides.maxOf { it.maxSpeedKmh }
        val bestEcon = samples.mapNotNull { it.fuelEconKml }.maxOrNull()
        val ridesPerDay = rides.groupingBy { it.startedAtMillis / 86_400_000L }
            .eachCount()
        val mostInDay = ridesPerDay.values.maxOrNull()
        return PersonalBests(
            longestRideKm = longest,
            topSpeedKmh = topSpeed,
            bestFuelEconKml = bestEcon,
            mostRidesInDay = mostInDay,
        )
    }

    /**
     * Project one [RideEntity] (+ its samples) into a [RideSummary] for the
     * charts. [RideSummary.fuelEconKml] uses the *latest* sample that has a
     * non-null reading (the cluster's averaged value typically stabilises as
     * the ride progresses).
     */
    fun summarize(ride: RideEntity, samples: List<RideSampleEntity>): RideSummary {
        val km = max(0, (ride.endOdoKm ?: ride.startOdoKm) - ride.startOdoKm)
        // Samples are stored oldest-first; walk from the end to find the most
        // recent non-null fuel-econ reading.
        val fuelEcon = samples.asReversed().firstOrNull { it.fuelEconKml != null }?.fuelEconKml
        return RideSummary(
            rideId = ride.id,
            date = ride.startedAtMillis,
            km = km,
            maxSpeed = ride.maxSpeedKmh,
            avgSpeed = ride.avgSpeedKmh,
            fuelEconKml = fuelEcon,
        )
    }

    /** Total km per weekday (index 0 = Monday … 6 = Sunday) across all rides. */
    fun weekdayKm(
        rides: List<RideEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Int> {
        val out = IntArray(7)
        for (r in rides) {
            val dow = Instant.ofEpochMilli(r.startedAtMillis).atZone(zone).toLocalDate().dayOfWeek.value
            out[dow - 1] += max(0, (r.endOdoKm ?: r.startOdoKm) - r.startOdoKm)
        }
        return out.toList()
    }

    /** Total km per time-of-day bucket: [morning 5–11, afternoon 12–16, evening 17–20, night else]. */
    fun timeOfDayKm(
        rides: List<RideEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Int> {
        val out = IntArray(4)
        for (r in rides) {
            val hour = Instant.ofEpochMilli(r.startedAtMillis).atZone(zone).hour
            val idx = when (hour) {
                in 5..11 -> 0
                in 12..16 -> 1
                in 17..20 -> 2
                else -> 3
            }
            out[idx] += max(0, (r.endOdoKm ?: r.startOdoKm) - r.startOdoKm)
        }
        return out.toList()
    }

    /** Distance per calendar month for the last [months] months, oldest-first. */
    fun monthlyKm(
        rides: List<RideEntity>,
        months: Int = 6,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<MonthKm> {
        val firstMonth = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .withDayOfMonth(1).minusMonths((months - 1).toLong())
        val buckets = LinkedHashMap<String, Int>()
        for (i in 0 until months) {
            val m = firstMonth.plusMonths(i.toLong())
            buckets["%04d-%02d".format(m.year, m.monthValue)] = 0
        }
        for (r in rides) {
            val d = Instant.ofEpochMilli(r.startedAtMillis).atZone(zone).toLocalDate()
            val key = "%04d-%02d".format(d.year, d.monthValue)
            if (buckets.containsKey(key)) {
                buckets[key] = (buckets[key] ?: 0) + max(0, (r.endOdoKm ?: r.startOdoKm) - r.startOdoKm)
            }
        }
        return buckets.entries.map { MonthKm(it.key, it.value) }
    }

    /**
     * Average of the bike's own per-sample economy readings (km/L) over a ride.
     * Ignores null / non-positive readings. Null when no usable reading exists.
     */
    fun avgBikeEcon(samples: List<RideSampleEntity>): Double? {
        val vals = samples.mapNotNull { it.fuelEconKml }.filter { it > 0.0 }
        return if (vals.isEmpty()) null else vals.average()
    }

    /**
     * Estimate litres burnt over [distanceKm] given a km/L figure. Prefers the
     * rider's fill-measured mileage [fillKmPerL]; falls back to the bike's own
     * economy [bikeKmPerL]. Returns null when neither source is usable or the
     * ride covered no distance. [FuelBurn.source] records which figure was used
     * so the UI can label the estimate.
     */
    fun fuelBurnt(distanceKm: Int, fillKmPerL: Double?, bikeKmPerL: Double?): FuelBurn? {
        if (distanceKm <= 0) return null
        val fill = fillKmPerL?.takeIf { it > 0.0 }
        val bike = bikeKmPerL?.takeIf { it > 0.0 }
        val kmPerL = fill ?: bike ?: return null
        val source = if (fill != null) FuelBurnSource.FILLS else FuelBurnSource.BIKE
        return FuelBurn(litres = distanceKm / kmPerL, source = source)
    }
}

/** Distance ridden in one calendar month ("yyyy-MM"). */
data class MonthKm(val month: String, val km: Int)

/** Which km/L figure produced a [FuelBurn] estimate. */
enum class FuelBurnSource { FILLS, BIKE }

/** Estimated litres burnt over a ride + the source of the km/L used. */
data class FuelBurn(val litres: Double, val source: FuelBurnSource)
