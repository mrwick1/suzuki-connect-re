package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.RideEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [RouteLeaderboard] and its stat helpers.
 *
 * No Room, no Android — all inputs are hand-crafted [RideEntity] objects and
 * [RouteCluster] values built directly.
 *
 * Duration arithmetic:
 *   endedAtMillis - startedAtMillis (ms) / 1000 = seconds.
 * Distance arithmetic:
 *   endOdoKm - startOdoKm = km.
 */
class RouteLeaderboardTest {

    private val t0 = 1_750_000_000_000L // ~ 2025-06-15

    /** Build a completed [RideEntity] with explicit duration and distance. */
    private fun completedRide(
        id: Long,
        durationSec: Long,
        distanceKm: Int,
        startOdo: Int = 1000,
    ): RideEntity = RideEntity(
        id = id,
        startedAtMillis = t0 + id * 86_400_000L,
        endedAtMillis = t0 + id * 86_400_000L + durationSec * 1_000L,
        startOdoKm = startOdo,
        endOdoKm = startOdo + distanceKm,
        maxSpeedKmh = 60,
        avgSpeedKmh = 35.0,
        sampleCount = 10,
        fuelBarsStart = 4,
        fuelBarsEnd = 3,
    )

    /** Build an in-progress (no endedAtMillis / endOdoKm) ride. */
    private fun inProgressRide(id: Long): RideEntity = RideEntity(
        id = id,
        startedAtMillis = t0 + id * 86_400_000L,
        endedAtMillis = null,
        startOdoKm = 1000,
        endOdoKm = null,
        maxSpeedKmh = 0,
        avgSpeedKmh = 0.0,
        sampleCount = 0,
        fuelBarsStart = 4,
        fuelBarsEnd = null,
    )

    /** Build a [RouteCluster] with the given ride ids (no real signature needed here). */
    private fun cluster(id: Int, vararg rideIds: Long): RouteCluster =
        RouteCluster(clusterId = id, rideIds = rideIds.toList(), representativeSignature = emptySet())

    private val untrackedCluster: RouteCluster =
        RouteCluster(
            clusterId = RouteClustering.CLUSTER_ID_UNTRACKED,
            rideIds = listOf(99L),
            representativeSignature = emptySet(),
        )

    // -------------------------------------------------------------------------
    // medianLong
    // -------------------------------------------------------------------------

    @Test fun medianLongOddCount() {
        assertEquals(30L, RouteLeaderboard.medianLong(listOf(10L, 30L, 50L)))
    }

    @Test fun medianLongEvenCountAveragesTwoMiddle() {
        // sorted: [10, 20, 30, 40] → (20+30)/2 = 25
        assertEquals(25L, RouteLeaderboard.medianLong(listOf(40L, 10L, 30L, 20L)))
    }

    @Test fun medianLongSingleElement() {
        assertEquals(42L, RouteLeaderboard.medianLong(listOf(42L)))
    }

    // -------------------------------------------------------------------------
    // medianInt
    // -------------------------------------------------------------------------

    @Test fun medianIntOddCount() {
        assertEquals(3, RouteLeaderboard.medianInt(listOf(1, 3, 5)))
    }

    @Test fun medianIntEvenCountTruncates() {
        // (2+3)/2 = 2 (integer truncation)
        assertEquals(2, RouteLeaderboard.medianInt(listOf(1, 2, 3, 4)))
    }

    // -------------------------------------------------------------------------
    // coefficientOfVariationPct
    // -------------------------------------------------------------------------

    @Test fun cvPctNullForFewerThanTwoValues() {
        assertNull(RouteLeaderboard.coefficientOfVariationPct(emptyList()))
        assertNull(RouteLeaderboard.coefficientOfVariationPct(listOf(10.0)))
    }

    @Test fun cvPctZeroForConstantValues() {
        // All same → stddev=0 → cv=0%
        val result = RouteLeaderboard.coefficientOfVariationPct(listOf(100.0, 100.0, 100.0))
        assertEquals(0.0, result!!, 1e-9)
    }

    @Test fun cvPctNullWhenMeanIsZero() {
        assertNull(RouteLeaderboard.coefficientOfVariationPct(listOf(0.0, 0.0)))
    }

    @Test fun cvPctKnownValue() {
        // values=[10, 20] → mean=15, variance=((−5)²+(5)²)/2=25, stddev=5 → cv=5/15*100=33.3%
        val cv = RouteLeaderboard.coefficientOfVariationPct(listOf(10.0, 20.0))!!
        assertEquals(33.33, cv, 0.1)
    }

    // -------------------------------------------------------------------------
    // leaderboard — empty and edge cases
    // -------------------------------------------------------------------------

    @Test fun leaderboardEmptyClustersReturnsEmpty() {
        val result = RouteLeaderboard.leaderboard(emptyList(), emptyList())
        assertTrue(result.isEmpty())
    }

