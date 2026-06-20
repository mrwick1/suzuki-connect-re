package dev.mrwick.redline.analytics

import dev.mrwick.redline.data.RideEntity
import dev.mrwick.redline.data.RideLocationEntity

/**
 * Coarse on-device route clustering.
 *
 * # Algorithm
 *
 * Each ride's GPS track is reduced to a **grid signature**: a set of
 * (gridRow, gridCol) cell keys obtained by snapping every lat/lng point to the
 * nearest cell in a fixed-size grid.  Two rides are considered to cover the
 * **same route** when the Jaccard index of their grid-cell sets exceeds a
 * similarity threshold.  Rides that meet this bar are merged into a cluster; a
 * new cluster is created for rides that match no existing cluster.
 *
 * # Why coarse grid, not precise geometry
 *
 * GPS traces drift by metres every run: a straight road appears as a slightly
 * different polyline each time.  Snapping to a ~100 m grid absorbs that noise
 * without needing a polyline-matching library or a network call.
 *
 * # Limitations (documented, not hidden)
 *
 * - Grid cells in India at ~100 m size correspond to roughly 0.001° of latitude
 *   and longitude.  That's the default [GRID_DEG] constant below.  Rides in
 *   hilly terrain may cross a cell boundary even on the same road; the threshold
 *   [SIMILARITY_THRESHOLD] accommodates this with a value well below 1.0.
 * - A ride with fewer than [MIN_LOCATION_SAMPLES] GPS points is treated as
 *   having **no route** and is placed in the special cluster id
 *   [CLUSTER_ID_UNTRACKED].
 * - Rides are greedy-assigned: the first existing cluster whose representative
 *   exceeds the threshold wins.  A future wave can refine this with a proper
 *   union-find or medoid approach; for now the ordering is deterministic
 *   (chronological by rideId).
 *
 * # Tuning knobs — all marked ASSUMED
 *
 * The constants below were chosen by reasoning about typical urban/suburban
 * Indian ride patterns on a 150cc commuter bike.  They have **not** been
 * calibrated against real captured tracks from the Gixxer SF 150 — treat them
 * as starting hypotheses and adjust once real ride data is available.
 */
object RouteClustering {

    /**
     * Cell size in degrees for the lat/lng grid.
     *
     * ASSUMED: 0.001° ≈ 111 m latitude / ~100 m longitude at 13°N (Bangalore).
     * Wide enough to absorb GPS drift on repeated runs of the same road; narrow
     * enough to distinguish parallel roads ~200 m apart.
     */
    const val GRID_DEG: Double = 0.001 // ASSUMED

    /**
     * Jaccard similarity threshold above which two rides are considered the same
     * route.  Jaccard = |A ∩ B| / |A ∪ B|.
     *
     * ASSUMED: 0.60 means at least 60 % of grid cells must overlap.  Accommodates
     * detours of up to ~40 % of the route length (traffic, fuel stop) while
     * keeping obviously different routes separate.
     */
    const val SIMILARITY_THRESHOLD: Double = 0.60 // ASSUMED

    /**
     * Minimum number of GPS location samples a ride must have to be assigned a
     * real cluster.  Rides below this limit are bucketed into [CLUSTER_ID_UNTRACKED].
     *
     * ASSUMED: 3 samples is a low bar that still requires the phone to have
     * acquired a lock and recorded some movement.  Very short test rides (e.g.
     * moving the bike out of the garage) legitimately have < 3 points and should
     * not pollute a route cluster.
     */
    const val MIN_LOCATION_SAMPLES: Int = 3 // ASSUMED

