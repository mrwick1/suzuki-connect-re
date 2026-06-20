package dev.mrwick.redline.analytics

import dev.mrwick.redline.data.RideEntity
import dev.mrwick.redline.data.RideLocationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [RouteClustering].
 *
 * No Room, no Android — all inputs are hand-crafted.  Coordinates are chosen so
 * expected cell membership is trivially verifiable by hand.
 *
 * Grid: GRID_DEG = 0.001°.  Cell (row, col) = (floor(lat/0.001), floor(lng/0.001)).
 * Example: lat=12.9716, lng=77.5946 → row=12971, col=77594.
 */
class RouteClusteringTest {

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    private val t0 = 1_750_000_000_000L // ~ 2025-06-15

    /** Minimal [RideEntity] for clustering; only [id] and odo fields matter here. */
    private fun ride(id: Long, startOdo: Int = 1000, distanceKm: Int = 10): RideEntity =
        RideEntity(
            id = id,
            startedAtMillis = t0 + id * 3_600_000L,
            endedAtMillis = t0 + id * 3_600_000L + 1_800_000L,
            startOdoKm = startOdo,
            endOdoKm = startOdo + distanceKm,
            maxSpeedKmh = 60,
            avgSpeedKmh = 35.0,
            sampleCount = 10,
            fuelBarsStart = 4,
            fuelBarsEnd = 3,
        )

    /** [RideLocationEntity] with all optional fields null (sufficient for clustering). */
    private fun loc(rideId: Long, lat: Double, lng: Double): RideLocationEntity =
        RideLocationEntity(
            id = 0,
            rideId = rideId,
            tMillis = t0,
            lat = lat,
            lng = lng,
            altitudeM = null,
            accuracyM = null,
        )

    /**
     * Build a simple linear GPS track near Bangalore (lat≈12.97, lng≈77.59) with
     * [n] points spaced [spacingDeg] apart along longitude.  Cells differ by 1
     * col per spacing of 0.001°, so [n=5, spacing=0.001] → 5 distinct cells.
     */
    private fun linearTrack(
        rideId: Long,
        lat: Double = 12.971,
        startLng: Double = 77.594,
        n: Int = 5,
        spacingDeg: Double = RouteClustering.GRID_DEG,
    ): List<RideLocationEntity> = (0 until n).map { i ->
        loc(rideId, lat, startLng + i * spacingDeg)
    }

    // -------------------------------------------------------------------------
    // cellOf
    // -------------------------------------------------------------------------

    @Test fun cellOfBasicPositiveCoordinate() {
        // lat=12.9716, lng=77.5946 → row=floor(12.9716/0.001)=12971, col=77594
        val cell = RouteClustering.cellOf(12.9716, 77.5946)
        assertEquals(GridCell(12971L, 77594L), cell)
    }

    @Test fun cellOfExactBoundaryFallsInLowerCell() {
        // Exact multiple → falls in that cell (not the one above it).
        val cell = RouteClustering.cellOf(12.971, 77.594)
        assertEquals(GridCell(12971L, 77594L), cell)
    }

    @Test fun cellOfNegativeLatCorrectFloor() {
        // lat=-12.9716 → floor(-12.9716 / 0.001) = floor(-12971.6) = -12972
        val cell = RouteClustering.cellOf(-12.9716, 77.5946)
        assertEquals(GridCell(-12972L, 77594L), cell)
    }

    @Test fun cellOfCustomGridDeg() {
        // grid=0.01 → coarser; lat=12.97 → row=floor(12.97/0.01)=1297
        val cell = RouteClustering.cellOf(12.97, 77.59, gridDeg = 0.01)
        assertEquals(GridCell(1297L, 7759L), cell)
    }

    // -------------------------------------------------------------------------
    // signature
    // -------------------------------------------------------------------------

    @Test fun signatureEmptyTrackIsEmptySet() {
        val sig = RouteClustering.signature(emptyList())
        assertTrue(sig.isEmpty())
    }

