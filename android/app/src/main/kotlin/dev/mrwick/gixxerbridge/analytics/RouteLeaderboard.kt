package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.RideEntity
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Per-route leaderboard: rank clusters produced by [RouteClustering] by
 * various metrics (median duration, best/worst run, distance consistency, etc.)
 * and attach per-route fuel-cost estimates.
 *
 * # Inputs
 *
 * - A list of [RouteCluster] objects from [RouteClustering.cluster].
 * - The full ride history ([RideEntity] list) so individual ride stats can be
 *   looked up by id.
 * - An optional km/L figure used to estimate fuel cost per route run.
 *
 * # Fuel/cost estimates
 *
 * The bike exposes no litres counter over BLE, so any litre-based figure here
 * is an **estimate** derived from [kmPerL].  Every field that depends on it is
 * clearly named with the suffix `EstLitres` or `EstCostRs` and accompanied by
 * a KDoc note.  Callers must not treat these as authoritative measurements.
 *
 * # Pure-JVM
 *
 * No Android / Room imports.  All inputs are plain Kotlin/JVM types so tests
 * in `src/test` can run on the host JVM without an emulator.
 */
object RouteLeaderboard {

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Build a leaderboard entry for every cluster in [clusters].
     *
     * [rides] is the full ride list; rides not present in any cluster (e.g. the
     * in-progress ride) are silently ignored.
     *
     * [kmPerL] is used to compute per-route fuel estimates.  Pass null (or 0.0)
     * to skip fuel-cost columns — [RouteStats.medianEstLitres] and
     * [RouteStats.medianEstCostRs] will be null.
     *
     * [fuelRsPerL] is the price per litre in Indian Rupees used to convert the
     * litre estimate to a cost estimate.  Defaults to [DEFAULT_FUEL_RS_PER_L].
     *
     * The untracked cluster ([RouteClustering.CLUSTER_ID_UNTRACKED]) is included
     * in the output with its ride count and distance stats but with nulls for
     * duration stats (since untracked rides may be wildly different routes).
     *
     * Result is ordered by descending [RouteStats.runCount] (most-ridden route
     * first), with the untracked cluster always last regardless of run count.
     */
    fun leaderboard(
        clusters: List<RouteCluster>,
        rides: List<RideEntity>,
        kmPerL: Double? = null,
        fuelRsPerL: Double = DEFAULT_FUEL_RS_PER_L,
    ): List<RouteStats> {
        if (clusters.isEmpty()) return emptyList()

        val rideById: Map<Long, RideEntity> = rides.associateBy { it.id }
        val usableKmPerL = kmPerL?.takeIf { it > 0.0 }

        val real = clusters
            .filter { it.clusterId != RouteClustering.CLUSTER_ID_UNTRACKED }
            .map { cluster -> buildStats(cluster, rideById, usableKmPerL, fuelRsPerL) }
            .sortedByDescending { it.runCount }

        val untracked = clusters
            .firstOrNull { it.clusterId == RouteClustering.CLUSTER_ID_UNTRACKED }
            ?.let { buildUntrackedStats(it, rideById) }

        return if (untracked != null) real + untracked else real
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun buildStats(
        cluster: RouteCluster,
        rideById: Map<Long, RideEntity>,
        kmPerL: Double?,
        fuelRsPerL: Double,
    ): RouteStats {
        val members = cluster.rideIds.mapNotNull { rideById[it] }
        val runCount = members.size

        // Duration in seconds for each completed ride in the cluster.
        val durations: List<Long> = members
            .filter { it.endedAtMillis != null }
            .map { r -> max(0L, r.endedAtMillis!! - r.startedAtMillis) / 1_000L }

        // Distance in km for each completed ride.
        val distances: List<Int> = members
            .filter { it.endOdoKm != null }
            .map { r -> max(0, r.endOdoKm!! - r.startOdoKm) }

        val medianDurationSec = durations.takeIf { it.isNotEmpty() }?.let { medianLong(it) }
        val bestDurationSec = durations.minOrNull()
        val worstDurationSec = durations.maxOrNull()

        val medianDistanceKm = distances.takeIf { it.isNotEmpty() }?.let { medianInt(it) }
        val totalDistanceKm = distances.sum()

        // Fuel estimate: median distance × litres-per-km.
        val medianEstLitres: Double? = if (kmPerL != null && medianDistanceKm != null && medianDistanceKm > 0) {
            medianDistanceKm.toDouble() / kmPerL
        } else null

        val medianEstCostRs: Double? = if (medianEstLitres != null) {
            medianEstLitres * fuelRsPerL
        } else null

        // Pace consistency: how tightly clustered are the durations?
        val durationCvPct: Double? = coefficientOfVariationPct(durations.map { it.toDouble() })

        return RouteStats(
            clusterId = cluster.clusterId,
            runCount = runCount,
            medianDurationSec = medianDurationSec,
            bestDurationSec = bestDurationSec,
            worstDurationSec = worstDurationSec,
            medianDistanceKm = medianDistanceKm,
            totalDistanceKm = totalDistanceKm,
            medianEstLitres = medianEstLitres,
            medianEstCostRs = medianEstCostRs,
            durationCvPct = durationCvPct,
            isUntracked = false,
        )
    }

    /** Stats for the untracked cluster: distance/count only; durations are null (mixed routes). */
    private fun buildUntrackedStats(
        cluster: RouteCluster,
        rideById: Map<Long, RideEntity>,
    ): RouteStats {
        val members = cluster.rideIds.mapNotNull { rideById[it] }
        val totalDistanceKm = members.filter { it.endOdoKm != null }
            .sumOf { r -> max(0, r.endOdoKm!! - r.startOdoKm) }

        return RouteStats(
            clusterId = cluster.clusterId,
            runCount = members.size,
            medianDurationSec = null,
            bestDurationSec = null,
            worstDurationSec = null,
            medianDistanceKm = null,
            totalDistanceKm = totalDistanceKm,
            medianEstLitres = null,
            medianEstCostRs = null,
            durationCvPct = null,
            isUntracked = true,
        )
    }

    // -------------------------------------------------------------------------
    // Pure stat helpers (internal for test access)
    // -------------------------------------------------------------------------

    /** Median of a Long list.  Precondition: list is non-empty. */
    internal fun medianLong(values: List<Long>): Long {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            // Integer average of two middle values (truncates 0.5 — acceptable
            // for seconds-resolution durations).
            (sorted[mid - 1] + sorted[mid]) / 2L
        }
    }

