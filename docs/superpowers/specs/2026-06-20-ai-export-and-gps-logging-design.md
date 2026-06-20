# Design: "Share everything for AI" export + GPS track logging

Date: 2026-06-20
Status: Approved (design), pending spec review
Author: Arjun + Claude (REDLINE)

## Summary

Two related features for the REDLINE Android app (`dev.mrwick.redline`):

- **Component A — "Share everything for AI" export.** A second AI-share action,
  alongside the existing per-trip "Share for AI". Exports the *whole bike's*
  data (all rides, telemetry, fuel, cost, service, plus every derived analytics
  output) as a single self-contained `.txt` "full archive", delivered through
  the same FileProvider / `ACTION_SEND` pipeline. Intended to be uploaded as a
  file to an AI assistant for analysis of bike health, performance, mileage, and
  running cost.

- **Component B — GPS track logging.** Make ride GPS tracks actually record
  (today they don't — see below), and switch the sampler from time-based (5 s)
  to **distance-based 100 m**.

The two are independent features but share one theme — getting good data to the
AI — and Component B enriches Component A's GPS section over time. They ship as
one spec, one implementation plan.

## Background / verified ground truth

Facts established by inspection on 2026-06-20 (not assumptions):

- The existing per-trip exporter is `export/TripShareText.kt` — a **pure**
  function (no Android/DB/Context, deterministic), with disciplined data-quality
  labelling (e.g. it explicitly warns the BLE econ field over-reads ~30% and
  only trusts fill-measured km/L). Component A mirrors this pattern.
- Per-trip share wiring lives in `ui/trips/TripDetailScreen.kt` (`shareForAi`
  lambda): builds text via `TripShareText.build()`, appends CSV via
  `CsvExporter.rideSamplesToCsv()`, writes to `cacheDir`, shares via
  `FileProvider.getUriForFile(context, "${packageName}.fileprovider", file)` +
  `Intent(ACTION_SEND)` chooser.
- There are **17 pure analytics modules** in `analytics/` (BikeHealth,
  MileageAnalytics, CostAnalytics, RunningCostAnalytics, RangeEstimator,
  FuelTankEstimator, RefuelPredictor, ServiceSchedule, ServiceEta, RideAnalytics,
  RideStreak, RouteClustering, RouteLeaderboard, WrappedAnalytics,
  JourneyDetector, SpeedTrack, SpeedTrackColors). All deterministic JVM —
  their outputs drop directly into the report.
- `data/RideStore.kt` exposes `getAllRides()`, per-ride `getSamples(rideId)` /
  `getLocations(rideId)`, and `getAllLocationsPerRide(rides)`. There is **no**
  bulk "all samples across all rides" getter today. Fuel fills and service logs
  live in their own stores (`data/FuelFill.kt`, `data/ServiceLog.kt`).
- `location/RideLocationTracker.kt` wraps `FusedLocationProviderClient`,
  **time-based** at 5 s (`INTERVAL_MS`), `PRIORITY_BALANCED_POWER_ACCURACY`.
  `start()` silently no-ops if `ACCESS_FINE_LOCATION` is not granted.
  `telemetry/RideLogger.kt` correctly gates `start()`/`stop()` on ride lifecycle.
- **GPS is empty (6 points across 99 rides)** because location permission is not
  granted: `dumpsys package` shows `ACCESS_FINE_LOCATION: granted=false`. The
  rename to `dev.mrwick.redline` made this a new package with zero permissions;
  the 6 points came from the old package during a brief grant.
- Location permission is requested **only** in `ui/onboarding/OnboardingScreen.kt`.
  `ui/settings/PermissionRow.kt` is wired for SEND_SMS and DND access only —
  **not location**. Because `onboarding_complete` was restored as `true` from
  the pre-rename backup, onboarding will not re-run, so there is currently **no
  in-app path to grant location**. This gap must be closed for Component B to
  produce any data.

## Decisions (locked with Arjun)

- **Format:** Full archive — curated brief + complete raw CSV (all telemetry
  samples, GPS, fills, services). Meant to be uploaded as a file, not pasted.
- **Privacy:** Include everything, including bike MAC, bike serial-name,
  emergency-contact phone, and home/work addresses. (Arjun's data → Arjun's own
  AI chat. Trade-off accepted: the file carries identifiers that cannot be
  un-shared once uploaded.)
- **GPS interval:** 100 m, distance-based, `BALANCED_POWER`.
- **Export entry point:** Trips screen header icon (top-right), parallel to the
  per-trip share icon on Trip Detail.
- **Scope:** One spec, both components, one implementation plan.

## Component A — `AllBikeExporter`

### Module: `export/AllBikeExporter.kt` (new)

A pure object mirroring `TripShareText`. No Android imports, no DB, no Context,
no clock calls — all time strings derived from entity timestamps + a passed-in
zone. Every estimate labelled `(est.)`; data-quality caveats stated inline so
the AI is not misled (per the project's no-assumptions rule).

Signature (shape; finalize in the plan):

```kotlin
object AllBikeExporter {
    fun build(
        rides: List<RideEntity>,            // all rides (parents + children + normal)
        samplesByRide: Map<Long, List<RideSampleEntity>>,
        locationsByRide: Map<Long, List<RideLocationEntity>>,
        fills: List<FuelFillEntity>,
        services: List<ServiceLogEntity>,
        profile: BikeProfileSnapshot,       // odo, tank, mac, serial-name, paired-since
        settings: ExportSettingsSnapshot,   // service intervals, fuel capacity, etc.
        zone: String = "UTC",
    ): String
}
```

It computes derived sections by calling the existing pure analytics objects
internally (BikeHealth, MileageAnalytics, Cost/RunningCostAnalytics,
ServiceSchedule/ServiceEta, RideAnalytics, RideStreak, WrappedAnalytics,
RouteClustering→RouteLeaderboard, FuelTankEstimator). Keeping `build()` a pure
function of already-fetched data preserves unit-testability.

### Report layout (`.txt`)

```
PREAMBLE  — "Analyse my motorcycle's health, performance, mileage and running
             cost from the complete data below. Data-quality notes are inline."

=== DATA QUALITY NOTES ===     (honesty first)
  - BLE cluster econ over-reads ~30%; only fill-measured km/L is trustworthy.
  - N fuel fills ⇒ N-1 measured mileage intervals.
  - GPS coverage: X of 99 rides have tracks (Y%).
  - Service log: <empty | N entries>.
  - Baseline window: <first ride date> → <last ride date>, <total> km.

=== BIKE PROFILE ===           model, current odo, tank capacity, fuel-bar count,
                               paired-since, bike MAC, bike serial-name
=== HEALTH ===                 BikeHealth score / grade / sub-scores
=== FUEL & MILEAGE ===         per-tank km/L table (MileageAnalytics),
                               trailing avg, current tank estimate (FuelTankEstimator)
=== RUNNING COST ===           rolling & lifetime ₹/km (Cost/RunningCostAnalytics),
                               monthly spend, fuel-vs-service split, price trend
=== SERVICE ===                per-item km/days remaining + ETA + overdue
                               (ServiceSchedule + ServiceEta)
=== PERFORMANCE ===            totals, personal bests, speed distribution,
                               weekday / time-of-day, moving vs idle (RideAnalytics)
=== HABITS ===                 streaks (RideStreak), Wrapped recap
                               (WrappedAnalytics), route stats (RouteLeaderboard)
=== PER-RIDE SUMMARY (N) ===   one line per ride: date, name, km, max/avg speed,
                               fuel bars, duration
=== FUEL FILLS (CSV) ===
=== SERVICE LOGS (CSV) ===
=== GPS TRACKS (CSV) ===       lat,lng,alt,accuracy,t per point, grouped by ride
=== FULL TELEMETRY (CSV) ===   all samples (reuse CsvExporter row format)
FOOTER                          source + export timestamp
```

### Wiring

- **`data/RideStore.kt`**: add a bulk samples getter, e.g.
  `getAllSamplesPerRide(rides): Map<Long, List<RideSampleEntity>>` (mirrors the
  existing `getAllLocationsPerRide`). Reuse `getAllLocationsPerRide` for GPS.
- **`ui/trips/TripsViewModel.kt`**: add `suspend fun buildFullExport(zone): Uri`
  that gathers all rides + samples + locations (RideStore), all fills
  (FuelFill store), all services (ServiceLog store), profile + settings
  snapshots, calls `AllBikeExporter.build()`, writes
  `cacheDir/redline-full-export-<startedTs>.txt`, returns the FileProvider Uri.
  All DB/IO on `Dispatchers.IO`.
- **`ui/trips/TripsScreen.kt`**: add a header IconButton (top-right, next to the
  existing routes action) that calls the VM and fires the same
  `ACTION_SEND` + `createChooser` pattern as `TripDetailScreen.shareForAi`.

### Performance

The archive is large (~1 MB string, ~16 k CSV rows for the current corpus).
Build entirely on `Dispatchers.IO`; stream into a single `buildString` /
file write. Acceptable for an explicit, user-initiated export.

## Component B — GPS track logging

### `location/RideLocationTracker.kt`

- Add `.setMinUpdateDistanceMeters(100f)` to the `LocationRequest.Builder`.
- Keep `PRIORITY_BALANCED_POWER_ACCURACY`.
- Lower the desired time interval to ~2–3 s (floor) so the **distance gate**
  drives cadence; the OS emits a fix roughly every 100 m of travel and none
  while stopped.
- Replace the stale `ASSUMED ... 5 s cadence` doc comment with the new
  distance-based rationale.
- Extract request construction into a small pure helper
  (e.g. `buildLocationRequest(distanceM, ...): LocationRequest`) so the 100 m
  parameter is unit-testable without a device.

### Permission surface (the actual blocker)

Because onboarding won't re-run, add an in-app way to grant location:

- Add a **location permission row** to a Settings screen (Bike or Maintenance
  settings is the natural home — confirm in the plan), built on the existing
  `PermissionRow` (`n(...)`) helper used for SMS/DND. It shows granted/denied
  state and launches the runtime request.
- After grant, ensure tracking picks up without restart: `RideLogger` already
  re-calls `start()` per ride; document that the user must grant before/at the
  start of a ride. Optionally re-attempt `start()` when the permission row flips
  to granted during an active ride (nice-to-have, not required).

GPS remains opt-in: no permission, no tracking, exactly as today.

## Testing (TDD)

- **`AllBikeExporterTest`** (JVM unit, mirrors `TripShareTextTest`): fixture
  rides/samples/locations/fills/services →
  - asserts each section header present;
  - asserts DATA QUALITY NOTES emitted with correct fill-interval count and GPS
    coverage %;
  - asserts `(est.)` labels on modelled values;
  - asserts CSV row counts equal input counts;
  - edge cases: zero fills (mileage "can't measure"), empty service log, zero
    GPS, in-progress ride (null endOdo).
- **`RideLocationTracker`**: unit-test the extracted `buildLocationRequest`
  helper asserts `minUpdateDistanceMeters == 100`. (FusedLocation callbacks
  themselves are not unit-tested.)
- **On-device verification**: grant location, ride a short loop, confirm
  `ride_locations` rows accumulate at ~100 m spacing, then run the full export
  and eyeball the GPS section.

## File map

New:
- `android/app/src/main/kotlin/dev/mrwick/redline/export/AllBikeExporter.kt`
- `android/app/src/test/kotlin/dev/mrwick/redline/export/AllBikeExporterTest.kt`

Modify:
- `android/app/src/main/kotlin/dev/mrwick/redline/data/RideStore.kt`
  (bulk samples getter)
- `android/app/src/main/kotlin/dev/mrwick/redline/ui/trips/TripsViewModel.kt`
  (`buildFullExport`)
- `android/app/src/main/kotlin/dev/mrwick/redline/ui/trips/TripsScreen.kt`
  (header export icon)
- `android/app/src/main/kotlin/dev/mrwick/redline/location/RideLocationTracker.kt`
  (distance-based 100 m + testable request builder)
- A Settings screen (Bike or Maintenance) + `ui/settings/PermissionRow.kt`
  usage for a location permission row

## Out of scope / YAGNI

- No JSON export variant (text full-archive only, per decision).
- No PII redaction / opt-in toggles (include-everything, per decision).
- No re-rendering of GPS as a map image in the export (CSV coords only;
  `ShareCardRenderer` already covers per-trip visuals).
- No background-location permission (tracking only while app/ride is active).
- No class-symbol cleanup (`GixxerTokens`, etc.) — unrelated to this work.

## Risks / notes

- **Privacy**: the archive contains MAC, serial-name, SOS phone, home/work
  addresses. By decision. Worth a one-line in-app note at the share point
  ("this file contains your full data including identifiers") — to confirm in
  the plan.
- **Export size** scales with telemetry volume; fine now, revisit if the corpus
  grows into hundreds of thousands of samples.
- **Battery**: distance-based 100 m at BALANCED_POWER should be lighter than
  the old 5 s time-based sampler (no fixes while stopped). Verify on-device.