    @Test fun signatureDedupsDuplicateCells() {
        // Two points that map to the same cell → size-1 signature.
        val locs = listOf(
            loc(1, 12.9710, 77.5940),
            loc(1, 12.9711, 77.5940), // same cell (same 0.001° block)
        )
        val sig = RouteClustering.signature(locs)
        assertEquals(1, sig.size)
    }

    @Test fun signatureDistinctCellsForDistinctPoints() {
        val locs = linearTrack(rideId = 1, n = 5)
        val sig = RouteClustering.signature(locs)
        assertEquals(5, sig.size)
    }

    // -------------------------------------------------------------------------
    // jaccard
    // -------------------------------------------------------------------------

    @Test fun jaccardIdenticalSetsIsOne() {
        val a = setOf(GridCell(1, 1), GridCell(1, 2), GridCell(2, 1))
        assertEquals(1.0, RouteClustering.jaccard(a, a), 1e-9)
    }

    @Test fun jaccardDisjointSetsIsZero() {
        val a = setOf(GridCell(1, 1))
        val b = setOf(GridCell(9, 9))
        assertEquals(0.0, RouteClustering.jaccard(a, b), 1e-9)
    }

    @Test fun jaccardPartialOverlap() {
        // A={1,2,3,4}, B={3,4,5} → intersection={3,4}=2, union={1,2,3,4,5}=5 → 0.4
        val a = setOf(GridCell(0, 1), GridCell(0, 2), GridCell(0, 3), GridCell(0, 4))
        val b = setOf(GridCell(0, 3), GridCell(0, 4), GridCell(0, 5))
        assertEquals(2.0 / 5.0, RouteClustering.jaccard(a, b), 1e-9)
    }

    @Test fun jaccardBothEmptySetsIsZero() {
        assertEquals(0.0, RouteClustering.jaccard(emptySet(), emptySet()), 1e-9)
    }

    @Test fun jaccardOneEmptySetIsZero() {
        val a = setOf(GridCell(1, 1))
        assertEquals(0.0, RouteClustering.jaccard(a, emptySet()), 1e-9)
        assertEquals(0.0, RouteClustering.jaccard(emptySet(), a), 1e-9)
    }

    // -------------------------------------------------------------------------
    // cluster — basic contract
    // -------------------------------------------------------------------------

    @Test fun clusterEmptyRidesReturnsEmpty() {
        val result = RouteClustering.cluster(emptyList(), locationsForRide = { emptyList() })
        assertTrue(result.isEmpty())
    }

    @Test fun clusterSingleTrackedRideFormsSingleCluster() {
        val ride = ride(id = 1)
        val locs = linearTrack(rideId = 1, n = 5)
        val result = RouteClustering.cluster(listOf(ride), locationsForRide = { locs })
        assertEquals(1, result.size)
        assertEquals(0, result[0].clusterId)
        assertEquals(listOf(1L), result[0].rideIds)
    }

    @Test fun clusterRideWithTooFewPointsGoesToUntracked() {
        val ride = ride(id = 1)
        // Only 1 location point < MIN_LOCATION_SAMPLES (3)
        val locs = listOf(loc(1, 12.971, 77.594))
        val result = RouteClustering.cluster(
            listOf(ride),
            locationsForRide = { locs },
            minLocationSamples = RouteClustering.MIN_LOCATION_SAMPLES,
        )
        assertEquals(1, result.size)
        assertEquals(RouteClustering.CLUSTER_ID_UNTRACKED, result[0].clusterId)
        assertEquals(listOf(1L), result[0].rideIds)
    }