    @Test fun leaderboardRidesMissingFromRideListProduceZeroCount() {
        // Cluster references ride id 99 which doesn't exist in rides list.
        val c = cluster(0, 99L)
        val result = RouteLeaderboard.leaderboard(listOf(c), emptyList())
        assertEquals(1, result.size)
        assertEquals(0, result[0].runCount)
    }

    // -------------------------------------------------------------------------
    // leaderboard — single cluster, multiple runs
    // -------------------------------------------------------------------------

    @Test fun leaderboardSingleClusterThreeRuns() {
        val rides = listOf(
            completedRide(id = 1, durationSec = 1800L, distanceKm = 20),  // 30 min, 20 km
            completedRide(id = 2, durationSec = 2400L, distanceKm = 22),  // 40 min, 22 km
            completedRide(id = 3, durationSec = 3000L, distanceKm = 18),  // 50 min, 18 km
        )
        val c = cluster(0, 1L, 2L, 3L)
        val result = RouteLeaderboard.leaderboard(listOf(c), rides)

        assertEquals(1, result.size)
        val s = result[0]
        assertEquals(0, s.clusterId)
        assertEquals(3, s.runCount)
        // Durations sorted: [1800, 2400, 3000] → median = 2400
        assertEquals(2400L, s.medianDurationSec)
        assertEquals(1800L, s.bestDurationSec)   // fastest
        assertEquals(3000L, s.worstDurationSec)  // slowest
        // Distances sorted: [18, 20, 22] → median = 20
        assertEquals(20, s.medianDistanceKm)
        // Total: 20+22+18 = 60
        assertEquals(60, s.totalDistanceKm)
    }

    @Test fun leaderboardMedianDurationEvenCountOfRuns() {
        // 4 runs: durations 600, 900, 1200, 1500 → sorted → median = (900+1200)/2 = 1050
        val rides = listOf(
            completedRide(id = 1, durationSec = 1500L, distanceKm = 10),
            completedRide(id = 2, durationSec = 600L, distanceKm = 10),
            completedRide(id = 3, durationSec = 1200L, distanceKm = 10),
            completedRide(id = 4, durationSec = 900L, distanceKm = 10),
        )
        val c = cluster(0, 1L, 2L, 3L, 4L)
        val result = RouteLeaderboard.leaderboard(listOf(c), rides)
        assertEquals(1050L, result[0].medianDurationSec)
    }

    // -------------------------------------------------------------------------
    // leaderboard — in-progress ride (no end timestamp)
    // -------------------------------------------------------------------------

    @Test fun leaderboardInProgressRideExcludedFromDurationAndDistance() {
        val rides = listOf(
            completedRide(id = 1, durationSec = 1200L, distanceKm = 15),
            inProgressRide(id = 2),
        )
        val c = cluster(0, 1L, 2L)
        val result = RouteLeaderboard.leaderboard(listOf(c), rides)

        val s = result[0]
        assertEquals(2, s.runCount) // both rides counted
        // Duration stats use only completed rides (id=1)
        assertEquals(1200L, s.medianDurationSec)
        assertEquals(1200L, s.bestDurationSec)
        assertEquals(1200L, s.worstDurationSec)
        // Distance from completed rides only
        assertEquals(15, s.medianDistanceKm)
        assertEquals(15, s.totalDistanceKm)
    }

    // -------------------------------------------------------------------------
    // leaderboard — fuel estimates
    // -------------------------------------------------------------------------

    @Test fun leaderboardFuelEstimatesNullWhenNoKmPerL() {
        val rides = listOf(completedRide(id = 1, durationSec = 1200L, distanceKm = 20))
        val c = cluster(0, 1L)
        val result = RouteLeaderboard.leaderboard(listOf(c), rides, kmPerL = null)
        assertNull(result[0].medianEstLitres)
        assertNull(result[0].medianEstCostRs)
    }

    @Test fun leaderboardFuelEstimatesComputedWhenKmPerLProvided() {
        // median distance = 20 km, kmPerL = 40 → 0.5 L
        // cost = 0.5 × 103 = 51.5 ₹ (default price)
        val rides = listOf(completedRide(id = 1, durationSec = 1200L, distanceKm = 20))
        val c = cluster(0, 1L)
        val result = RouteLeaderboard.leaderboard(listOf(c), rides, kmPerL = 40.0)
        assertEquals(0.5, result[0].medianEstLitres!!, 1e-9)
        assertEquals(0.5 * RouteLeaderboard.DEFAULT_FUEL_RS_PER_L, result[0].medianEstCostRs!!, 0.01)
    }

