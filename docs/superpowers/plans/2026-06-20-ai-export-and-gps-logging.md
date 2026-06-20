# AI Full-Data Export + Distance-Based GPS Logging — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Share everything for AI" whole-bike export (full-archive `.txt`) on the Trips screen, and make ride GPS actually record by switching the tracker to distance-based 100 m sampling plus an in-app location-permission row.

**Architecture:** Component A is a pure `AllBikeExporter` object (mirrors the existing `TripShareText`) that turns all rides + telemetry + fuel + service + derived analytics into one self-contained text report; `TripsViewModel` gathers the data and `TripsScreen` writes it to `cacheDir` and fires the same `FileProvider`/`ACTION_SEND` pipeline the per-trip share uses. Component B changes `RideLocationTracker` to distance-based sampling and surfaces a location permission row in Bike settings.

**Tech Stack:** Kotlin, Jetpack Compose, Room, Coroutines, Play Services Location (`FusedLocationProviderClient`), JUnit4 (JVM unit tests).

## Global Constraints

- Package root is `dev.mrwick.redline` (post-rename). Debug app id `dev.mrwick.redline.debug`.
- `AllBikeExporter` MUST be a pure function: no Android imports, no DB/Context, no clock calls — `now: Long` and `zone: ZoneId` are passed in. (Same contract as `export/TripShareText.kt`.)
- Privacy = include-everything (decision): bike MAC, `BikeInfo` serial/model, rider name all included. No redaction.
- GPS sampling distance = **100 m**, `Priority.PRIORITY_BALANCED_POWER_ACCURACY`.
- Merge handling (verified against real data): aggregate analytics + per-ride table use `rides.filter { it.parentRideId == null }` (merged parents + normals, 0 children); raw telemetry/GPS CSV use `rides.filter { !it.isMerged }` (normals + children — the rows that actually carry samples; merged parents have 0 samples).
- Every modelled/estimated value labelled `(est.)`; data-quality caveats stated inline (no-assumptions rule).
- Commit after each task. Personal git identity (`Arjun KR <arjunkrishnaraj123@gmail.com>`).
- Build/test from `android/`: `./gradlew :app:testDebugUnitTest` and `:app:compileDebugKotlin`.

---

## File Structure

New:
- `android/app/src/main/kotlin/dev/mrwick/redline/export/AllBikeExporter.kt` — pure whole-bike report builder.
- `android/app/src/test/kotlin/dev/mrwick/redline/export/AllBikeExporterTest.kt` — JVM unit tests.

Modify:
- `android/app/src/main/kotlin/dev/mrwick/redline/location/RideLocationTracker.kt` — distance-based 100 m.
- `android/app/src/main/kotlin/dev/mrwick/redline/ui/settings/PermissionRow.kt` — add `LocationPermissionRow`.
- `android/app/src/main/kotlin/dev/mrwick/redline/ui/settings/BikeSettingsScreen.kt` — add a "Location" section.
- `android/app/src/main/kotlin/dev/mrwick/redline/ui/trips/TripsViewModel.kt` — add `serviceLogStore` + `buildFullExportText()`.
- `android/app/src/main/kotlin/dev/mrwick/redline/ui/trips/TripsScreen.kt` — header share icon + share lambda.

---

## Verified reference signatures (used below — do not re-derive)

Data access:
- `RideStore.getAllRides(): List<RideEntity>` · `getSamples(rideId: Long): List<RideSampleEntity>` · `getLocations(rideId: Long): List<RideLocationEntity>` · `lastKnownOdometer(): Int?`
- `FuelStore.all(): List<FuelFillEntity>` · `ServiceLogStore.all(): List<ServiceLogEntity>`
- `Settings.fuelCapacityL: Flow<Double>` · `serviceIntervalKm: Flow<Int>` · `serviceSchedule: Flow<Map<ServiceItem, ServiceItemState>>` · `bikeMac: Flow<String?>` · `riderName: Flow<String>` · `lastTelemetry: Flow<LastTelemetry?>` (`LastTelemetry(odometerKm, fuelBars, kmPerL, tMillis)`). Read one-shot via `.first()`.
- `CsvExporter.rideSamplesToCsv(ride: RideEntity, samples: List<RideSampleEntity>): String` (header `timestamp_iso,t_millis,speed_kmh,odometer_km,trip_a_km,trip_b_km,fuel_bars,fuel_econ_kml`).
- `AppGraph.bikeInfo: StateFlow<BikeInfo?>` (`BikeInfo(manufacturer, modelNumber, serialNumber, firmwareRevision, …)` all `String?`).

Entities:
- `RideEntity(id, startedAtMillis, endedAtMillis: Long?, startOdoKm, endOdoKm: Int?, maxSpeedKmh, avgSpeedKmh: Double, sampleCount, fuelBarsStart: Int?, fuelBarsEnd: Int?, name: String?, parentRideId: Long?, isMerged: Boolean)`
- `RideLocationEntity(id, rideId, tMillis, lat: Double, lng: Double, altitudeM: Double?, accuracyM: Float?)`
- `FuelFillEntity(id, tMillis, odometerKm, litres: Double, rupees: Double?, note: String?)`
- `ServiceLogEntity(id, tMillis, odometerKm, type: String, rupees: Double?, notes: String?)`
- `ServiceItemState(item: ServiceItem, kmThreshold: Int?, daysThreshold, lastServiceDateMs: Long?, lastServiceOdoKm: Int?)`; `ServiceItem` has `.label: String`.