    /**
     * Sentinel cluster id for rides whose GPS track is too sparse to fingerprint.
     * Stored as [RouteCluster.clusterId] so callers can filter these out.
     */
    const val CLUSTER_ID_UNTRACKED: Int = -1

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Cluster a collection of rides by route similarity.
     *
     * [locationsForRide] is a function that returns the GPS track for a given
     * ride id.  It is called once per ride and must return points in any order
     * (order is irrelevant for the set-based signature).
     *
     * Rides with fewer than [MIN_LOCATION_SAMPLES] points are assigned
     * [CLUSTER_ID_UNTRACKED] and collected into a single untracked cluster.
     *
     * Returns all clusters (including the untracked one, if non-empty), each
     * containing at least one ride.  Clusters are ordered by descending ride
     * count (most-frequent route first), with the untracked cluster always last.
     */
    fun cluster(
        rides: List<RideEntity>,
        locationsForRide: (rideId: Long) -> List<RideLocationEntity>,
        gridDeg: Double = GRID_DEG,
        similarityThreshold: Double = SIMILARITY_THRESHOLD,
        minLocationSamples: Int = MIN_LOCATION_SAMPLES,
    ): List<RouteCluster> {
        require(gridDeg > 0.0) { "gridDeg must be positive" }
        require(similarityThreshold in 0.0..1.0) { "similarityThreshold must be in [0,1]" }
        require(minLocationSamples > 0) { "minLocationSamples must be positive" }

        if (rides.isEmpty()) return emptyList()

        // Sorted by id (chronological insertion order) for deterministic greedy
        // assignment; oldest ride "owns" its cluster.
        val sorted = rides.sortedBy { it.id }

        // Each element: mutable representative set + mutable member id list.
        // We use parallel lists so we can update both in place without awkward
        // data-class copy() semantics on the MutableList field.
        val clusterReps = mutableListOf<MutableSet<GridCell>>()
        val clusterMembers = mutableListOf<MutableList<Long>>()
        val untrackedIds = mutableListOf<Long>()

        for (ride in sorted) {
            val locs = locationsForRide(ride.id)
            if (locs.size < minLocationSamples) {
                untrackedIds += ride.id
                continue
            }
            val sig = signature(locs, gridDeg)
            if (sig.isEmpty()) {
                // All points collapsed to a single cell (zero-movement ride).
                untrackedIds += ride.id
                continue
            }

            // Find the first cluster whose representative overlaps enough.
            val matchIdx = clusterReps.indexOfFirst { rep ->
                jaccard(rep, sig) >= similarityThreshold
            }

            if (matchIdx >= 0) {
                // Absorb: grow the representative (union) and record the ride.
                clusterReps[matchIdx].addAll(sig)
                clusterMembers[matchIdx] += ride.id
            } else {
                clusterReps += sig.toHashSet()
                clusterMembers += mutableListOf(ride.id)
            }
        }

        // Build result list: real clusters sorted desc by ride count, untracked last.
        val real = clusterReps.indices
            .map { idx ->
                RouteCluster(
                    clusterId = idx,
                    rideIds = clusterMembers[idx].toList(),
                    representativeSignature = clusterReps[idx].toSet(),
                )
            }
            .sortedByDescending { it.rideIds.size }

        val result = real.toMutableList()
        if (untrackedIds.isNotEmpty()) {
            result += RouteCluster(
                clusterId = CLUSTER_ID_UNTRACKED,
                rideIds = untrackedIds.toList(),
                representativeSignature = emptySet(),
            )
        }
        return result
    }

    // -------------------------------------------------------------------------
    // Internal helpers (internal for test access)
    // -------------------------------------------------------------------------

    /** Snap [lat]/[lng] to a grid-cell key using [gridDeg]-sized cells. */
    internal fun cellOf(lat: Double, lng: Double, gridDeg: Double = GRID_DEG): GridCell {
        // Floor-divide: works for negative coordinates too (southern hemisphere /
        // west of prime meridian), though the bike lives in India (positive lat/lng).
        val row = floorDiv(lat, gridDeg)
        val col = floorDiv(lng, gridDeg)
        return GridCell(row, col)
    }

    /** Build the grid-cell signature (unique cell set) for a GPS track. */
    internal fun signature(
        locations: List<RideLocationEntity>,
        gridDeg: Double = GRID_DEG,
    ): Set<GridCell> = locations.mapTo(HashSet()) { cellOf(it.lat, it.lng, gridDeg) }

    /**
     * Jaccard similarity: |A ∩ B| / |A ∪ B|.  Returns 0.0 when both sets are
     * empty (avoids division by zero; two zero-point tracks are not considered
     * similar).
     */
    internal fun jaccard(a: Set<GridCell>, b: Set<GridCell>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val intersection = a.count { it in b }
        val union = a.size + b.size - intersection
        return if (union == 0) 0.0 else intersection.toDouble() / union.toDouble()
    }

    /** Integer floor-division for Double / Double, safe for negative values. */
    private fun floorDiv(value: Double, divisor: Double): Long {
        // Add a tiny epsilon before flooring so exact grid boundaries (e.g.
        // 12.971 / 0.001, which evaluates to 12970.9999999998 in binary FP) snap
        // to the intended cell instead of falling one short. Safe for negatives.
        return kotlin.math.floor(value / divisor + 1e-9).toLong()
    }
}

// -------------------------------------------------------------------------
// Data types declared here (not in AnalyticsModels.kt per wave-1 scope rule)
// -------------------------------------------------------------------------

/**
 * A coarse grid cell identified by its (row, col) indices, where:
 * - row = floor(lat / gridDeg)
 * - col = floor(lng / gridDeg)
 *
 * Used as the atomic element of a ride's route signature.
 */
data class GridCell(val row: Long, val col: Long)

/**
 * A group of rides that share the same coarse route.
 *
 * [clusterId] is an opaque non-negative integer assigned in order of first
 * appearance, or [RouteClustering.CLUSTER_ID_UNTRACKED] (-1) for the
 * special bucket of rides with no GPS track.
 *
 * [representativeSignature] is the union of all member ride signatures; it
 * grows as more rides join the cluster.  It is empty for the untracked cluster.
 *
 * [rideIds] lists member ride ids in the order they were assigned to the
 * cluster (chronological by ride id).
 */
data class RouteCluster(
    val clusterId: Int,
    val rideIds: List<Long>,
    val representativeSignature: Set<GridCell>,
)
