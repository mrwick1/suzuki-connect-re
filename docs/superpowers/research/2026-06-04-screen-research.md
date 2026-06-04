# GixxerBridge — Per-Screen Research (REDLINE PRESS plan 3+)

> Real research per screen: what data we actually have, what a user needs *there*,
> and which REDLINE PRESS components render it. Grounded in the codebase
> (HomeViewModel, AppGraph, TelemetryRepository, analytics/*, data/*), not guesses.

## ★ CORE PRINCIPLE — this is a STATIONARY companion, not a riding HUD

**The owner uses this phone app while PARKED or STOPPED** — bike off in the garage,
or pulled over at the roadside to check something, then rides on. **It is never read
at speed.** The only while-riding surface is the **bike's own instrument cluster**,
which the bridge writes to (nav arrow, distance, ETA) — that's what the rider glances
at moving.

Design consequences (override the earlier "glanceability-first / ride-time Pure lane"
framing for the PHONE UI):
- The phone app can be **rich, dense, interactive, and animated everywhere** — it's a
  showpiece read at rest. No need to dumb screens down for at-speed legibility.
- Optimize Home/Stats/Trips/etc. for **"sat down with it" depth**, not 1-second glances.
- The **two-motion-lane "Pure" mode still applies, but only to the on-bike Cluster
  mirror + the Active-ride screen** (the surfaces that represent the riding moment) —
  NOT to the rest of the app.
- "Where's my bike" + "can I make my trip on this fuel" are the highest-value at-rest
  jobs (you're often away from or beside a parked bike).

(Recorded per the owner's 2026-06-04 directive. Also reflected in the design spec §3.)

---

## Data inventory (what the app actually has)

| Source | Fields | Availability |
|---|---|---|
| `AppGraph.connectionState` | Idle / Connecting / Discovering / Ready / Disconnected / Failed | always |
| `TelemetryRepository.latest` (a537) | `speedKmh`, `odometerKm`, `tripAKm`, `tripBKm`, `fuelBars` (1–6), `fuelEconKmlV2` (~trip-avg km/L) | live only while BLE-connected + bike on |
| `TelemetryRepository.history` | last 60 frames (~5 min) | live only |
| `RangeEstimator` | `estimateRemainingKm(fuelBars, kmPerBar)`; kmPerBar from ride history (median); `FALLBACK_KM_PER_BAR=50` | derived |
| `RideStore` / `RideAnalytics` | today km (rolling 24h), totals, per-ride history | persisted |
| `RideStreak` | consecutive-day streak | persisted |
| `ServiceSchedule` (`NextServiceSummary`) | worst-overdue item label + "1200 km" / "Overdue 320 km" + overdue flag | persisted + odo |
| `LastParkedTracker.lastParked` | lat/lng/time at last disconnect + `mapsUrl()` | persisted on park |
| `QuickDestinations` | saved destinations | persisted |
| `Settings.riderName` | display name | persisted |

**No-assumptions caveats:** `fuelEconKmlV2` is trip-average (not instantaneous); fuel is
coarse 1–6 bars; range is an *estimate* (label it). Lean/RPM are NOT available. Telemetry
is **stale/absent when disconnected** — Home must show "last known" + timestamps, never
imply live data when parked.

---

## HOME — "Living Cover" (design + build target)

**The job (at rest):** in ~2 seconds tell me — is the bike reachable, where is it, how
much fuel/range, and a glanceable pulse of my riding (today/streak/odo) — then offer the
1–2 things I'd actually start (nav, ride log, pair). Rich, not minimal.

**Two states (driven by `connectionState`):**

- **CONNECTED** (bike on, you've stopped beside it): live status.
  - **Hero:** RANGE — `HeroNumeral` km (lush-green) + a `Sweep` fuel gauge (bars→fraction,
    cool→hot) as the visual; "≈ at your recent km/bar" subcaption (estimate, honest).
  - Half tile: **Fuel** bars + km/L (`OdometerNumber`).
  - Half tile: **Odometer** (`OdometerNumber`) + Trip A.
  - Health tile: `HealthRing` (from service schedule / bike health) + "next service in …".
  - Today strip: today km + streak.
  - Quick actions (custom glyphs): Nav, Log/Start ride, Pair/Settings.

- **PARKED / DISCONNECTED** (away from bike): "where + recap".
  - **Hero:** **Last parked** — "parked 4h 12m ago" + distance-away + a `TraceChart`
    route-as-art flourish; tap → open in maps (`mapsUrl()`). If no park record, hero
    falls back to **today distance** `HeroNumeral`.
  - Last-known fuel/range tile (clearly "last seen", timestamped).
  - Service-due tile (`HealthRing`).
  - Today + streak strip.
  - Quick actions.

**Components:** `BentoTile` (wall + stagger), `HeroNumeral`, `Sweep`, `OdometerNumber`,
`HealthRing`, `TraceChart` (route-art), `GixxerIcons` (quick-action glyphs), `ConnectionDot`
(breathing status). One enforced Hero. Connection state = a breathing dot + word, not a chip.

**ViewModel additions needed:** expose `fuelBars`, `odometerKm`, `tripAKm`, `fuelEconKml`
(from `TelemetryRepository.latest`), a `rangeKm` estimate (`RangeEstimator` + ride history),
and `lastParked` (+ "Nm away" if we have a current fix). Keep existing 5 flows.

**Build status:** IN PROGRESS (this plan).

---

## Other screens — to research + build (after Home)

Each gets the same treatment (data → at-rest job → components) before building:

- **Dashboard** — live telemetry cockpit (Sweep speed + fuel + odo/trip + history trace).
- **Stats** — Wrapped-style ride stats (bar/line/histogram/calendar-heatmap → REDLINE charts).
- **Trips** list + **Trip detail** + **Post-ride "Ride Wrapped"** (TraceChart + ShareScene).
- **Mileage** — km/L over time (TraceChart + OdometerNumber).
- **Maintenance / Service history** — HealthRing + service timeline.
- **Cluster Preview / Active-ride "The Sweep"** — the ONE Pure-lane surface (on-bike mirror).
- **Diagnostics** — BLE frame log (JetBrains Mono + BLE-chevron glyphs).
- **Settings** (pairing, cluster, notifications, developer) — bento + Sweep for any progress.
- **Onboarding, SOS/safety, Frame composer/inspector (dev).**