Analytics (all pure objects in `analytics/`):
- `MileageAnalytics.perTankKmPerL(fills): List<Pair<Long,Double>>` · `averageKmPerL(fills, count=5): Double?`
- `BikeHealth.compute(currentOdo: Int?, lastServiceOdo: Int, serviceIntervalKm: Int, fuelBars: Int?, rides, now): BikeHealthScore(total, service, fuel, connection, insufficientData, grade)`
- `CostAnalytics.stats(fills, count=5): CostStats?(rollingRupeesPerKm, lifetimeRupeesPerKm, pricedIntervals, totalIntervals)`
- `RunningCostAnalytics.cost(fills, services, fallbackDistanceKm): RunningCost?(distanceKm, fuelRupees, serviceRupees, totalRupees, rupeesPerKm, fuelFraction, serviceFraction)` · `monthlySpend(fills, services, months=6, now, zone): List<MonthSpend(month, fuelRupees, serviceRupees, totalRupees)>`
- `RideAnalytics.totalsFor(rides, days: Long, now): WeeklyTotal(km, hours, rides)` · `personalBests(rides, samples): PersonalBests(longestRideKm, topSpeedKmh, bestFuelEconKml, mostRidesInDay)` · `speedHistogram(samples, bucketSizeKmh=10, maxKmh=150): List<SpeedBucket(lowKmh, highKmh, sampleCount)>` · `movingIdleMinutes(samples, maxGapMs=15_000): Pair<Int,Int>` · `monthlyKm(rides, months=6, now, zone): List<MonthKm(month, km)>`
- `ServiceSchedule.mostOverdue(items: Collection<ServiceItemState>, currentOdoKm: Int?, now): ServiceScheduleHealth(perItem: List<ServiceItemHealth(state, daysRemaining: Int?, kmRemaining: Int?, remainingFraction: Double?)>, worst)`
- `ServiceEta.paceKmPerDay(rides, now): Double` · `forecast(health, kmPerDay, now): ServiceEtaForecast?(daysAway, dueAtMillis, gate, isOverdue)` · `formatRelative(f): String`
- `RideStreak.compute(rides, zone, today): StreakInfo(current, longest)`
- `FuelTankEstimator.estimate(fills, currentOdometerKm, avgKmPerL, bikeLiveKmPerL, bikeFuelBars, capacityL, fallbackKmPerL=…): FuelEstimate?(litresLeft, percent, rangeKm, kmPerLUsed, isRough)`
- `RouteClustering.cluster(rides, locationsForRide): List<RouteCluster(clusterId, rideIds, …)>` · `RouteLeaderboard.leaderboard(clusters, rides, kmPerL, fuelRsPerL=…): List<RouteStats(clusterId, runCount, medianDistanceKm, totalDistanceKm, medianDurationSec, …)>`

---

## Task 1: GPS tracker → distance-based 100 m

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/redline/location/RideLocationTracker.kt`

**Interfaces:**
- Produces: behavioural change only — `start()` now requests fixes every ~100 m of travel. No new public API.

- [ ] **Step 1: Edit the LocationRequest construction**

In `RideLocationTracker.kt`, replace the `start()` request builder and the
companion constants. New `start()` body (lines ~85–95) and companion:

```kotlin
    fun start(): Boolean {
        if (!hasFineLocation()) return false
        val req = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            INTERVAL_MS,
        )
            .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
            .setMinUpdateDistanceMeters(SMALLEST_DISPLACEMENT_M)
            .build()
        client.requestLocationUpdates(req, callback, Looper.getMainLooper())
        return true
    }
```

```kotlin
    private companion object {
        /**
         * Distance-based sampling: emit a fix only after ~100 m of travel.
         * At city/motorcycle speeds this yields a point every few seconds while
         * moving and NONE while stopped (saves battery, avoids drift clusters at
         * lights). Verified preferable to fixed-time sampling for route tracks.
         */
        const val SMALLEST_DISPLACEMENT_M = 100f
        /** Desired time cadence; the distance gate above is the real driver. */
        const val INTERVAL_MS = 3_000L
        /** Hard floor between fixes. */
        const val MIN_INTERVAL_MS = 2_000L
    }
```

Also update the stale class KDoc: replace the `ASSUMED ... 5 s cadence`
paragraph (lines ~51–53) with:

```kotlin
 * Distance-based sampling at SMALLEST_DISPLACEMENT_M (100 m) with
 * PRIORITY_BALANCED_POWER_ACCURACY: a fix roughly every 100 m of travel,
 * none while stopped. Raise to PRIORITY_HIGH_ACCURACY if tracks look jagged.
```

- [ ] **Step 2: Compile**

Run: `cd android && ./gradlew :app:compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`.

> No JVM unit test here: `LocationRequest.Builder` is a Play-Services Android
> API with no behaviour to assert off-device. Real behaviour is verified on
> hardware in Task 5.

- [ ] **Step 3: Commit**

```bash
cd /home/mrwick/coding/projects/suzuki-connect-re
git add android/app/src/main/kotlin/dev/mrwick/redline/location/RideLocationTracker.kt
git commit -m "feat(gps): distance-based 100 m ride location sampling"
```

---

## Task 2: Location permission row in Bike settings

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/redline/ui/settings/PermissionRow.kt`
- Modify: `android/app/src/main/kotlin/dev/mrwick/redline/ui/settings/BikeSettingsScreen.kt`

**Interfaces:**
- Produces: `@Composable fun LocationPermissionRow()` in package `dev.mrwick.redline.ui.settings`.
- Consumes: existing `@Composable fun PermissionRow(title, rationale, grantedRationale, isGranted: (Context)->Boolean, onGrant: ()->Unit)`.

- [ ] **Step 1: Add `LocationPermissionRow` to `PermissionRow.kt`**

Append this composable (mirrors the existing `SendSmsPermissionRow`):

