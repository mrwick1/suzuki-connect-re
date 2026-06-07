# Route Repeat Detector — Commute Leaderboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cluster past rides by route similarity (a coarse lat/lng grid signature over each ride's GPS track), then rank the most-ridden routes by best / median / worst time and distance — a fully-offline, Strava-segment-style "commute leaderboard". No map service, no cloud.

**Architecture:** A pure `RouteClustering` object turns one ride's GPS track (`List<RideLocationEntity>`) into a coarse **route signature** (an ordered, de-duplicated set of snapped lat/lng grid cells), then groups rides whose signatures overlap above a Jaccard threshold into `RouteCluster`s. A second pure step (`RouteLeaderboard`) joins each clustered ride to its `RideEntity` (duration from timestamps, distance from odo delta) and produces ranked `RouteStats` (best/median/worst duration + distance, ride count, last-ridden). A modelled per-route fuel figure is computed via the existing `RideAnalytics.fuelBurnt(...)` (km/L × distance) and **labelled "est."** — the bike exposes no litres. The data plumbing lives in `RouteRepeatViewModel`, which loads each ride's locations through the existing `RideStore.getLocations(rideId)`. The UI is a new screen reachable from the Trips tab.

**Why pure-first:** Every other analytics unit in this app (`RideAnalytics`, `MileageAnalytics`, `RangeEstimator`, `BikeHealth`) is a side-effect-free JVM object with a JUnit4 test in `app/src/test/.../analytics/`. This feature follows the same split: all geometry/grouping/ranking is pure and unit-tested; only the I/O glue and Compose live in Android code.

**Tech Stack:** Kotlin, Jetpack Compose, Room (read-only — **no schema change**), JUnit4 (`org.junit`), Gradle.

**Research entry:** `docs/superpowers/research/2026-06-06-stats-and-features-research.md` → Top Picks #5 ("Route repeat detector — your commute, ranked", value 4 / effort 3).

---

## Open questions — UI placement / UX (HUMAN MUST DECIDE before Task 5)

These are genuine product decisions, not implementation details. The plan below assumes a **default** for each so the agent can proceed, but the human should confirm or override:

1. **Entry point & screen vs section.** DEFAULT ASSUMED: a new full screen `RouteRepeatScreen` on route `"routes"`, reached via a header action / overflow on the existing **Trips** tab (`TripsScreen`), mirroring how `mileage` / `service-history` are sub-routes off Stats. Alternatives the human may prefer: (a) a new top-level bottom-nav tab (the nav bar already has 5 tabs — adding a 6th is cramped); (b) an inline section/card at the top of `TripsScreen` showing only the top 1–2 routes with a "see all" link; (c) folding it into the Stats tab. **Decide which.**

2. **What "a route" is named.** There is no map/geocoding, so a cluster has no street name. DEFAULT ASSUMED: label each route by its ride count + a synthesized tag from the first clustered ride's `RideEntity.name` (already auto-generated, e.g. "Morning commute (Mon)"), e.g. "Route A · 14 rides · ~Morning commute". The human may instead want to let the user **rename a route** (would need a DataStore-keyed name map — see Task 4 note; no Room change).

3. **Minimum rides to surface a cluster.** DEFAULT ASSUMED: only show clusters with **≥ 2 rides** (a single-ride "cluster" is just a ride, already on the Trips list). Confirm the floor (2 vs 3).

4. **Ranking metric shown as the headline.** DEFAULT ASSUMED: median duration is the headline ("typical run"), with best/worst as secondary, since median is robust to one traffic-jam outlier. The human may prefer best-time ("fastest run") as the hero to match the Strava-segment framing in the research.

5. **Whether per-route modelled fuel/cost is shown at all.** DEFAULT ASSUMED: show modelled litres + (if `rupees` history exists) ₹ per run, clearly tagged "est." Given the no-assumptions rule and that fuel is fully modelled, the human may prefer to **omit fuel from v1** and keep the leaderboard to time + distance only.

---

## Feasibility caveats & on-bike-only verification

