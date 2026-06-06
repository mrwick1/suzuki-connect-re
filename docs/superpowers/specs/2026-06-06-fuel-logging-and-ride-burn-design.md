# Design — Fuel logging revamp, per-ride fuel burnt, and phantom-ride fix

Date: 2026-06-06
Status: Approved (design); implementation pending
Scope owner: Arjun (REDLINE / gixxerbridge Android app)

## Context

After two real rides on 2026-06-06, three improvements were requested by the
rider. This spec covers the three that need **no on-bike verification** and can
be built directly. Two other items raised in the same session — the idle-cluster
warning glyph + duplicate clock, and the white "i" notification LED — are
**parked pending an on-bike test session** (a measurement harness for them is
already installed; see "Parked work" at the end).

### Source facts (verified this session)

- The bike streams its own real-time fuel economy; we already persist it per
  ride sample as `ride_samples.fuelEconKml` (set from
  `frame.fuelEconKmlV2 ?: frame.fuelEconKml` in `RideLogger.appendSample`).
  Today's rides: ride A avg ≈ 36.4 km/L, ride B avg ≈ 51.7 km/L.
- `TelemetryRepository.latest : StateFlow<TelemetryFrame?>` is a global singleton
  exposing the most recent frame (incl. `odometerKm`). The dashboard already
  reads it; the fuel screen can too.
- A phantom 0-km "ride" (id 1) leaked into the log: 1 sample, speed 0, never
  moved, `startOdo == endOdo`. The existing discard rule
  (`distance < 1 && silenceGap < 30s`) missed it because the silence proxy
  exceeded 30 s. Real rides reached 51 / 68 km/h; the phantom's max speed was 0.
- `FuelFillEntity(tMillis, odometerKm, litres, rupees?, note?)` and
  `MileageAnalytics.perTankKmPerL` / `averageKmPerL` already exist. Per-tank
  km/L = `(odo[N] - odo[N-1]) / litres[N]` — it **requires a litres value**.

## Goals

1. **Fuel-add revamp** — remove manual odometer typing; pre-fill it from the
   odometer we have at tap time. Fields: **litres**, **total price**, **odometer
   (auto, editable)**; note optional.
2. **Per-ride fuel burnt** — show an estimated litres-burnt per ride, sourced
   from the bike's logged econ, auto-upgrading to fill-calibrated km/L once fuel
   fills exist.
3. **Phantom-ride fix** — never persist a "ride" that never moved.

Non-goals: changing the cluster/LED behaviour (parked); changing the per-tank
km/L analytics formula; predicting partial fills (we assume full-to-full fills,
matching the existing analytics model and the rider's described behaviour).

---

## 1. Fuel-add revamp

### Behaviour

Replace the current "Add fill" flow (manual odometer entry) with an
auto-odometer flow:

- The FAB / button label becomes **"Fill up"**.
- On tap, open the fill sheet with the **odometer field pre-filled** using the
  best odometer available *at that instant*:
  1. `TelemetryRepository.latest.value?.odometerKm` if non-null (connected / just
     rode up), else
  2. the most recent ride's `endOdoKm` from `RideStore` (last-known), else
  3. blank.
  The field is **always editable** (rider can correct it).
- Fields shown: **Litres** (required, decimal), **Total price ₹** (required,
  decimal), **Odometer km** (pre-filled, editable, required), **Note**
  (optional).
- Save is enabled when odometer ≥ 0, litres > 0, and total price > 0.
- Price-per-litre is *derived* (`totalPrice / litres`) for display — not entered.

### Data model

No schema change. `FuelFillEntity.rupees` stores **total price**;
`FuelFillEntity.litres` stores litres. The existing optional `rupees` becomes
effectively required at the UI layer (DB stays nullable for backward compat with
the one… zero existing fills — `fuel_fills` is currently empty, so no migration
concern).

### Components touched

- `ui/mileage/MileageScreen.kt` — rename action to "Fill up"; rework
  `AddFillDialog` to pre-fill + reorder fields (litres, total price, odometer,
  note); relabel "Rupees (optional)" → "Total price (₹)".
- `ui/mileage/MileageViewModel.kt` — add a way to read the current best odometer:
  expose `currentOdometer(): Int?` that checks `TelemetryRepository.latest.value`
  then falls back to the latest ride's `endOdoKm` (needs a `RideStore` handle or
  a one-shot query). `addFill` signature is unchanged.