```kotlin
@Composable
fun LocationPermissionRow() {
    var refreshTick by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ -> refreshTick++ }
    androidx.compose.runtime.key(refreshTick) {
        PermissionRow(
            title = "Location (GPS)",
            rationale = "Required — without this, rides are recorded with no GPS track.",
            grantedRationale = "Granted — GPS points are recorded along each ride (~every 100 m).",
            isGranted = { ctx ->
                androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx,
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            },
            onGrant = { launcher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION) },
        )
    }
}
```

> Uses fully-qualified names for `Manifest`/`ContextCompat`/`PackageManager` so
> no new imports are required. `rememberLauncherForActivityResult`,
> `ActivityResultContracts`, `remember`, `mutableStateOf` are already imported
> in this file (used by `SendSmsPermissionRow`).

- [ ] **Step 2: Add a "Location" section to `BikeSettingsScreen.kt`**

In the `LazyColumn` (after the existing `SettingsSection("Bike")` item, before
`SafetySection`), add:

```kotlin
    item {
        SettingsSection("Location") {
            LocationPermissionRow()
        }
    }
```

`SettingsSection` and `LocationPermissionRow` are in the same package
(`dev.mrwick.redline.ui.settings`) — no import needed.

- [ ] **Step 3: Compile**

Run: `cd android && ./gradlew :app:compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`.

> UI/permission rows have no existing unit tests (see `SendSmsPermissionRow`);
> verified on-device in Task 5.

- [ ] **Step 4: Commit**

```bash
cd /home/mrwick/coding/projects/suzuki-connect-re
git add android/app/src/main/kotlin/dev/mrwick/redline/ui/settings/PermissionRow.kt \
        android/app/src/main/kotlin/dev/mrwick/redline/ui/settings/BikeSettingsScreen.kt
git commit -m "feat(settings): location permission row to enable GPS ride tracks"
```

---

## Task 3: `AllBikeExporter` pure builder + tests

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/redline/export/AllBikeExporter.kt`
- Test: `android/app/src/test/kotlin/dev/mrwick/redline/export/AllBikeExporterTest.kt`

**Interfaces:**
- Produces:
```kotlin
fun AllBikeExporter.build(
    rides: List<RideEntity>,
    samplesByRide: Map<Long, List<RideSampleEntity>>,
    locationsByRide: Map<Long, List<RideLocationEntity>>,
    fills: List<FuelFillEntity>,
    services: List<ServiceLogEntity>,
    currentOdoKm: Int?,
    currentFuelBars: Int?,
    tankCapacityL: Double,
    serviceIntervalKm: Int,
    serviceSchedule: Map<ServiceItem, ServiceItemState>,
    bikeMac: String?,
    bikeInfo: BikeInfo?,
    riderName: String,
    zone: ZoneId,
    now: Long,
): String
```
- Consumes: all analytics signatures listed in the reference block above.

- [ ] **Step 1: Write the failing test**

Create `AllBikeExporterTest.kt`:

```kotlin
package dev.mrwick.redline.export

import dev.mrwick.redline.data.FuelFillEntity
import dev.mrwick.redline.data.RideEntity
import dev.mrwick.redline.data.RideLocationEntity
import dev.mrwick.redline.data.RideSampleEntity
import dev.mrwick.redline.data.ServiceLogEntity
import dev.mrwick.redline.data.ServiceItem
import dev.mrwick.redline.data.ServiceItemState
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertTrue

class AllBikeExporterTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    // Fixed clock: 2026-06-20T12:00:00 IST
    private val now = 1_781_000_000_000L

    private fun ride(
        id: Long, start: Long, end: Long?, startOdo: Int, endOdo: Int?,
        maxS: Int = 60, avgS: Double = 30.0, n: Int = 2,
        parent: Long? = null, merged: Boolean = false, name: String? = null,
    ) = RideEntity(
        id = id, startedAtMillis = start, endedAtMillis = end,
        startOdoKm = startOdo, endOdoKm = endOdo, maxSpeedKmh = maxS,
        avgSpeedKmh = avgS, sampleCount = n, fuelBarsStart = 6, fuelBarsEnd = 5,
        name = name, parentRideId = parent, isMerged = merged,
    )

    private fun sample(rid: Long, t: Long, speed: Int, odo: Int) =
        RideSampleEntity(
            id = 0, rideId = rid, tMillis = t, speedKmh = speed,
            odometerKm = odo, tripAKm = 1.0, tripBKm = 2.0,
            fuelBars = 5, fuelEconKml = 45.0,
        )

    private fun fixture(): String {
        val normal = ride(1, now - 86_400_000L, now - 86_000_000L, 16882, 16887)
        val parent = ride(98, now - 70_000_000L, now - 60_000_000L, 17355, 17683, merged = true)
        val child1 = ride(73, now - 70_000_000L, now - 69_000_000L, 17355, 17357, parent = 98)
        val child2 = ride(74, now - 69_000_000L, now - 60_000_000L, 17357, 17683, parent = 98)
        val rides = listOf(normal, parent, child1, child2)

        val samplesByRide = mapOf(
            1L to listOf(sample(1, now - 86_400_000L, 0, 16882), sample(1, now - 86_300_000L, 40, 16885)),
            73L to listOf(sample(73, now - 70_000_000L, 30, 17355)),
            74L to listOf(sample(74, now - 69_000_000L, 55, 17360)),
        )
        val locationsByRide = mapOf(
            74L to listOf(
                RideLocationEntity(0, 74, now - 69_000_000L, 11.2176, 75.8299, 12.0, 8f),
                RideLocationEntity(0, 74, now - 68_000_000L, 11.2148, 75.8290, 13.0, 7f),
            ),
        )
        val fills = listOf(
            FuelFillEntity(1, now - 1_200_000_000L, 16891, 9.49, 1098.56, null),
            FuelFillEntity(2, now - 600_000_000L, 17271, 7.73, 892.35, null),
            FuelFillEntity(3, now - 100_000_000L, 17749, 9.18, 1044.68, null),
        )
        val services = listOf(
            ServiceLogEntity(1, now - 2_000_000_000L, 15000, "Oil change", 450.0, "synthetic"),
        )
        val schedule = mapOf(
            ServiceItem.PERIODIC_SERVICE to ServiceItemState(
                item = ServiceItem.PERIODIC_SERVICE, kmThreshold = 3500,
                daysThreshold = 120, lastServiceDateMs = now - 2_000_000_000L,
                lastServiceOdoKm = 15000,
            ),
        )
        return AllBikeExporter.build(
            rides = rides,
            samplesByRide = samplesByRide,
            locationsByRide = locationsByRide,
            fills = fills,
            services = services,
            currentOdoKm = 17763,
            currentFuelBars = 5,
            tankCapacityL = 12.0,
            serviceIntervalKm = 3500,
            serviceSchedule = schedule,
            bikeMac = "AA:BB:CC:DD:EE:FF",
            bikeInfo = null,
            riderName = "Arjun",
            zone = zone,
            now = now,
        )
    }

    @Test fun `has all top-level sections`() {
        val out = fixture()
        listOf(
            "=== DATA QUALITY NOTES ===", "=== BIKE PROFILE ===", "=== HEALTH ===",
            "=== FUEL & MILEAGE ===", "=== RUNNING COST ===", "=== SERVICE ===",
            "=== PERFORMANCE ===", "=== HABITS ===", "=== PER-RIDE SUMMARY",
            "=== FUEL FILLS (CSV) ===", "=== SERVICE LOGS (CSV) ===",
            "=== GPS TRACKS (CSV) ===", "=== FULL TELEMETRY",
        ).forEach { assertTrue(out.contains(it), "missing section: $it") }
    }

    @Test fun `data quality notes warn about BLE econ over-read`() {
        assertTrue(fixture().contains("over-read", ignoreCase = true))
    }

    @Test fun `per-ride table counts top-level rides only (no children double-count)`() {
        // 1 normal + 1 merged parent = 2 top-level rides; children 73/74 excluded.
        val out = fixture()
        val tableHeader = out.substringAfter("=== PER-RIDE SUMMARY")
        assertTrue(tableHeader.contains("(2)"), "expected 2 top-level rides in header")
    }

    @Test fun `telemetry CSV includes child samples but not merged parent`() {
        val out = fixture()
        val tele = out.substringAfter("=== FULL TELEMETRY")
        assertTrue(tele.contains("ride 73"), "child 73 telemetry present")
        assertTrue(tele.contains("ride 74"), "child 74 telemetry present")
        assertTrue(!tele.contains("ride 98"), "merged parent 98 must be skipped (0 samples)")
    }

    @Test fun `fuel fills CSV has one row per fill`() {
        val out = fixture()
        val block = out.substringAfter("=== FUEL FILLS (CSV) ===").substringBefore("===")
        // 3 fills => 3 data rows (+1 header line)
        assertTrue(block.trim().lines().count { it.contains(",") } >= 4)
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*AllBikeExporterTest' --console=plain`
Expected: FAIL — `Unresolved reference: AllBikeExporter`.

- [ ] **Step 3: Implement `AllBikeExporter.kt`**

Create the file. Pure object; calls the analytics modules; no Android/DB/Context.

```kotlin
package dev.mrwick.redline.export

import dev.mrwick.redline.analytics.BikeHealth
import dev.mrwick.redline.analytics.CostAnalytics
import dev.mrwick.redline.analytics.FuelTankEstimator
import dev.mrwick.redline.analytics.MileageAnalytics
import dev.mrwick.redline.analytics.RideAnalytics
import dev.mrwick.redline.analytics.RideStreak
import dev.mrwick.redline.analytics.RouteClustering
import dev.mrwick.redline.analytics.RouteLeaderboard
import dev.mrwick.redline.analytics.RunningCostAnalytics
import dev.mrwick.redline.analytics.ServiceEta
import dev.mrwick.redline.analytics.ServiceSchedule
import dev.mrwick.redline.ble.BikeInfo
import dev.mrwick.redline.data.FuelFillEntity
import dev.mrwick.redline.data.RideEntity
import dev.mrwick.redline.data.RideLocationEntity
import dev.mrwick.redline.data.RideSampleEntity
import dev.mrwick.redline.data.ServiceItem
import dev.mrwick.redline.data.ServiceItemState
import dev.mrwick.redline.data.ServiceLogEntity
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

/**
 * Builds a single self-contained, LLM-ready "full archive" text report of the
 * whole bike — every ride, all telemetry, fuel/cost/service, and the derived
 * analytics — for upload to an AI assistant.
 *
 * Pure: no Android imports, no DB/Context, no clock calls. Deterministic given
 * its inputs (`now` + `zone` passed in). Estimates labelled "(est.)"; data
 * caveats stated inline (no-assumptions rule). Mirrors [TripShareText].
 */
object AllBikeExporter {

    private const val PREAMBLE =
        "Analyse my motorcycle's health, performance, fuel economy and running " +
            "cost from the complete data below. Data-quality notes are stated " +
            "inline — respect them (the BLE econ field is unreliable)."

    fun build(
        rides: List<RideEntity>,
        samplesByRide: Map<Long, List<RideSampleEntity>>,
        locationsByRide: Map<Long, List<RideLocationEntity>>,
        fills: List<FuelFillEntity>,
        services: List<ServiceLogEntity>,
        currentOdoKm: Int?,
        currentFuelBars: Int?,
        tankCapacityL: Double,
        serviceIntervalKm: Int,
        serviceSchedule: Map<ServiceItem, ServiceItemState>,
        bikeMac: String?,
        bikeInfo: BikeInfo?,
        riderName: String,
        zone: ZoneId,
        now: Long,
    ): String {
        val tz = TimeZone.getTimeZone(zone.id)
        val dt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).apply { timeZone = tz }
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
        val today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), zone)

        // Merge-aware ride partitions (verified against real data).
        val topLevel = rides.filter { it.parentRideId == null }          // parents + normals
        val dataRides = rides.filter { !it.isMerged }                    // normals + children (carry samples)
        val allSamples = dataRides.flatMap { samplesByRide[it.id].orEmpty() }

        val avgKmPerL = MileageAnalytics.averageKmPerL(fills)
        val perTank = MileageAnalytics.perTankKmPerL(fills)
        val lastSvcOdo = serviceSchedule[ServiceItem.PERIODIC_SERVICE]?.lastServiceOdoKm ?: 0
        val totalKm = topLevel.sumOf { r -> r.endOdoKm?.let { max(0, it - r.startOdoKm) } ?: 0 }
        val gpsRideCount = dataRides.count { locationsByRide[it.id].orEmpty().isNotEmpty() }

        return buildString {
            appendLine(PREAMBLE)
            appendLine()
            appendLine("Exported: ${dt.format(Date(now))}")
            appendLine("Rider: $riderName")
            appendLine()

            // ---- DATA QUALITY ----
            appendLine("=== DATA QUALITY NOTES ===")
            appendLine("- BLE cluster econ (fuelEconKml) OVER-READS ~30% on this bike; do NOT treat it as real mileage.")
            appendLine("- Only fill-measured km/L is trustworthy. Fuel fills logged: ${fills.size} (=> ${max(0, fills.size - 1)} measured interval(s)).")
            appendLine("- GPS coverage: $gpsRideCount of ${dataRides.size} recorded ride(s) have a track.")
            appendLine("- Service log entries: ${services.size}${if (services.isEmpty()) " (maintenance below is projection-only)" else ""}.")
            val first = topLevel.minByOrNull { it.startedAtMillis }?.startedAtMillis
            val lastEnd = topLevel.mapNotNull { it.endedAtMillis }.maxOrNull()
            appendLine("- Baseline: ${first?.let { d.format(Date(it)) } ?: "?"} → ${lastEnd?.let { d.format(Date(it)) } ?: "?"}, $totalKm km over ${topLevel.size} ride(s).")
            appendLine("- Merged journeys are counted once (parent), their segments excluded from totals but present in raw telemetry.")
            appendLine()

            // ---- BIKE PROFILE ----
            appendLine("=== BIKE PROFILE ===")
            appendLine("Current odometer : ${currentOdoKm?.let { "$it km" } ?: "(unknown)"}")
            appendLine("Tank capacity    : ${"%.1f".format(tankCapacityL)} L")
            appendLine("Service interval : $serviceIntervalKm km (legacy single-item)")
            appendLine("Bike MAC         : ${bikeMac ?: "(not paired)"}")
            if (bikeInfo != null) {
                appendLine("Manufacturer     : ${bikeInfo.manufacturer ?: "?"}")
                appendLine("Model            : ${bikeInfo.modelNumber ?: "?"}")
                appendLine("Serial           : ${bikeInfo.serialNumber ?: "?"}")
                appendLine("Firmware         : ${bikeInfo.firmwareRevision ?: "?"}")
            }
            appendLine()

            // ---- HEALTH ----
            val health = BikeHealth.compute(currentOdoKm, lastSvcOdo, serviceIntervalKm, currentFuelBars, topLevel, now)
            appendLine("=== HEALTH ===")
            appendLine("Score   : ${health.total}/100 (${health.grade})")
            appendLine("Sub     : service ${health.service}, fuel ${health.fuel}, connection ${health.connection}")
            if (health.insufficientData) appendLine("Note    : insufficient data for a confident score.")
            appendLine()

            // ---- FUEL & MILEAGE ----
            appendLine("=== FUEL & MILEAGE ===")
            appendLine("Avg mileage : ${avgKmPerL?.let { "${"%.1f".format(it)} km/L  [fill-measured, trustworthy]" } ?: "(need >= 2 fills)"}")
            if (perTank.isNotEmpty()) {
                appendLine("Per-tank km/L (fillId: km/L):")
                perTank.forEach { (fid, kmpl) -> appendLine("  - fill $fid: ${"%.1f".format(kmpl)}") }
            }
            val tank = FuelTankEstimator.estimate(
                fills = fills, currentOdometerKm = currentOdoKm, avgKmPerL = avgKmPerL,
                bikeLiveKmPerL = null, bikeFuelBars = currentFuelBars, capacityL = tankCapacityL,
            )
            if (tank != null) {
                appendLine("Current tank: ${"%.1f".format(tank.litresLeft)} L (${(tank.percent * 100).toInt()}%), range ~${tank.rangeKm.toInt()} km (est.)${if (tank.isRough) " [rough, pre-first-fill]" else ""}")
            }
            appendLine()

            // ---- RUNNING COST ----
            appendLine("=== RUNNING COST ===")
            val cost = CostAnalytics.stats(fills)
            if (cost != null) {
                appendLine("Fuel ₹/km : rolling ${"%.2f".format(cost.rollingRupeesPerKm)}, lifetime ${"%.2f".format(cost.lifetimeRupeesPerKm)}  [${cost.pricedIntervals}/${cost.totalIntervals} intervals priced]")
            } else {
                appendLine("Fuel ₹/km : (need >= 2 priced fills)")
            }
            val running = RunningCostAnalytics.cost(fills, services, fallbackDistanceKm = totalKm)
            if (running != null) {
                appendLine("Blended   : ₹${"%.2f".format(running.rupeesPerKm)}/km over ${running.distanceKm} km — fuel ₹${running.fuelRupees.toInt()} (${(running.fuelFraction * 100).toInt()}%), service ₹${running.serviceRupees.toInt()} (${(running.serviceFraction * 100).toInt()}%)")
            }
            val monthly = RunningCostAnalytics.monthlySpend(fills, services, now = now, zone = zone)
            if (monthly.isNotEmpty()) {
                appendLine("Monthly spend:")
                monthly.forEach { m -> appendLine("  - ${m.month}: ₹${m.totalRupees.toInt()} (fuel ₹${m.fuelRupees.toInt()}, service ₹${m.serviceRupees.toInt()})") }
            }
            appendLine()

            // ---- SERVICE ----
            appendLine("=== SERVICE ===")
            val pace = ServiceEta.paceKmPerDay(topLevel, now = now)
            appendLine("Riding pace : ${"%.1f".format(pace)} km/day (recent window)")
            val sched = ServiceSchedule.mostOverdue(serviceSchedule.values, currentOdoKm, now)
            if (sched.perItem.isEmpty()) {
                appendLine("(no service items configured)")
            } else {
                sched.perItem.forEach { h ->
                    val eta = ServiceEta.forecast(h, pace, now)
                    val km = h.kmRemaining?.let { "$it km" } ?: "—"
                    val days = h.daysRemaining?.let { "$it d" } ?: "—"
                    val etaStr = eta?.let { ServiceEta.formatRelative(it) + if (it.isOverdue) " (OVERDUE)" else "" } ?: "no baseline"
                    appendLine("  - ${h.state.item.label}: km left $km, days left $days, ETA $etaStr")
                }
            }
            appendLine()

            // ---- PERFORMANCE ----
            appendLine("=== PERFORMANCE ===")
            val lifetime = RideAnalytics.totalsFor(topLevel, days = 36_500L, now = now)
            appendLine("Totals  : ${lifetime.km} km, ${"%.1f".format(lifetime.hours)} h, ${lifetime.rides} rides")
            val pb = RideAnalytics.personalBests(topLevel, allSamples)
            appendLine("Bests   : longest ${pb.longestRideKm ?: "—"} km, top speed ${pb.topSpeedKmh ?: "—"} km/h, best econ ${pb.bestFuelEconKml?.let { "%.1f".format(it) } ?: "—"} km/L, most rides/day ${pb.mostRidesInDay ?: "—"}")
            val (mov, idle) = RideAnalytics.movingIdleMinutes(allSamples)
            appendLine("Time    : ${mov} min moving, ${idle} min idle (engine on, stopped)")
            val hist = RideAnalytics.speedHistogram(allSamples)
            val histStr = hist.filter { it.sampleCount > 0 }.joinToString(", ") { "${it.lowKmh}-${it.highKmh}:${it.sampleCount}" }
            if (histStr.isNotBlank()) appendLine("Speed   : [${histStr}] (sample counts per km/h bucket)")
            appendLine()

            // ---- HABITS ----
            appendLine("=== HABITS ===")
            val streak = RideStreak.compute(topLevel, zone, today)
            appendLine("Streak  : current ${streak.current} day(s), longest ${streak.longest}")
            val clusters = RouteClustering.cluster(topLevel) { id -> locationsByRide[id].orEmpty() }
            val board = RouteLeaderboard.leaderboard(clusters, topLevel, kmPerL = avgKmPerL)
            val tracked = board.filter { !it.isUntracked }
            if (tracked.isNotEmpty()) {
                appendLine("Routes  : ${tracked.size} recurring route(s)")
                tracked.take(5).forEach { rs ->
                    appendLine("  - route ${rs.clusterId}: ${rs.runCount} runs, median ${rs.medianDistanceKm ?: "—"} km")
                }
            } else {
                appendLine("Routes  : (none — needs more GPS-tracked rides)")
            }
            appendLine()

            // ---- PER-RIDE SUMMARY ----
            appendLine("=== PER-RIDE SUMMARY (${topLevel.size}) ===")
            appendLine("date | name | km | maxKmh | avgKmh | fuelBars | duration")
            topLevel.sortedBy { it.startedAtMillis }.forEach { r ->
                val km = r.endOdoKm?.let { max(0, it - r.startOdoKm) } ?: 0
                val dur = r.endedAtMillis?.let { fmtDur((it - r.startedAtMillis) / 1000L) } ?: "(in progress)"
                val tag = if (r.isMerged) " [merged journey]" else ""
                appendLine("${d.format(Date(r.startedAtMillis))} | ${r.name ?: "Ride #${r.id}"}$tag | $km | ${r.maxSpeedKmh} | ${"%.1f".format(r.avgSpeedKmh)} | ${r.fuelBarsStart ?: "?"}→${r.fuelBarsEnd ?: "?"} | $dur")
            }
            appendLine()

            // ---- CSV: FUEL FILLS ----
            appendLine("=== FUEL FILLS (CSV) ===")
            appendLine("t_iso,t_millis,odometer_km,litres,rupees,note")
            fills.sortedBy { it.tMillis }.forEach { f ->
                appendLine("${dt.format(Date(f.tMillis))},${f.tMillis},${f.odometerKm},${"%.2f".format(f.litres)},${f.rupees?.let { "%.2f".format(it) } ?: ""},${csv(f.note)}")
            }
            appendLine()

            // ---- CSV: SERVICE LOGS ----
            appendLine("=== SERVICE LOGS (CSV) ===")
            appendLine("t_iso,t_millis,odometer_km,type,rupees,notes")
            services.sortedBy { it.tMillis }.forEach { s ->
                appendLine("${dt.format(Date(s.tMillis))},${s.tMillis},${s.odometerKm},${csv(s.type)},${s.rupees?.let { "%.2f".format(it) } ?: ""},${csv(s.notes)}")
            }
            appendLine()

            // ---- CSV: GPS TRACKS ----
            appendLine("=== GPS TRACKS (CSV) ===")
            appendLine("ride_id,t_iso,t_millis,lat,lng,altitude_m,accuracy_m")
            dataRides.forEach { r ->
                locationsByRide[r.id].orEmpty().forEach { g ->
                    appendLine("${r.id},${dt.format(Date(g.tMillis))},${g.tMillis},${g.lat},${g.lng},${g.altitudeM ?: ""},${g.accuracyM ?: ""}")
                }
            }
            appendLine()

            // ---- CSV: FULL TELEMETRY (per ride, reuse CsvExporter) ----
            val teleTotal = dataRides.sumOf { samplesByRide[it.id].orEmpty().size }
            appendLine("=== FULL TELEMETRY (CSV, $teleTotal rows across ${dataRides.size} rides) ===")
            dataRides.sortedBy { it.startedAtMillis }.forEach { r ->
                val s = samplesByRide[r.id].orEmpty()
                if (s.isNotEmpty()) {
                    appendLine("--- ride ${r.id} (${r.name ?: "Ride #${r.id}"}) ---")
                    append(CsvExporter.rideSamplesToCsv(r, s))
                    appendLine()
                }
            }

            appendLine("---")
            appendLine("Source: Suzuki Gixxer SF 150 via REDLINE BLE logger")
        }.trimEnd()
    }

    private fun fmtDur(sec: Long): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, s)
    }

    /** Minimal CSV-escape for free-text fields. */
    private fun csv(v: String?): String {
        val t = v ?: return ""
        return if (t.contains(',') || t.contains('"') || t.contains('\n'))
            "\"" + t.replace("\"", "\"\"") + "\"" else t
    }
}
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*AllBikeExporterTest' --console=plain`
Expected: PASS (5 tests).

> If a property/function name mismatches (e.g. `ServiceItem.label`), the
> compiler will name it exactly — fix to the compiler's reported name and
> re-run. Do not guess past a compile error.

- [ ] **Step 5: Commit**

```bash
cd /home/mrwick/coding/projects/suzuki-connect-re
git add android/app/src/main/kotlin/dev/mrwick/redline/export/AllBikeExporter.kt \
        android/app/src/test/kotlin/dev/mrwick/redline/export/AllBikeExporterTest.kt
git commit -m "feat(export): AllBikeExporter — whole-bike full-archive report for AI"
```

---

## Task 4: ViewModel gather + Trips header share wiring

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/redline/ui/trips/TripsViewModel.kt`
- Modify: `android/app/src/main/kotlin/dev/mrwick/redline/ui/trips/TripsScreen.kt`

**Interfaces:**
- Produces: `suspend fun TripsViewModel.buildFullExportText(zone: ZoneId, now: Long): String`.
- Consumes: `AllBikeExporter.build(...)` (Task 3); `RideStore`, `FuelStore`, `ServiceLogStore`, `Settings`, `AppGraph.bikeInfo`.

- [ ] **Step 1: Add `serviceLogStore` + `buildFullExportText` to `TripsViewModel`**

Add the store field next to the existing `fuelStore`:

```kotlin
    private val serviceLogStore: ServiceLogStore =
        ServiceLogStore(GixxerDatabase.get(context).serviceLogDao())
    private val settings: Settings = AppGraph.settings(context)
```

> Confirmed: `AppGraph.settings(context): Settings` exists (singleton,
> `app/AppGraph.kt:107`) and `serviceLogDao()` exists on `GixxerDatabase`
> (`:45`), mirroring the existing `fuelStore` construction at
> `TripsViewModel.kt:44`.

Add the method (gathers everything, calls the pure builder):

```kotlin
    /** Build the whole-bike "share everything for AI" report text. */
    suspend fun buildFullExportText(zone: java.time.ZoneId, now: Long): String =
        withContext(Dispatchers.IO) {
            val rides = store.getAllRides()
            val dataRides = rides.filter { !it.isMerged }
            val samplesByRide = dataRides.associate { it.id to store.getSamples(it.id) }
            val locationsByRide = store.getAllLocationsPerRide(dataRides)
            val fills = fuelStore.all()
            val services = serviceLogStore.all()
            val telem = settings.lastTelemetry.first()
            val currentOdo = telem?.odometerKm ?: store.lastKnownOdometer()
            AllBikeExporter.build(
                rides = rides,
                samplesByRide = samplesByRide,
                locationsByRide = locationsByRide,
                fills = fills,
                services = services,
                currentOdoKm = currentOdo,
                currentFuelBars = telem?.fuelBars,
                tankCapacityL = settings.fuelCapacityL.first(),
                serviceIntervalKm = settings.serviceIntervalKm.first(),
                serviceSchedule = settings.serviceSchedule.first(),
                bikeMac = settings.bikeMac.first(),
                bikeInfo = AppGraph.bikeInfo.value,
                riderName = settings.riderName.first(),
                zone = zone,
                now = now,
            )
        }
```

Add imports to `TripsViewModel.kt`:

```kotlin
import dev.mrwick.redline.data.ServiceLogStore
import dev.mrwick.redline.data.Settings
import dev.mrwick.redline.export.AllBikeExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
```

- [ ] **Step 2: Compile the ViewModel change**

Run: `cd android && ./gradlew :app:compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`. (Fix any settings-accessor name per the note above.)

- [ ] **Step 3: Add the share lambda + header icon in `TripsScreen.kt`**

`TripsScreen` already has `val scope = rememberCoroutineScope()` and
`val context = LocalContext.current`. After those, add the lambda:

```kotlin
    val shareAllForAi: () -> Unit = {
        scope.launch {
            val text = vm.buildFullExportText(
                zone = java.time.ZoneId.systemDefault(),
                now = System.currentTimeMillis(),
            )
            val uri = withContext(Dispatchers.IO) {
                val cache = java.io.File(context.cacheDir, "redline-full-export.txt")
                cache.writeText(text)
                androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", cache,
                )
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                android.content.Intent.createChooser(intent, "Share all bike data for AI"),
            )
        }
    }