- **Coarse GPS → coarse signatures.** GPS is logged at `PRIORITY_BALANCED_POWER_ACCURACY` (~0.2 Hz, ~50 m spacing per `RideLocationEntity` docs at `RideStore.kt:65-93`). Two genuinely-different routes that share most of their length (e.g. same arterial road, different final turn) may merge into one cluster; conversely a detour or a GPS gap may split one commute into two clusters. The grid-cell size and Jaccard threshold are the tuning knobs (Task 1) and their *correct* values are **only verifiable against Arjun's real accumulated commute tracks on the physical phone** — synthetic unit tests prove the algorithm is correct, not that the thresholds match reality. Ship the thresholds as named constants and expect to tune after real rides accumulate.
- **Slow burn.** The feature is empty/uninteresting until a route has been ridden ≥ 2 (ideally ≥ 5) times. Nothing to verify on-bike except that real repeats eventually cluster.
- **Per-route fuel is MODELLED, not measured.** The bike gives only a 6-bar gauge — no litres (confirmed in research intro + `TelemetryFrame`). Any litres/₹ figure per route is `km/L × distance` via `RideAnalytics.fuelBurnt`, which already carries a `FuelBurnSource` label. It **must** be rendered with an "est." qualifier per the no-assumptions rule. This is a modelling choice, not bike data.
- **Distance source.** Use `RideEntity` odo delta (`endOdoKm − startOdoKm`) for distance, NOT the summed haversine of the GPS track — odo is the bike's ground truth (whole-km), GPS-sum would double-count jitter. (Haversine is used only inside signature generation, never as the reported distance.)
- **No on-bike action required to build/verify the logic** — it's pure analytics over already-logged history. Only threshold *tuning* needs real tracks.

## Room schema migration