    /** Median of an Int list.  Precondition: list is non-empty. */
    internal fun medianInt(values: List<Int>): Int {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2
        }
    }

    /**
     * Coefficient of variation as a percentage: (stddev / mean) × 100.
     *
     * Returns null when the list has fewer than 2 elements (undefined) or when
     * the mean is zero (division by zero).
     *
     * A low value (< 15 %) means duration is very consistent across runs — a
     * signal that the cluster is truly one repeating route under similar traffic.
     * A high value (> 40 %) may indicate the cluster merges multiple different
     * routes that happen to share many grid cells.
     *
     * ASSUMED: the 15 %/40 % thresholds are illustrative; the UI will expose
     * the raw percentage so the rider can judge.
     */
    internal fun coefficientOfVariationPct(values: List<Double>): Double? {
        if (values.size < 2) return null
        val mean = values.average()
        if (mean == 0.0) return null
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        val stddev = sqrt(variance)
        return (stddev / mean) * 100.0
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /**
     * Default petrol price per litre in Indian Rupees used when the caller does
     * not supply [fuelRsPerL].
     *
     * ASSUMED: ₹103/L is a representative value for Bangalore as of mid-2025.
     * This is NOT live-updated; it is a static fallback the caller should
     * override from a user-configurable setting.
     */
    const val DEFAULT_FUEL_RS_PER_L: Double = 103.0 // ASSUMED
}

// -------------------------------------------------------------------------
// Result type (declared here per wave-1 scope rule: no edits to AnalyticsModels.kt)
// -------------------------------------------------------------------------

/**
 * Leaderboard row for one route cluster.
 *
 * Duration fields are in **seconds** (Long) because the typical commute is
 * 10–60 min; storing as seconds avoids sub-second precision noise from the
 * millisecond timestamps in [RideEntity].
 *
 * Fields tagged **ESTIMATE** depend on a km/L figure that the bike does NOT
 * expose natively over BLE — they are derived from fill-measured or
 * app-averaged economy data supplied by the caller.  Do not display them
 * without appropriate labelling in the UI.
 */
data class RouteStats(
    /** Cluster id matching [RouteCluster.clusterId]. */
    val clusterId: Int,

    /** Number of rides assigned to this cluster. */
    val runCount: Int,

    /** Median end-to-end duration across completed rides, in seconds. */
    val medianDurationSec: Long?,

    /** Fastest completed run (minimum duration), in seconds. */
    val bestDurationSec: Long?,

    /** Slowest completed run (maximum duration), in seconds. */
    val worstDurationSec: Long?,

    /** Median end-to-end distance (km) across completed rides. */
    val medianDistanceKm: Int?,

    /** Total km accumulated across all runs of this route. */
    val totalDistanceKm: Int,

    /**
     * ESTIMATE: median litres consumed per run, derived from median distance /
     * caller-supplied km/L.  Null when km/L is unavailable or distance is zero.
     */
    val medianEstLitres: Double?,

    /**
     * ESTIMATE: median cost per run in Indian Rupees, derived from
     * [medianEstLitres] × fuelRsPerL.  Null when [medianEstLitres] is null.
     */
    val medianEstCostRs: Double?,

    /**
     * Coefficient of variation of run durations as a percentage.
     * Low values indicate consistent travel time (same road conditions each run).
     * Null when fewer than 2 completed runs exist.
     */
    val durationCvPct: Double?,

    /**
     * True for the catch-all untracked cluster ([RouteClustering.CLUSTER_ID_UNTRACKED]).
     * Duration / distance / fuel stats are null for this cluster.
     */
    val isUntracked: Boolean,
)