```

Add the import for `Dispatchers` + `withContext` (scope/launch already present):

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```

- [ ] **Step 4: Pass the action into the header and render an icon**

Update the `TripsScreenHeader` call site to pass the new action:

```kotlin
                        TripsScreenHeader(
                            rideCount = monthSummary.rideCount,
                            totalKm = monthSummary.totalKm,
                            onOpenRoutes = onOpenRoutes,
                            onShareAllForAi = shareAllForAi,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
                        )
```

Update `TripsScreenHeader`'s signature and add the icon next to the routes
`IconButton` (wrap both in a `Row` so they sit side by side):

```kotlin
private fun TripsScreenHeader(
    rideCount: Int,
    totalKm: Int,
    onOpenRoutes: () -> Unit,
    onShareAllForAi: () -> Unit,
    modifier: Modifier = Modifier,
) {
```

Replace the single routes `IconButton` block with:

```kotlin
        Row {
            IconButton(onClick = onShareAllForAi, modifier = Modifier.padding(top = 4.dp)) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Outlined.Share,
                    contentDescription = "Share all bike data for AI",
                    tint = GixxerTokens.textMuted,
                )
            }
            IconButton(onClick = onOpenRoutes, modifier = Modifier.padding(top = 4.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Route,
                    contentDescription = "Routes",
                    tint = GixxerTokens.textMuted,
                )
            }
        }
```