    @Test fun leaderboardFuelEstimatesUsesCustomFuelPrice() {
        val rides = listOf(completedRide(id = 1, durationSec = 1800L, distanceKm = 50))
        val c = cluster(0, 1L)
        // 50 km / 50 kmpl = 1 L → 1 × 110 = ₹110
        val result = RouteLeaderboard.leaderboard(listOf(c), rides, kmPerL = 50.0, fuelRsPerL = 110.0)
        assertEquals(1.0, result[0].medianEstLitres!!, 1e-9)
        assertEquals(110.0, result[0].medianEstCostRs!!, 0.01)
    }

    @Test fun leaderboardFuelEstimatesNullWhenKmPerLIsZero() {
        val rides = listOf(completedRide(id = 1, durationSec = 1200L, distanceKm = 20))
        val c = cluster(0, 1L)
        val result = RouteLeaderboard.leaderboard(listOf(c), rides, kmPerL = 0.0)
        assertNull(result[0].medianEstLitres)
        assertNull(result[0].medianEstCostRs)
    }

    // -------------------------------------------------------------------------
    // leaderboard — ordering
    // -------------------------------------------------------------------------

    @Test fun leaderboardSortedByDescendingRunCount() {
        val rides = (1L..4L).map { completedRide(it, durationSec = 1200L, distanceKm = 15) }
        // Cluster A: 3 rides; Cluster B: 1 ride.
        val clusterA = cluster(0, 1L, 2L, 3L)
        val clusterB = cluster(1, 4L)
        // Intentionally pass B before A to verify sorting.
        val result = RouteLeaderboard.leaderboard(listOf(clusterB, clusterA), rides)
        assertEquals(2, result.size)
        assertEquals(3, result[0].runCount) // A first
        assertEquals(1, result[1].runCount) // B second
    }

    @Test fun leaderboardUntrackedClusterIsAlwaysLast() {
        val rides = (1L..4L).map { completedRide(it, durationSec = 1200L, distanceKm = 15) } +
            completedRide(id = 99L, durationSec = 600L, distanceKm = 5)
        val clusterA = cluster(0, 1L, 2L, 3L, 4L) // 4 rides → would beat untracked
        val result = RouteLeaderboard.leaderboard(listOf(clusterA, untrackedCluster), rides)
        assertEquals(2, result.size)
        assertTrue(result.last().isUntracked)
        assertEquals(RouteClustering.CLUSTER_ID_UNTRACKED, result.last().clusterId)
    }

    // -------------------------------------------------------------------------
    // leaderboard — untracked cluster stats
    // -------------------------------------------------------------------------

    @Test fun leaderboardUntrackedClusterHasNullDurationStats() {
        val rides = listOf(completedRide(id = 99L, durationSec = 1800L, distanceKm = 10))
        val result = RouteLeaderboard.leaderboard(listOf(untrackedCluster), rides)
        assertEquals(1, result.size)
        val s = result[0]
        assertTrue(s.isUntracked)
        assertNull(s.medianDurationSec)
        assertNull(s.bestDurationSec)
        assertNull(s.worstDurationSec)
        assertNull(s.medianDistanceKm)
        assertNull(s.medianEstLitres)
        assertNull(s.medianEstCostRs)
        assertNull(s.durationCvPct)
        // totalDistanceKm IS computed for untracked
        assertEquals(10, s.totalDistanceKm)
    }

    // -------------------------------------------------------------------------
    // leaderboard — durationCvPct
    // -------------------------------------------------------------------------

    @Test fun leaderboardCvPctNullForSingleRun() {
        val rides = listOf(completedRide(id = 1, durationSec = 1800L, distanceKm = 20))
        val c = cluster(0, 1L)
        val result = RouteLeaderboard.leaderboard(listOf(c), rides)
        assertNull(result[0].durationCvPct)
    }

    @Test fun leaderboardCvPctNonNullForTwoRuns() {
        val rides = listOf(
            completedRide(id = 1, durationSec = 1000L, distanceKm = 15),
            completedRide(id = 2, durationSec = 2000L, distanceKm = 15),
        )
        val c = cluster(0, 1L, 2L)
        val result = RouteLeaderboard.leaderboard(listOf(c), rides)
        val cv = result[0].durationCvPct
        // mean=1500, stddev=500 → cv=33.3%
        assertTrue(cv != null && cv > 0.0)
        assertEquals(33.33, cv!!, 0.1)
    }

    // -------------------------------------------------------------------------
    // leaderboard — no completed rides in cluster
    // -------------------------------------------------------------------------

    @Test fun leaderboardAllInProgressRidesProducesNullStats() {
        val rides = listOf(inProgressRide(id = 1), inProgressRide(id = 2))
        val c = cluster(0, 1L, 2L)
        val result = RouteLeaderboard.leaderboard(listOf(c), rides)
        val s = result[0]
        assertEquals(2, s.runCount)
        assertNull(s.medianDurationSec)
        assertNull(s.bestDurationSec)
        assertNull(s.worstDurationSec)
        assertNull(s.medianDistanceKm)
        assertEquals(0, s.totalDistanceKm)
    }
}