### Edge cases

- Not connected and no prior ride → odometer blank, rider types it (old
  behaviour, acceptable fallback).
- Stale live odometer (key was off a while) → still the best guess; editable.

---

## 2. Per-ride fuel burnt (estimated)

### Computation

Pure function in `analytics/RideAnalytics.kt` (the existing ride-analytics home):

```
fuelBurntL(distanceKm, kmPerL) = if (kmPerL > 0) distanceKm / kmPerL else null
```

km/L source, in priority order:

1. **Fill-calibrated** — `MileageAnalytics.averageKmPerL(fills)` when ≥ 1 valid
   tank exists. This is the rider's real measured mileage.
2. **Bike econ (fallback)** — the ride's average of `ride_samples.fuelEconKml`.
   The `rides` table has no econ aggregate column, so this is read via a new
   `RideDao` query (`SELECT AVG(fuelEconKml) ... WHERE rideId = :id AND
   fuelEconKml > 0`) — **no schema change**. Used until fills exist.

The chosen source is surfaced so the UI can label it (e.g. "est. from bike" vs
"from your fills").

### Display

- **Trip detail screen** (`ui/trips/TripDetailScreen.kt`): a stat alongside
  distance/speed — e.g. `Fuel used  ~0.14 L` with a subtle "(est.)" qualifier.
- **Post-ride summary card** (`ui/trips/PostRideSummary.kt`): same value at
  ride end.
- **Not** added to the ride-list rows (avoids clutter).

### Accuracy framing (per project no-assumptions rule)

The bike's econ is itself a computed value, not a metered flow reading. The
per-ride number is therefore an **estimate**, always rendered with `~` / "(est.)"
and never as exact truth. It self-corrects toward measured reality as fuel fills
accumulate (source 1 overtakes source 2).

---

## 3. Phantom-ride fix

In `RideLogger`, track whether the ride ever actually moved:

- Add `private var everMoved = false`; set it `true` in `onSample` whenever
  `frame.speedKmh > 0`.
- In `endRideInternal`, discard the ride when `!everMoved` (in addition to the
  existing noise rule). Reset `everMoved = false` alongside the other per-ride
  state on end.

This unambiguously drops key-on-but-parked captures (max speed 0) without risking
genuine sub-1-km rides (which still register a non-zero speed).

---

## Testing

- `MileageAnalyticsTest` / new `RideAnalyticsTest` — `fuelBurntL` math: normal,
  zero km/L guard, source-priority selection (fills present vs absent).
- `RideStoreTest` / `RideLogger` test — a never-moved ride is discarded; a moving
  ride is kept.
- Fuel-add: odometer pre-fill picks live value when present, falls back to
  last-known ride end-odo otherwise; save-enabled gating (litres & price > 0).

## Parked work (separate, needs on-bike session)

- **Idle cluster** — stop sending our duplicate clock in the a531 `eta`/`n0`
  slot; keep weather + temp + now-playing; replace the maneuver byte (currently
  46 = warning glyph) with a **blank** byte found via the re-enabled
  `ManeuverSweepScreen` (candidates 26–35). Bonus already done: the
  "Show clock + weather when nav idle" toggle was dead (producer ignored it) —
  now gated on `settings.idleClockEnabled`.
- **White "i" LED** — characterize a533 byte 14 (`i0`/SMS) and 15 (`j0`/Call) via
  the installed debug toggles (`AppGraph.debugSmsPending/CallPending`); confirm
  N/Y on/off direction (doc claims `N` = active — unverified), then wire to real
  notification state.
- **Incoming-call notification** — deferred; Samsung dialer keyword match in
  `NotificationDispatcher.handlePhone` likely fails; needs a logcat repro.