Add the icon import near the other `material.icons.outlined` imports:

```kotlin
import androidx.compose.material.icons.outlined.Share
```

- [ ] **Step 5: Compile + run the full unit suite**

Run: `cd android && ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`; the only failing test is the pre-existing
`IdleClockGeneratorTest` (unrelated — confirmed failing on clean HEAD).

- [ ] **Step 6: Commit**

```bash
cd /home/mrwick/coding/projects/suzuki-connect-re
git add android/app/src/main/kotlin/dev/mrwick/redline/ui/trips/TripsViewModel.kt \
        android/app/src/main/kotlin/dev/mrwick/redline/ui/trips/TripsScreen.kt
git commit -m "feat(trips): 'Share everything for AI' header action — whole-bike export"
```

---

## Task 5: On-device end-to-end verification

**Files:** none (manual verification on the connected phone, package `dev.mrwick.redline.debug`).

- [ ] **Step 1: Install**

Run: `cd android && ./gradlew :app:installDebug --console=plain`
Expected: `Installed on 1 device.`

- [ ] **Step 2: Grant location via the new Settings row**

On the phone: Settings → Bike → Location → tap the row → grant "While using".
Verify: `adb shell dumpsys package dev.mrwick.redline.debug | grep ACCESS_FINE_LOCATION`
Expected: `granted=true`.