    @Test fun clusterUntrackedClusterIsAlwaysLast() {
        val tracked1 = ride(id = 1)
        val tracked2 = ride(id = 2)
        val untracked = ride(id = 3)
        val baseLocs = linearTrack(rideId = 1, n = 5)

        val result = RouteClustering.cluster(
            listOf(tracked1, tracked2, untracked),
            locationsForRide = { id ->
                if (id == 3L) listOf(loc(3, 12.971, 77.594)) // 1 point, < min
                else baseLocs.map { it.copy(rideId = id) }
            },
        )

        assertTrue(result.last().clusterId == RouteClustering.CLUSTER_ID_UNTRACKED)
    }

    // -------------------------------------------------------------------------
    // cluster — same-route merging
    // -------------------------------------------------------------------------

    @Test fun clusterTwoIdenticalRoutesFormOneCluster() {
        val ride1 = ride(id = 1)
        val ride2 = ride(id = 2)
        val locs1 = linearTrack(rideId = 1, n = 6)
        // Same track for ride2 (identical cells)
        val locs2 = linearTrack(rideId = 2, n = 6)

        val result = RouteClustering.cluster(
            listOf(ride1, ride2),
            locationsForRide = { id -> if (id == 1L) locs1 else locs2 },
        )

        // One real cluster; no untracked cluster.
        assertEquals(1, result.size)
        assertEquals(2, result[0].rideIds.size)
        assertTrue(result[0].rideIds.containsAll(listOf(1L, 2L)))
    }

    @Test fun clusterTwoCompletelyDifferentRoutesFormTwoClusters() {
        val ride1 = ride(id = 1)
        val ride2 = ride(id = 2)
        // Route A: cells along lng ~77.594
        val locsA = linearTrack(rideId = 1, lat = 12.971, startLng = 77.594, n = 5)
        // Route B: cells far away — no overlap with A
        val locsB = linearTrack(rideId = 2, lat = 13.100, startLng = 77.700, n = 5)

        val result = RouteClustering.cluster(
            listOf(ride1, ride2),
            locationsForRide = { id -> if (id == 1L) locsA else locsB },
        )

        assertEquals(2, result.size)
        assertEquals(1, result[0].rideIds.size)
        assertEquals(1, result[1].rideIds.size)
    }

    @Test fun clusterPartialOverlapAboveThresholdMerges() {
        // 6 shared cells + 2 extra in ride2 → Jaccard = 6/8 = 0.75 > 0.60
        val ride1 = ride(id = 1)
        val ride2 = ride(id = 2)
        val locsA = linearTrack(rideId = 1, n = 6, startLng = 77.594)
        // Ride2: same 6 cells + 2 continuation cells
        val locsB = linearTrack(rideId = 2, n = 8, startLng = 77.594)

        val result = RouteClustering.cluster(
            listOf(ride1, ride2),
            locationsForRide = { id -> if (id == 1L) locsA else locsB },
        )

        assertEquals(1, result.size) // merged
        assertEquals(2, result[0].rideIds.size)
    }

    @Test fun clusterPartialOverlapBelowThresholdSeparates() {
        // locsA cols: 77594, 77595, 77596, 77597, 77598 (5 cells)
        // locsB cols: 77597, 77598, 77599, 77600, 77601 (5 cells)
        // Intersection: {77597, 77598} = 2 cells
        // Union = 5 + 5 - 2 = 8 cells → Jaccard = 2/8 = 0.25 < 0.60
        val ride1 = ride(id = 1)
        val ride2 = ride(id = 2)
        val locsA = linearTrack(rideId = 1, n = 5, startLng = 77.594)
        val locsB = linearTrack(rideId = 2, n = 5, startLng = 77.597)

        val result = RouteClustering.cluster(
            listOf(ride1, ride2),
            locationsForRide = { id -> if (id == 1L) locsA else locsB },
            similarityThreshold = 0.60,
        )

        // Jaccard 0.25 < 0.60 → two separate clusters.
        assertEquals(2, result.size)
    }

