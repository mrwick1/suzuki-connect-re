# Fuel-tank estimate ("litres left" + range) — design

- **Date:** 2026-06-06
- **Status:** Approved in brainstorm; pending spec review
- **Area:** `analytics/`, `data/` (Settings/DataStore), `ui/home` fuel tile + RANGE hero

## Problem / motivation

Two bugs and one feature, all in the same area:

1. **Bug — "fuel last seen" shows 0.** The Home fuel tile (disconnected state, `FuelTile` in
   `HomeScreen.kt`) reads `TelemetryRepository.latest`, which is in-memory only and `null`
   after any process restart. So with the bike off it shows `0 km/L` and `0 of 6 bars` — it
   doesn't actually remember anything.
2. **Bug — mislabeled metric.** The tile is titled "FUEL" but its hero number is fuel
   *economy* (km/L), not fuel *level*. The actual fuel reading (bars) is demoted to a subtitle.
3. **Feature — estimate litres in the tank** from logged fuel fills + distance ridden, and
   show **fuel left** and **tank range**, far finer than the bike's coarse 6-bar gauge.

## Decisions (from brainstorm)

- **Always fills to full** → every logged fill re-anchors the tank to capacity. No cross-tank
  drift; km/L accuracy only matters *within* a tank.
- **Capacity = 12 L** (stored in `Settings`, user-editable). ⚠️ To confirm against the owner's
  manual — chosen as a working value, not verified hardware fact (project no-assumptions rule).
- **km/L = measured fill-to-fill trailing average** over the last ~5 tanks
  (`MileageAnalytics.averageKmPerL`). Fallbacks, in order: bike-live km/L
  (`TelemetryFrame.fuelEconKmlV2`) → fixed default constant — used only until 2+ fills exist.
- **The computed estimate is the source of truth.** The bike's 6-bar gauge is *not displayed*
  and is used only as an internal bootstrap before the first fill (see Cold-start).
- **One km/L drives both** "fuel left" consumption and "range," so they can never disagree.
- **Tile shows only:** `≈ <litres> L · <percent>%` headline and `Range ≈ <km> km` sub.
  No bar count, no "last seen" timestamp, same content live or offline.

## Architecture

### `FuelTankEstimator` (pure, `analytics/`)

No Android dependencies → JVM-unit-testable, matching the existing `analytics/` style.

**Inputs:**
- `fills: List<FuelFillEntity>` (from `FuelStore`)
- `currentOdometerKm: Int?` — live `TelemetryRepository.latest.odometerKm`, else the persisted
  last-known odometer (see Persistence)
- `avgKmPerL: Double?` — `MileageAnalytics.averageKmPerL(fills)`
- `bikeLiveKmPerL: Double?` — `TelemetryFrame.fuelEconKmlV2` when live
- `bikeFuelBars: Int?` — last-known bars (bootstrap only)
- `capacityL: Double` — from Settings (default 12)
- `fallbackKmPerL: Double` — named constant (see Open items)

**Output** (`data class FuelEstimate`): `litresLeft: Double`, `percent: Double`,
`rangeKm: Double`, `kmPerLUsed: Double`, `isRough: Boolean`.

**Logic:**

```
km_per_L = avgKmPerL ?: bikeLiveKmPerL ?: fallbackKmPerL

if fills not empty AND currentOdometerKm != null:
    anchor      = most-recent fill
    km_since    = max(0, currentOdometerKm − anchor.odometerKm)   // clamp negative
    litres_used = km_since / km_per_L
    litres_left = (capacityL − litres_used).coerceIn(0, capacityL)
    isRough     = false
else:                                              // Cold-start (no fills yet)
    litres_left = bikeFuelBars?.let { (it / 6.0) * capacityL }    // rough bars→litres
    isRough     = true                             // (litres_left null → estimate unavailable)

percent  = litres_left / capacityL
range_km = litres_left * km_per_L
```

- Clamping to `[0, capacityL]` **structurally eliminates the "below zero / 0-of-6" bug.**
- `isRough` lets the UI optionally soften the wording before the first fill; not a separate
  number, just a flag.

### Persistence (also fixes bugs #1/#2)

The estimator needs the **current odometer even when disconnected**. Persist a small
**last-telemetry snapshot** to DataStore (`Settings`), updated whenever a telemetry frame
arrives: `odometerKm`, `fuelBars`, `kmPerL` (`fuelEconKmlV2`), `tMillis`. The Home VM reads
live telemetry when present and falls back to this snapshot otherwise. Capacity (12 L) also
lives in `Settings`.

> Net effect: the tile and estimator work fully offline, and the old in-memory-only gap that
> made "last seen" read 0 is closed.

### UI

- **Home fuel tile** (`FuelTile`): hero = `≈ <litres> L · <percent>%`; sub = `Range ≈ <km> km`.
  No bars, no timestamp, same label ("FUEL") live or offline. Economy (km/L) is dropped from
  this tile (it remains available on Stats/Mileage). When the estimate is unavailable (no
  fills *and* no bars/odometer), show `—` rather than a fake 0.
- **RANGE hero card** (`RangeHero`, connected state): re-point its `rangeKm` at
  `FuelTankEstimator` so the hero and the tile always show the same range, replacing the
  cruder `bars × km/bar` heuristic in `RangeEstimator` for this path. *(Flagged for veto on
  review — it touches the hero card.)*

## Edge cases

- Fill logged with `odometerKm` greater than current odometer (bad entry / odo not yet seen):
  `km_since` clamps to 0 → reads full tank. Acceptable.
- `km_per_L <= 0` or NaN: guard → treat as unavailable, show `—`.
- Multiple fills same odometer: most-recent (by `tMillis`) wins as anchor.

## Testing

`FuelTankEstimator` pure unit tests (JVM, `app/src/test`): just-filled = capacity; mid-tank
arithmetic; empty clamps to 0; no-fills cold-start (bars→litres) and `isRough=true`;
no-fills-no-bars → unavailable; negative-`km_since` guard; fallback-economy ordering
(avg → bike-live → default); `range = litres × km/L` consistency.

## Out of scope (YAGNI for v1)

- Partial fills / "filled to full?" flag (user always fills full).
- Reserve-capacity modelling / low-fuel warnings.
- Forgotten-fill auto-detection from the bike's gauge jumping up.
- Continuous bars↔ledger blending.

## Open items / assumptions

- **Tank capacity 12 L is unverified** — confirm against the manual; it's user-editable so a
  wrong default is self-correcting.
- **`fallbackKmPerL` default value** — needs a sane constant for the pre-first-fill window when
  the bike isn't live. Marked "tune / unverified" in code; only affects the bootstrap path.