- [ ] **Step 3: Record a short ride with GPS**

Connect to the bike (or demo mode), ride/move ~1 km, end the ride. Then:
`adb exec-out run-as dev.mrwick.redline.debug cat databases/gixxer.db > /tmp/v.db`
`sqlite3 /tmp/v.db "SELECT COUNT(*) FROM ride_locations;"`
Expected: count increased; spot-check spacing is ~100 m between consecutive
points of the new ride.

- [ ] **Step 4: Run the export**

On the phone: Trips → tap the Share icon (top-right) → choose a target (e.g.
save to Files / send to yourself). Open the resulting
`redline-full-export.txt` and confirm: all section headers present, DATA
QUALITY notes correct (fill count, GPS coverage), per-ride table count =
top-level rides, FULL TELEMETRY contains child rides but no merged-parent id,
GPS TRACKS section has the new ride's points.

- [ ] **Step 5: Final commit (docs only, if notes were updated)**

```bash
cd /home/mrwick/coding/projects/suzuki-connect-re
# Update NOTES.md / DISCOVERIES.md if anything surprising surfaced, then:
git add -A && git commit -m "docs: verify AI export + GPS logging end-to-end" || true
```

---

## Self-Review

**Spec coverage:** Component A export → Tasks 3+4. Component B GPS sampling →
Task 1; permission surface → Task 2. Full-archive format (brief + raw CSV) →
Task 3 report layout. Include-everything privacy → MAC/BikeInfo/rider in
profile section. Trips-header entry point → Task 4. Data-quality caveats →
Task 3 DATA QUALITY section. Merge double-count handling → `topLevel`/`dataRides`
filters (Tasks 3+4). Testing → Task 3 unit tests + Task 5 on-device. All spec
sections covered.

**Placeholder scan:** No TBD/TODO. `AppGraph.settings(context)` and
`serviceLogDao()` are confirmed to exist (notes updated). The single remaining
compile-time check (`ServiceItem.label`) has a clear fix-to-compiler-name
instruction, not a vague hand-wave.

**Type consistency:** `buildFullExportText(zone, now)` produced in Task 4 and
called in Task 4 Step 3. `AllBikeExporter.build(...)` signature identical in
Task 3 (definition + test) and Task 4 (call). `topLevel`/`dataRides` filters
defined once and used consistently. Analytics calls match the verified
reference block.
</content>
</invoke>