    @Test fun clusterRepresentativeGrowsAsRidesJoin() {
        // Ride 1: cells {A, B, C}
        // Ride 2: cells {B, C, D} → Jaccard(rep={A,B,C}, sig={B,C,D}) = 2/4 = 0.5 < 0.60
        // With threshold 0.50 they merge; rep becomes {A,B,C,D}.
        // Ride 3: cells {C, D, E} → Jaccard({A,B,C,D}, {C,D,E}) = 2/5 = 0.4 < 0.50 → new cluster
        // But Jaccard({A,B,C,D}, {C,D,E}) = 2/5... let's use threshold=0.40 to force merge.
        val ride1 = ride(id = 1)
        val ride2 = ride(id = 2)
        val ride3 = ride(id = 3)
        // makeLocs produces one GPS point per cell column value.
        // col=N → lat=N*0.001, lng=N*0.001 → cell=(N, N) under GRID_DEG=0.001.
        fun makeLocs(rideId: Long, cols: List<Long>) =
            cols.map { loc(rideId, it * 0.001, it * 0.001) }

        val locs1 = makeLocs(1, listOf(1, 2, 3))
        val locs2 = makeLocs(2, listOf(2, 3, 4))
        val locs3 = makeLocs(3, listOf(3, 4, 5))

        // With threshold=0.40:
        // Ride1→cluster0={1,2,3}
        // Ride2: J({1,2,3},{2,3,4})=2/4=0.50≥0.40 → merges; rep={1,2,3,4}
        // Ride3: J({1,2,3,4},{3,4,5})=2/5=0.40≥0.40 → merges; rep={1,2,3,4,5}
        val result = RouteClustering.cluster(
            listOf(ride1, ride2, ride3),
            locationsForRide = { id ->
                when (id) { 1L -> locs1; 2L -> locs2; else -> locs3 }
            },
            similarityThreshold = 0.40,
        )

        assertEquals(1, result.size)
        assertEquals(3, result[0].rideIds.size)
    }

    // -------------------------------------------------------------------------
    // cluster — ordering guarantee
    // -------------------------------------------------------------------------

    @Test fun clusterSortsByDescendingRunCount() {
        // Route A: 3 rides.  Route B: 1 ride.  A should come first.
        val ridesA = (1L..3L).map { ride(id = it) }
        val rideB = ride(id = 4)
        val locsA = linearTrack(rideId = 1, lat = 12.971, startLng = 77.594, n = 5)
        val locsB = linearTrack(rideId = 4, lat = 13.100, startLng = 77.700, n = 5)

        val allRides = ridesA + rideB
        val result = RouteClustering.cluster(
            allRides,
            locationsForRide = { id ->
                if (id <= 3L) locsA.map { it.copy(rideId = id) } else locsB
            },
        )

        assertEquals(2, result.size)
        assertEquals(3, result[0].rideIds.size) // most-ridden first
        assertEquals(1, result[1].rideIds.size)
    }

    // -------------------------------------------------------------------------
    // cluster — mixed tracked + untracked
    // -------------------------------------------------------------------------

    @Test fun clusterMixedTrackedUntrackedProducesCorrectStructure() {
        val tracked1 = ride(id = 1)
        val tracked2 = ride(id = 2)
        val sparse = ride(id = 3)   // only 1 GPS point → untracked

        val locs = linearTrack(rideId = 1, n = 5)

        val result = RouteClustering.cluster(
            listOf(tracked1, tracked2, sparse),
            locationsForRide = { id ->
                when (id) {
                    3L -> listOf(loc(3, 12.971, 77.594)) // 1 point
                    else -> locs.map { it.copy(rideId = id) }
                }
            },
        )

        // Expect: 1 real cluster (ids 1,2) + 1 untracked cluster (id 3).
        assertEquals(2, result.size)
        assertEquals(2, result[0].rideIds.size)
        assertEquals(RouteClustering.CLUSTER_ID_UNTRACKED, result[1].clusterId)
        assertEquals(listOf(3L), result[1].rideIds)
        assertTrue(result[1].representativeSignature.isEmpty())
    }
}