**None.** `GixxerDatabase` uses `fallbackToDestructiveMigration` (research intro + `GixxerDatabase.kt`), so any new Room column would wipe all history. This feature is **100% read + recompute-on-read**: it reads existing `rides` + `ride_locations` rows via the existing DAO methods (`observeRides()`, `getLocations(rideId)`) and computes clusters in memory each time. The only persisted state that *might* be added is an optional per-route **display name** (open question #2) — if the human wants that, it goes in **DataStore (`Settings`)**, never Room. No new entity, no new column, no migration.

---

### Task 1: `RouteClustering` — signatures + clustering (pure) + tests

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/RouteClustering.kt`
- Test: `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/RouteClusteringTest.kt`

- [ ] **Step 1: Write the failing test**

Create `RouteClusteringTest.kt`. The tests use hand-built lat/lng point lists (no Room — just `RideLocationEntity` data objects with the geometry fields filled, others zero/null). Geometry is checked with small synthetic grids so the math is deterministic.

```kotlin
package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.RideLocationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Pure-JVM tests for [RouteClustering]. No Room, no Android. */
class RouteClusteringTest {

    /** Build a track from (lat,lng) pairs; non-geo fields are irrelevant here. */
    private fun track(vararg pts: Pair<Double, Double>): List<RideLocationEntity> =
        pts.mapIndexed { i, (lat, lng) ->
            RideLocationEntity(
                id = i.toLong(), rideId = 1, tMillis = i * 5_000L,
                lat = lat, lng = lng, altitudeM = null, accuracyM = 10f,
            )
        }

    // ---- signature ----

    @Test fun signatureSnapsNearbyPointsToSameCell() {
        // Two points ~10 m apart at a ~50 m grid collapse to one cell.
        val sig = RouteClustering.signature(track(12.9000 to 77.6000, 12.90005 to 77.60005))
        assertEquals(1, sig.cells.size)
    }

    @Test fun signatureKeepsDistinctCellsForSpacedPoints() {
        val sig = RouteClustering.signature(track(12.9000 to 77.6000, 12.9100 to 77.6100))
        assertEquals(2, sig.cells.size)
    }

    @Test fun signatureOfEmptyOrTinyTrackIsEmpty() {
        assertTrue(RouteClustering.signature(emptyList()).cells.isEmpty())
    }

    // ---- similarity (Jaccard over cell sets) ----

    @Test fun identicalTracksAreFullySimilar() {
        val a = RouteClustering.signature(track(12.90 to 77.60, 12.91 to 77.61, 12.92 to 77.62))
        assertEquals(1.0, RouteClustering.similarity(a, a), 0.0001)
    }

    @Test fun disjointTracksHaveZeroSimilarity() {
        val a = RouteClustering.signature(track(12.90 to 77.60, 12.91 to 77.61))
        val b = RouteClustering.signature(track(13.50 to 78.20, 13.51 to 78.21))
        assertEquals(0.0, RouteClustering.similarity(a, b), 0.0001)
    }

    @Test fun mostlyOverlappingTracksAreHighlySimilar() {
        val a = RouteClustering.signature(track(12.90 to 77.60, 12.91 to 77.61, 12.92 to 77.62, 12.93 to 77.63))
        // b shares the first 3 cells, diverges on the last.
        val b = RouteClustering.signature(track(12.90 to 77.60, 12.91 to 77.61, 12.92 to 77.62, 12.99 to 77.69))
        assertTrue(RouteClustering.similarity(a, b) > 0.5)
    }

    // ---- clustering ----

    @Test fun clustersMergesSimilarRoutesAndSeparatesDistinctOnes() {
        val commuteA1 = 10L to track(12.90 to 77.60, 12.91 to 77.61, 12.92 to 77.62)
        val commuteA2 = 11L to track(12.90 to 77.60, 12.91 to 77.61, 12.92 to 77.62)
        val weekend = 12L to track(13.10 to 77.90, 13.11 to 77.91, 13.12 to 77.92)

        val clusters = RouteClustering.cluster(
            listOf(commuteA1, commuteA2, weekend),
            threshold = 0.6,
        )
        // Two clusters: {10,11} and {12}.
        assertEquals(2, clusters.size)
        val sizes = clusters.map { it.rideIds.size }.sorted()
        assertEquals(listOf(1, 2), sizes)
        val commute = clusters.first { it.rideIds.size == 2 }
        assertTrue(commute.rideIds.containsAll(listOf(10L, 11L)))
    }

    @Test fun clusterIgnoresRidesWithEmptyTracks() {
        val clusters = RouteClustering.cluster(
            listOf(1L to emptyList(), 2L to track(12.90 to 77.60, 12.91 to 77.61)),
            threshold = 0.6,
        )
        // The empty-track ride is dropped; only the real one forms a (singleton) cluster.
        assertEquals(1, clusters.size)
        assertEquals(listOf(2L), clusters.single().rideIds)
    }

    @Test fun clusterIsDeterministicOrderByRideCountThenRecency() {
        val a = 1L to track(12.90 to 77.60, 12.91 to 77.61)
        val b = 2L to track(12.90 to 77.60, 12.91 to 77.61)
        val c = 3L to track(15.00 to 80.00, 15.01 to 80.01)
        val clusters = RouteClustering.cluster(listOf(a, b, c), threshold = 0.6)
        // Bigger cluster first.
        assertEquals(2, clusters.first().rideIds.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.RouteClusteringTest"`
Expected: FAIL — compilation error, `RouteClustering` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `RouteClustering.kt`. Keep it pure (no Android imports). Signature = ordered de-duplicated set of snapped grid cells; similarity = Jaccard over the cell *sets*; clustering = greedy single-linkage against representative signatures.

```kotlin
package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.RideLocationEntity
import kotlin.math.cos
import kotlin.math.roundToLong

/**
 * Pure geometry + grouping that turns ride GPS tracks into "route" clusters.
 *
 * A route SIGNATURE is the set of coarse lat/lng grid cells a track passes
 * through. Two rides are "the same route" when their signatures overlap above a
 * Jaccard threshold. Everything here is deterministic and side-effect free —
 * tested in RouteClusteringTest. No Android, no Room queries (callers pass the
 * already-loaded tracks).
 *
 * CAVEAT (see plan + research #5): GPS is logged at ~50 m spacing
 * (PRIORITY_BALANCED_POWER_ACCURACY), so signatures are coarse. [GRID_METERS]
 * and the clustering threshold are tuning knobs whose correct values can only be
 * validated against real accumulated commute tracks on-device, not in unit tests.
 */
object RouteClustering {

    /** Grid cell edge length in metres. ASSUMED ~60 m to sit above the ~50 m
     *  GPS spacing so jitter snaps together; tune on real tracks. */
    const val GRID_METERS: Double = 60.0

    /** Default Jaccard similarity to treat two rides as the same route.
     *  ASSUMED; tune on real tracks. */
    const val DEFAULT_THRESHOLD: Double = 0.6

    private const val METERS_PER_DEG_LAT = 111_320.0

    /** A coarse route fingerprint: the set of grid cells the track touches. */
    data class RouteSignature(val cells: Set<Long>)

    /** Snap one (lat,lng) to a grid-cell key. Longitude metres shrink by
     *  cos(lat); we anchor the lng scale at the point's own latitude (good
     *  enough at city scale where a commute spans << 1° of latitude). */
    private fun cellKey(lat: Double, lng: Double): Long {
        val latIdx = (lat * METERS_PER_DEG_LAT / GRID_METERS).roundToLong()
        val metersPerDegLng = METERS_PER_DEG_LAT * cos(Math.toRadians(lat))
        val lngIdx = (lng * metersPerDegLng / GRID_METERS).roundToLong()
        // Pack two ~21-bit-ranged indices into one Long (collision-free at city scale).
        return (latIdx shl 32) xor (lngIdx and 0xFFFFFFFFL)
    }

    /** Build the coarse signature for one ride's track. Empty track → empty set. */
    fun signature(track: List<RideLocationEntity>): RouteSignature {
        if (track.isEmpty()) return RouteSignature(emptySet())
        val cells = LinkedHashSet<Long>(track.size)
        for (p in track) cells += cellKey(p.lat, p.lng)
        return RouteSignature(cells)
    }

    /** Jaccard overlap of two signatures: |A∩B| / |A∪B|. 0 when either empty. */
    fun similarity(a: RouteSignature, b: RouteSignature): Double {
        if (a.cells.isEmpty() || b.cells.isEmpty()) return 0.0
        val inter = a.cells.count { it in b.cells }
        val union = a.cells.size + b.cells.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    /** One detected route: the ride ids that share it, plus its representative
     *  signature (the largest member's, used for membership tests). */
    data class RouteCluster(val rideIds: List<Long>, val signature: RouteSignature)

    /**
     * Greedy single-linkage clustering. For each ride (largest-track first, so
     * the richest signature becomes the representative), join the first existing
     * cluster whose representative is similar above [threshold]; else start a new
     * cluster. Rides with empty tracks are dropped.
     *
     * Result is ordered by descending ride count (most-ridden route first), ties
     * broken by the max ride id (proxy for recency) so output is deterministic.
     */
    fun cluster(
        tracks: List<Pair<Long, List<RideLocationEntity>>>,
        threshold: Double = DEFAULT_THRESHOLD,
    ): List<RouteCluster> {
        data class Acc(val ids: MutableList<Long>, var sig: RouteSignature)
        val accs = mutableListOf<Acc>()
        // Process rides whose signatures are richer first for stabler representatives.
        val sigs = tracks
            .map { (id, t) -> id to signature(t) }
            .filter { it.second.cells.isNotEmpty() }
            .sortedByDescending { it.second.cells.size }
        for ((id, sig) in sigs) {
            val hit = accs.firstOrNull { similarity(it.sig, sig) >= threshold }
            if (hit != null) hit.ids += id
            else accs += Acc(mutableListOf(id), sig)
        }
        return accs
            .map { RouteCluster(it.ids.sorted(), it.sig) }
            .sortedWith(
                compareByDescending<RouteCluster> { it.rideIds.size }
                    .thenByDescending { it.rideIds.maxOrNull() ?: 0L }
            )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.RouteClusteringTest"`
Expected: PASS (all tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/RouteClustering.kt android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/RouteClusteringTest.kt
git commit -m "feat(analytics): pure RouteClustering (coarse grid signatures + Jaccard clustering) with tests"
```

---

### Task 2: `RouteLeaderboard` — rank clusters into RouteStats (pure) + tests

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/RouteLeaderboard.kt`
- Add models to: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/AnalyticsModels.kt`
- Test: `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/RouteLeaderboardTest.kt`

This step joins `RouteCluster.rideIds` to `RideEntity` rows and computes best/median/worst duration + distance, ride count, last-ridden, and a **modelled** per-run fuel estimate (reusing `RideAnalytics.fuelBurnt`). Still pure: it takes `List<RideEntity>` + `List<RouteCluster>` as inputs, no I/O.

- [ ] **Step 1: Add the result model to `AnalyticsModels.kt`**

Append to `AnalyticsModels.kt`:

```kotlin
/**
 * One ranked route in the commute leaderboard. Durations are derived from
 * [dev.mrwick.gixxerbridge.data.RideEntity] start/end timestamps; distance from
 * the odo delta (the bike's whole-km ground truth, NOT the GPS-summed length).
 *
 * [modelledLitresPerRun] is an ESTIMATE only — the bike exposes no litres
 * (6-bar gauge), so this is km/L × distance via [RideAnalytics.fuelBurnt]. Null
 * when no km/L source is available. The UI MUST label it "est."
 */
@Immutable
data class RouteStats(
    val rideIds: List<Long>,
    val rideCount: Int,
    val typicalKm: Int,            // median distance
    val bestDurationMs: Long,
    val medianDurationMs: Long,
    val worstDurationMs: Long,
    val lastRiddenMillis: Long,
    val sampleRideName: String?,   // a member ride's auto-name, for a human-ish label
    val modelledLitresPerRun: Double?,
)
```

- [ ] **Step 2: Write the failing test**

Create `RouteLeaderboardTest.kt`:

```kotlin
package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.analytics.RouteClustering.RouteSignature
import dev.mrwick.gixxerbridge.data.RideEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [RouteLeaderboard]. No Room, no Android. */
class RouteLeaderboardTest {

    private val t0 = 1_750_000_000_000L
    private val min = 60_000L

    private fun ride(id: Long, startMin: Long, durMin: Long, startOdo: Int, km: Int, name: String? = null) =
        RideEntity(
            id = id, startedAtMillis = t0 + startMin * min,
            endedAtMillis = t0 + startMin * min + durMin * min,
            startOdoKm = startOdo, endOdoKm = startOdo + km,
            maxSpeedKmh = 60, avgSpeedKmh = 30.0, sampleCount = 10,
            fuelBarsStart = 4, fuelBarsEnd = 3, name = name,
        )

    private fun clusterOf(vararg ids: Long) =
        RouteClustering.RouteCluster(ids.toList(), RouteSignature(setOf(1L)))

    @Test fun computesBestMedianWorstAndTypicalKm() {
        val rides = listOf(
            ride(1, 0, 20, 1000, 10, name = "Morning commute"),
            ride(2, 100, 30, 1010, 10),
            ride(3, 200, 25, 1020, 12),
        )
        val stats = RouteLeaderboard.rank(
            rides = rides,
            clusters = listOf(clusterOf(1, 2, 3)),
            minRides = 2, fillKmPerL = 45.0, bikeKmPerL = null,
        )
        assertEquals(1, stats.size)
        val r = stats.single()
        assertEquals(3, r.rideCount)
        assertEquals(20 * min, r.bestDurationMs)
        assertEquals(25 * min, r.medianDurationMs) // middle of {20,25,30}
        assertEquals(30 * min, r.worstDurationMs)
        assertEquals(10, r.typicalKm)              // median of {10,10,12}
        assertEquals("Morning commute", r.sampleRideName)
    }

    @Test fun dropsClustersBelowMinRides() {
        val rides = listOf(ride(1, 0, 20, 1000, 10))
        val stats = RouteLeaderboard.rank(
            rides = rides, clusters = listOf(clusterOf(1)),
            minRides = 2, fillKmPerL = null, bikeKmPerL = null,
        )
        assertTrue(stats.isEmpty())
    }

    @Test fun modelledFuelUsesFillKmPerLAndIsNullWhenNoSource() {
        val rides = listOf(ride(1, 0, 20, 1000, 45), ride(2, 100, 22, 1100, 45))
        val withFuel = RouteLeaderboard.rank(
            rides, listOf(clusterOf(1, 2)), minRides = 2, fillKmPerL = 45.0, bikeKmPerL = null,
        ).single()
        assertEquals(1.0, withFuel.modelledLitresPerRun!!, 0.001) // 45 km / 45 km/L

        val noFuel = RouteLeaderboard.rank(
            rides, listOf(clusterOf(1, 2)), minRides = 2, fillKmPerL = null, bikeKmPerL = null,
        ).single()
        assertNull(noFuel.modelledLitresPerRun)
    }

    @Test fun ordersByRideCountDescending() {
        val rides = (1L..5L).map { ride(it, it * 100, 20, 1000 + it.toInt() * 10, 10) }
        val stats = RouteLeaderboard.rank(
            rides = rides,
            clusters = listOf(clusterOf(1, 2), clusterOf(3, 4, 5)),
            minRides = 2, fillKmPerL = null, bikeKmPerL = null,
        )
        assertEquals(3, stats.first().rideCount)
        assertEquals(2, stats.last().rideCount)
    }

    @Test fun lastRiddenIsMaxEndTimestamp() {
        val rides = listOf(ride(1, 0, 20, 1000, 10), ride(2, 500, 20, 1010, 10))
        val r = RouteLeaderboard.rank(
            rides, listOf(clusterOf(1, 2)), minRides = 2, fillKmPerL = null, bikeKmPerL = null,
        ).single()
        assertEquals(t0 + 520 * min, r.lastRiddenMillis)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.RouteLeaderboardTest"`
Expected: FAIL — `RouteLeaderboard` unresolved.

- [ ] **Step 4: Write minimal implementation**

Create `RouteLeaderboard.kt`:

```kotlin
package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.RideEntity
import kotlin.math.max

/**
 * Ranks [RouteClustering.RouteCluster]s into [RouteStats] for the leaderboard.
 *
 * Pure: takes the already-loaded ride rows + the clusters and computes
 * best/median/worst duration, typical (median) distance, ride count, last-
 * ridden, and a MODELLED per-run fuel estimate. No I/O — the VM does the
 * loading. Tested in RouteLeaderboardTest.
 */
object RouteLeaderboard {

    fun rank(
        rides: List<RideEntity>,
        clusters: List<RouteClustering.RouteCluster>,
        minRides: Int = 2,
        fillKmPerL: Double?,
        bikeKmPerL: Double?,
    ): List<RouteStats> {
        val byId = rides.associateBy { it.id }
        return clusters.mapNotNull { c ->
            // Only completed rides contribute duration/distance.
            val members = c.rideIds.mapNotNull { byId[it] }
                .filter { it.endedAtMillis != null && it.endOdoKm != null }
            if (members.size < minRides) return@mapNotNull null

            val durations = members
                .map { max(0L, it.endedAtMillis!! - it.startedAtMillis) }
                .sorted()
            val distances = members
                .map { max(0, it.endOdoKm!! - it.startOdoKm) }
                .sorted()

            val typicalKm = median(distances.map { it.toLong() }).toInt()
            val burn = RideAnalytics.fuelBurnt(
                distanceKm = typicalKm, fillKmPerL = fillKmPerL, bikeKmPerL = bikeKmPerL,
            )
            RouteStats(
                rideIds = members.map { it.id },
                rideCount = members.size,
                typicalKm = typicalKm,
                bestDurationMs = durations.first(),
                medianDurationMs = median(durations),
                worstDurationMs = durations.last(),
                lastRiddenMillis = members.maxOf { it.endedAtMillis!! },
                sampleRideName = members.firstNotNullOfOrNull { it.name },
                modelledLitresPerRun = burn?.litres,
            )
        }.sortedWith(
            compareByDescending<RouteStats> { it.rideCount }
                .thenByDescending { it.lastRiddenMillis }
        )
    }

    /** Median of a sorted (or unsorted) list of longs; 0 when empty. */
    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }
}
```

Note: `RideAnalytics.fuelBurnt` already exists (`RideAnalytics.kt:235`) and returns null for `distanceKm <= 0` or no km/L source — reuse it rather than re-deriving the model.

- [ ] **Step 5: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.RouteLeaderboardTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/RouteLeaderboard.kt android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/AnalyticsModels.kt android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/RouteLeaderboardTest.kt
git commit -m "feat(analytics): RouteLeaderboard ranks clusters into RouteStats (modelled fuel labelled) with tests"
```

---

### Task 3: `RideStore.getAllLocationsByRide` — batched track load (read-only)

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/RideStore.kt`

The current DAO only has per-ride `getLocations(rideId)`. Clustering needs every ride's track. Add ONE read query + a store helper. No schema change (read-only `@Query`). No unit test (Room DAO — exercised on-device + by the VM path); the pure logic is already tested in Tasks 1–2.

- [ ] **Step 1: Add the DAO query**

In `RideDao` (after `getLocations`, ~line 148), add:

```kotlin
    /** Fetch every GPS location across all rides, oldest-first per ride.
     *  Read-only; used by the route-repeat clustering to build per-ride tracks. */
    @Query("SELECT * FROM ride_locations ORDER BY rideId ASC, tMillis ASC")
    suspend fun getAllLocations(): List<RideLocationEntity>
```

- [ ] **Step 2: Add the store helper that groups by rideId**

In `RideStore` (after `getLocations`, ~line 326), add:

```kotlin
    /**
     * Load every ride's GPS track, grouped by rideId (each track oldest-first).
     * One query + an in-memory group-by — cheaper than N per-ride queries. Used
     * by the route-repeat leaderboard. Read-only; no schema impact.
     */
    suspend fun getAllLocationsByRide(): Map<Long, List<RideLocationEntity>> =
        dao.getAllLocations().groupBy { it.rideId }
```

- [ ] **Step 3: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (Room generates the new query at build time; a Room codegen error here would mean the SQL/return type is wrong.)

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/RideStore.kt
git commit -m "feat(data): read-only getAllLocationsByRide for route clustering (no schema change)"
```

---

### Task 4: `RouteRepeatViewModel` — wire store → clustering → leaderboard

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/routes/RouteRepeatViewModel.kt`

No JVM unit test (Android `ViewModel` + Room I/O). All non-trivial logic is the pure objects already tested in Tasks 1–2; this VM is glue. Verified by compile + on-device.

- [ ] **Step 1: Create the ViewModel**

Mirror `TripsViewModel` (`stateIn(viewModelScope, SharingStarted.Eagerly, …)`, `RideStore`/`FuelStore` from `GixxerDatabase.get(context)`). Clustering is moderately heavy (loads all locations), so compute it lazily off `Dispatchers.Default` and expose a `StateFlow<List<RouteStats>>`.

```kotlin
package dev.mrwick.gixxerbridge.ui.routes

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.gixxerbridge.analytics.MileageAnalytics
import dev.mrwick.gixxerbridge.analytics.RouteClustering
import dev.mrwick.gixxerbridge.analytics.RouteLeaderboard
import dev.mrwick.gixxerbridge.analytics.RouteStats
import dev.mrwick.gixxerbridge.data.FuelStore
import dev.mrwick.gixxerbridge.data.GixxerDatabase
import dev.mrwick.gixxerbridge.data.RideStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backs the route-repeat leaderboard. Loads ride history + all GPS tracks,
 * clusters by route signature ([RouteClustering]) and ranks ([RouteLeaderboard]).
 *
 * Heavy work (loading every location row + grouping) runs on Dispatchers.Default
 * via [refresh]; the screen calls [refresh] once on first composition. No Room
 * schema change — pure read + recompute.
 */
class RouteRepeatViewModel(context: Context) : ViewModel() {

    private val store = RideStore(GixxerDatabase.get(context).rideDao())
    private val fuelStore = FuelStore(GixxerDatabase.get(context).fuelFillDao())

    private val _routes = MutableStateFlow<List<RouteStats>?>(null) // null = not loaded yet
    /** Ranked routes; null while computing, empty list when no repeats exist. */
    val routes: StateFlow<List<RouteStats>?> = _routes.asStateFlow()

    fun refresh(minRides: Int = 2) {
        viewModelScope.launch {
            val rides = store.observeRides().first()
            val fills = fuelStore.observe().first()
            val tracksByRide = store.getAllLocationsByRide()
            val fillKmPerL = MileageAnalytics.averageKmPerL(fills)
            val result = withContext(Dispatchers.Default) {
                val tracks = rides.map { it.id to (tracksByRide[it.id] ?: emptyList()) }
                val clusters = RouteClustering.cluster(tracks)
                RouteLeaderboard.rank(
                    rides = rides,
                    clusters = clusters,
                    minRides = minRides,
                    fillKmPerL = fillKmPerL,
                    bikeKmPerL = null, // bike per-sample econ is a trip-average; omit here
                )
            }
            _routes.value = result
        }
    }
}
```

> **Open-question #2 hook (per-route rename):** if the human wants editable route names, add a `Settings`-backed `Map<signatureHash, String>` (DataStore, **not** Room) and merge it into `RouteStats.sampleRideName` here. Deferred until decided.

- [ ] **Step 2: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/routes/RouteRepeatViewModel.kt
git commit -m "feat(routes): RouteRepeatViewModel wires store -> clustering -> leaderboard"
```

---

### Task 5: `RouteRepeatScreen` + nav entry (UI)

> **BLOCKED on open questions #1–#5.** The steps below assume the DEFAULTS (new full screen on route `"routes"`, opened from a Trips header action; median-duration headline; modelled fuel shown with "est."; ≥ 2 rides). If the human chose differently, adjust placement/labels accordingly before implementing.

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/routes/RouteRepeatScreen.kt`
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/MainActivity.kt` (add `composable("routes")` + a nav lambda)
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/TripsScreen.kt` (header action → `onOpenRoutes`)

UI — verified by compile + on-device visual check (no Compose unit test; this app's screenshot tests are opt-in per-screen via Paparazzi and out of scope for v1).

- [ ] **Step 1: Build the screen**

Use the existing design system: `BentoTile` per route, `GixxerMono.display`/`HeroNumeral` for the headline number, `GixxerBrand.accent`/`GixxerBrand.zoneCool` for labels, `GixxerTokens.textMuted` for sub-labels, `EmptyState` (with `Icons.Outlined.Route`) for the no-repeats case, `SkeletonCard` while `routes == null`. Match `TripsScreen`'s header treatment (label-caps title + `HeroNumeral`).

Per-route tile contents (defaults):
- Headline: median duration, formatted `mm:ss`/`Xm` (typical run).
- Sub: `rideCount` runs · `typicalKm` km.
- Secondary row: best / worst duration ("fastest Xm · slowest Ym").
- Last-ridden relative date.
- If `modelledLitresPerRun != null`: `≈ X.X L/run (est.)` in `GixxerTokens.textMuted` — the "est." qualifier is mandatory.
- A human-ish label from `sampleRideName` when present.

Call `vm.refresh()` from a `LaunchedEffect(Unit)`. Handle three states: `null` → skeletons; `emptyList()` → `EmptyState("No repeated routes yet — ride the same way a few times.")`; non-empty → `LazyColumn` of route tiles.

- [ ] **Step 2: Add the nav route in `MainActivity`**

In the `NavHost` (alongside `composable("mileage")` / `composable("service-history")`, ~line 403), add:

```kotlin
                composable("routes") {
                    dev.mrwick.gixxerbridge.ui.routes.RouteRepeatScreen(
                        vm = viewModel { dev.mrwick.gixxerbridge.ui.routes.RouteRepeatViewModel(applicationContext) },
                        onOpenRide = { rideId -> nav.navigate("trip/$rideId") },
                    )
                }
```

(Use the same `viewModel { ... }` / context idiom already used for the other screen VMs in this file — match the exact pattern present at the `mileage`/`service-history` composables.)

- [ ] **Step 3: Add the entry point from Trips**

In `TripsScreen`, add an `onOpenRoutes: () -> Unit = {}` param and a small header action (icon button or a "Routes" text action next to the "TRIPS · THIS MONTH" header) that calls it. Wire `onOpenRoutes = { nav.navigate("routes") }` at the `composable(Tab.Trips.route)` call site in `MainActivity`.

- [ ] **Step 4: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Build, install, and visually verify on device**

Run: `cd android && ./gradlew :app:installDebug`
Expected: `Installed on 1 device.` Then: open Trips → tap the Routes action → the leaderboard shows clustered routes ranked by ride count, each with median/best/worst time, typical km, and (if fuel data exists) `≈ X L/run (est.)`. With < 2 repeats the empty state shows.

> **On-device threshold reality check (not a unit test):** with Arjun's real history, confirm distinct commutes don't all merge into one cluster and a single commute doesn't split into many. If they do, tune `RouteClustering.GRID_METERS` / `DEFAULT_THRESHOLD` and re-run. This is the on-bike/on-real-data verification the unit tests cannot provide.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/routes/RouteRepeatScreen.kt android/app/src/main/kotlin/dev/mrwick/gixxerbridge/MainActivity.kt android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/TripsScreen.kt
git commit -m "feat(routes): route-repeat leaderboard screen + Trips entry point"
```

---

## Self-Review

**Spec / research coverage:**
- Cluster rides by coarse lat/lng grid signature → Task 1 (`RouteClustering`) ✓
- Rank most-ridden routes by best/median/worst time + distance → Task 2 (`RouteLeaderboard` → `RouteStats`) ✓
- Fully offline, no map service → pure geometry, no network ✓
- Per-route fuel MODELLED + labelled "est." → Task 2 (reuses `RideAnalytics.fuelBurnt`) + Task 5 (mandatory "est." in UI) ✓
- Distance from odo delta, not GPS-sum → Task 2 ✓
- No Room schema migration (recompute-on-read + DataStore-only if names needed) → Task 3 read-only query, no entity/column ✓
- Pure analytics JVM-unit-tested (JUnit4) → Tasks 1 & 2 ✓
- UI verified by build + on-device → Task 5 ✓

**Open product decisions surfaced (not silently assumed):** entry point/screen-vs-section, route naming, min-rides floor, headline metric, whether to show modelled fuel — all listed at top with explicit DEFAULTS.

**Honesty under the no-assumptions rule:** every modelled/assumed value is flagged — `GRID_METERS`, `DEFAULT_THRESHOLD` (ASSUMED, tune on real tracks), per-route fuel (MODELLED, "est." mandatory). Threshold correctness is called out as on-real-data-only verification, distinct from "tests pass."

**Type consistency:** `RouteSignature(cells)`, `RouteCluster(rideIds, signature)`, `RouteStats(...)` used identically across impl + tests + VM. `RideStore.getAllLocationsByRide(): Map<Long, List<RideLocationEntity>>` matches the VM call. `RideAnalytics.fuelBurnt(distanceKm, fillKmPerL, bikeKmPerL)` matches its existing signature (`RideAnalytics.kt:235`).

**Placeholder scan:** no TBD/TODO in pure-logic tasks; the only deferred items are the explicitly-flagged UI open questions and the optional DataStore rename hook.
