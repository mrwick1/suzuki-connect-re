# Stats & Features Worth Adding — Research Report

_2026-06-06 · Gixxer SF 150 BLE companion app_

## Intro: what the app actually has to work with

This report ranks candidate stats/features by value-to-effort, grounded in what the bike and phone genuinely expose. Per the project's no-assumptions rule, feasibility claims below are tied to verified code (file:line) and the protocol's hard limits, not to what "a bike app usually does."

**Data the app can rely on (all local, no cloud, no SIM):**

- **Ride telemetry (`RideSampleEntity`):** per-sample `speedKmh` (the bike's real **a537 ECU speed**, not the stale a533 SharedPrefs value), `tMillis`, `odometerKm` (whole-km Int), `tripAKm` (0.1-km resolution), `fuelEconKmlV2` (cluster trip-average km/L), `fuelBars` (6 coarse bars). a537 frames arrive at a **fixed ~5 s cadence** — so sample count is a clean proxy for wall-clock time, but resolution is coarse (~0.2 Hz, whole-km/h speed).
- **Rides (`RideEntity`):** start/end odo, timestamps, `avgSpeedKmh` (moving-only), `maxSpeedKmh`, editable `name`.
- **Fuel (`FuelFillEntity`):** `rupees` (nullable), `litres`, `odometerKm`, `tMillis` — captured but **never used for analytics today**.
- **Service (`ServiceLogEntity` + `ServiceSchedule`):** `rupees`, `odometerKm`, free-form `type`, plus km/day thresholds and `healthFor()` returning `kmRemaining`/`daysRemaining`.
- **GPS track (`RideLocationEntity`):** lat/lng, `altitudeM` (nullable, phone GPS), `accuracyM`, `tMillis` — currently used **only for GPX export**.
- **Phone/weather side:** `PhoneState.batteryPercent`/`isCharging`; ambient temp + weather code from **Open-Meteo (phone GPS)** — note this is phone-sourced, **not bike telemetry**.
- **Cluster output:** a531 is a **rigid 30-byte nav frame** — only a turn-icon byte plus short numeric slots (`distNext` 4 chars, `eta` 6 chars, `distTotal` 4 chars) render. There is **no free-text channel**. Idle-frame ASCII repurposing works but is still flagged ASSUMED/unproven-on-bike in places.

**Hard constraints honoured throughout:** no cloud, no embedded SIM, phone GPS only, coarse 6-bar fuel gauge (no litres from the bike), `fuelEconKmlV2` is a rider-resettable trip-average that ticks during engine-off idle, all analytics are pure JVM over Room entities, and `GixxerDatabase` uses `fallbackToDestructiveMigration` (any schema bump wipes history — prefer recompute-on-read or DataStore over new Room columns).

---

## Top picks — best value-to-effort, ranked

These are the ~8 highest-leverage ideas (high value, low effort, fully feasible). All are value 4 / effort 2 or strong value 3 / effort 2 builds.

### 1. Range-remaining cluster readout with personal km/bar  _(value 4 / effort 2)_
- **What it shows:** estimated km remaining (e.g. `~140KM`) on the idle cluster frame, tuned to how *this* rider rides.
- **Data used:** `RangeEstimator.kmPerBar()`/`estimateRemainingKm()` (already implemented, comment confirms "Not currently wired into the UI") × live `TelemetryFrame.fuelBars`; median km/bar from `observeRides()`; `FALLBACK_KM_PER_BAR=50` only when N is small (flagged).
- **Why it matters:** the stock cluster shows only 6 bars + a binary low-fuel light. A personalized distance number is the single thing a coarse gauge can't give. Mostly glue code — the engine is built and tested.
- **Effort:** low. Wire `fuelBars` + history → format → inject into a NavFrame slot. Honest caveats: inherits 6-bar quantization (~50 km granularity, so `~140KM` is soft); a531 slot rendering is ASSUMED and needs one on-bike confirmation; idle slots already carry clock/temp/weather, so range must join the rotation or displace a slot.

### 2. Cost-per-km tracker (rolling ₹/km and ₹/100km)  _(value 4 / effort 2)_
- **What it shows:** true ₹/km and ₹/100km from fuel spend ÷ km, with trailing-5-tank rate next to lifetime so the rider sees cost climbing or falling.
- **Data used:** `FuelFillEntity.rupees` + `odometerKm`, the same consecutive-pair logic `MileageAnalytics.perTankKmPerL` already uses — dividing rupees by km instead of km by litres. `rupees` is captured today but has zero analytics.
- **Why it matters:** riders think in money, not litres. ₹/km is the number that answers "what does this thing actually cost to ride" and enables commute-vs-cab comparison.
- **Effort:** low. One small pure object + tests + one card mirroring `AverageCard`. Caveat: `rupees` is nullable — exclude price-less intervals and flag the figure as partial.

### 3. Running cost dashboard: cost-per-km + monthly spend  _(value 4 / effort 2)_
- **What it shows:** blended cost of ownership — `(fuel ₹ + service ₹) ÷ km`, a fuel-vs-maintenance split, and a monthly spend bar.
- **Data used:** `FuelFillEntity.rupees` + `ServiceLogEntity.rupees` joined to distance, reusing `RideAnalytics.monthlyKm` month-bucketing (`RideAnalytics.kt:195-217`). All columns already exist — no schema change.
- **Why it matters:** first time the rider sees the true running cost of ownership, with the split exposing whether deferred maintenance or a degrading engine is quietly raising ₹/km.
- **Effort:** low. Caveats: `rupees` nullable (disclose coverage or it undercounts); a more robust km denominator is fuel-fill odo deltas (captures km even when rides aren't logged). The headline cost-per-km is useful from the first few records; the degradation trend story is aspirational.

### 4. Gixxer Wrapped — annual / any-window year-in-review  _(value 4 / effort 3)_
- **What it shows:** a swipeable, shareable recap — total km, saddle hours, ride count, longest ride, top speed (with date/name), busiest month/weekday, best/worst tank, litres burnt, total spend, longest streak.
- **Data used:** `RideEntity`, `RideAnalytics.monthlyKm`/`weekdayKm`/`timeOfDayKm`, `RideStreak`, `PersonalBests`, `MileageAnalytics.perTankKmPerL`, fuel/service `rupees`. Since the DB is single-rider, generate for any window. Reuses `SceneCapture`/`rememberSceneCapture` for image export.
- **Why it matters:** the single most "gamified," screenshot-ready artifact an offline app can produce — zero new data capture.
- **Effort:** moderate. The reusable primitive is `SceneCapture` (not a drop-in card composer — `ShareCardRenderer` is ride-specific), so several new swipeable card composables + window plumbing are net-new. Litres burnt is an estimate; carry the same "est." honesty already in trip summaries.

### 5. Route repeat detector — your commute, ranked  _(value 4 / effort 3)_
- **What it shows:** clusters rides by route similarity (coarse lat/lng grid signature) and ranks most-ridden routes by best/median/worst time and distance — Strava-segment style, fully offline.
- **Data used:** `RideLocationEntity` tracks across all rides; `RideEntity` timestamps for duration; odo delta for distance. Pure on-device geometry, no map service.
- **Why it matters:** for a daily-commute bike this answers "what's my fastest office run, is my commute getting slower" — turns a flat ride list into a per-route leaderboard.
- **Effort:** moderate. Caveats: balanced-power 5 s GPS (~50 m spacing) means coarse signatures may merge/split routes — not Strava-crisp. **Downgrade the "fuel-per-route" claim to estimated** (bike gives no litres; per-route fuel is modelled km/L × distance). Slow burn until a commute accumulates repeats.

### 6. Speed-colored ride track (heat polyline)  _(value 3 / effort 2)_
- **What it shows:** recolors the existing `RideTrackCard` polyline by speed — dim for stop-and-go, bright for fast stretches — so traffic and open road show on one picture.
- **Data used:** `RideLocationEntity` lat/lng joined to `RideSampleEntity.speedKmh` by nearest `tMillis`. Both already loaded in the `TripDetailScreen` scope (`TripDetailScreen.kt:400-458`).
- **Why it matters:** instantly shows where the ride was free-flowing vs stuck, without mentally cross-referencing track against a separate speed graph.
- **Effort:** low. Replace one `drawPath` with N colored `drawLine` calls; nearest-time join over sorted samples; fall back to flat color when samples empty. No schema bump. Speed is genuine ECU ground truth.

### 7. Service ETA forecast — "~18 days / ~3 rides until oil change"  _(value 3 / effort 2)_
- **What it shows:** translates the static km-remaining gate into a calendar-date ETA using the rider's recent daily km pace, shown side-by-side with the calendar gate ("calendar wins").
- **Data used:** `ServiceSchedule.healthFor()` (`kmRemaining`/`daysRemaining`) + `RideAnalytics.totalsFor`/`monthlyKm` for pace. Trivial arithmetic: `kmRemaining / (km per day)`, then `min(that, daysRemaining)`.
- **Why it matters:** turns a static threshold into an actionable "book the service this week" nudge that respects how much the rider actually rides (commuter hits km gate, weekend rider hits calendar gate).
- **Effort:** low. Must guard against a zero-km 30-day window (divide-by-zero → fall back to calendar-only). Value is polish on existing output, not a new capability.

### 8. Predicted refuel date + "fill before service" alert  _(value 3 / effort 2)_
- **What it shows:** "Refuel in ~2 days; oil service due in ~3 weeks" on the home card, bundling errands when a refuel and an overdue km-gated service fall in the same window.
- **Data used:** `RangeEstimator` per-bar km × latest `fuelBars` × daily km pace; `ServiceSchedule.kmRemaining` for the co-prompt.
- **Why it matters:** translates the coarse 6-bar gauge into "refuel by Tuesday" and bundles refuel + service into one trip.
- **Effort:** low. Caveats: show as a coarse range, not a precise day (6-bar quantization + anecdotal 50 km/bar fallback); prefer the fill-measured km/L path over blending with km/bar (they aren't independent); guard "no recent rides → no prediction." The **fill+service co-prompt is the genuinely high-value, low-risk half** — it sits on the reliable odo-gated `kmRemaining`.

---

## The rest, grouped by category

### Riding performance & telemetry stats

| Title | Value | Effort | Feasible |
|---|---|---|---|
| Moving vs Idle Time Split (Traffic Drag Meter) | 3 | 2 | Yes |
| Speed-vs-Distance Pace Curve with 1-km splits | 3 | 3 | Yes (key off `tripAKm`, not whole-km `odometerKm`) |
| Elevation Gain & Climb Stats from GPS Altitude | 3 | 3 | Yes (GPS altitude noisy; smooth + threshold) |
| Expanded Personal Records Board + "New Record!" flags | 3 | 2 | Yes (core records solid; "smoothest"/"idle %" depend on unbuilt metrics) |
| Acceleration / Roll-On Snapshots (cadence-honest) | 3 | 2 | Yes (signal thin at 5 s / whole-km/h) |
| Ride Smoothness / Throttle-Discipline Score | 2 | 2 | Yes (soft gamification; speed trace already partly used) |

### Fuel & cost intelligence (a litres-left estimate is already being built — these go beyond it)

| Title | Value | Effort | Feasible |
|---|---|---|---|
| Cost-per-km tracker (rolling ₹/km) | 4 | 2 | Yes — see Top Picks #2 |
| Running cost dashboard (cost-per-km + monthly spend) | 4 | 2 | Yes — see Top Picks #3 |
| Monthly fuel-spend bars + projected month-end total | 3 | 2 | Yes (clone `monthlyKm` bucketing; exclude null prices) |
| Cluster-vs-pump mileage honesty check (drift signal) | 3 | 2 | Yes (data-entry-mistake catch is the real win; drift narrative overstated) |
| Per-ride cost stamp + cheapest/priciest leaderboard | 3 | 2 | Yes (essentially an efficiency leaderboard scaled by near-constant price) |
| Spend-to-next-service forecast + total cost/km | 3 | 2 | Yes (gated on rider logging both fuel + service prices) |

### Maintenance & predictive bike health

| Title | Value | Effort | Feasible |
|---|---|---|---|
| Running cost dashboard (fuel + service ₹/km) | 4 | 2 | Yes — see Top Picks #3 |
| Service ETA forecast ("~18 days until oil change") | 3 | 2 | Yes — see Top Picks #7 |
| Predicted refuel date + "fill before service" alert | 3 | 2 | Yes — see Top Picks #8 |
| Battery-health proxy from ride-cadence gaps | 3 | 2 | Yes (drop the fuelEcon-idle refinement; clean `startedAtMillis` gap signal) |
| High-load wear index for spark plug / air filter | 2 | 2 | Yes mechanically, but speed is a weak RPM/load proxy — ship as labelled estimate |
| Sensor-drift watchdog (bike vs fill km/L) | 2 | 3 | Buildable but premise unsound — "air filter/plug" attribution unsupported; just trend fill km/L |

### Trips & journeys

| Title | Value | Effort | Feasible |
|---|---|---|---|
| Route repeat detector — your commute, ranked | 4 | 3 | Yes — see Top Picks #5 |
| Speed-colored ride track (heat polyline) | 3 | 2 | Yes — see Top Picks #6 |
| Elevation profile + climb/descent per ride | 3 | 2 | Yes (use haversine distance axis, not whole-km odo; deadband filter required) |
| Bike-vs-fill mileage accuracy badge per ride | 3 | 2 | Yes (over-time drift trend more defensible than per-ride number) |
| Auto trip-naming from start/end geofences | 3 | 2 | Yes (anchors in DataStore, no schema bump; match first *settled* GPS fix) |
| Per-ride weather + temperature stamp + mileage correlation | 3 | 2 | Yes (temp is phone-sourced, not bike; needs Migration(4,5) to avoid wipe) |

### Safety & emergency (crash detection already exists)

| Title | Value | Effort | Feasible |
|---|---|---|---|
| Phone-battery low-power SOS guard, before the ride | 3 | 2 | Yes (gate on SOS armed; no schema write needed — transient warning) |
| Lean/rough-road impact log for post-ride review | 3 | 3 | Yes (value gated by IMU signal tuning; thresholds unverified) |
| Live ride-share breadcrumb link in SOS SMS | 2 | 2 | Yes (degrades to origin→current 2-point link; needs multipart SMS) |
| Cold-start traction warning on the cluster | 2 | 2 | Yes (cluster gets terse "COLD"; full sentence → phone notification; rare in Bengaluru) |
| Stuck-stopped / no-motion check-in during a ride | 2 | 3 | Yes but high false-positive risk (traffic/fuel stops trip a 10-min window) |

### Gamification, streaks & achievements

| Title | Value | Effort | Feasible |
|---|---|---|---|
| Gixxer Wrapped — annual / any-window recap | 4 | 3 | Yes — see Top Picks #4 |
| Odometer milestone badges (with the ride that crossed it) | 3 | 2 | Yes (recompute on read, don't persist; ServiceLog.type is free-form) |
| Elevation & terrain badges from GPS track | 3 | 2 | Yes (GPS altitude noisy — totals soft, badges fire inconsistently) |
| Efficiency streaks / "Eco Rider" challenge | 3 | 2 | Yes (fuelEconV2 noisy, gameable by idling) |
| Weekly & monthly distance challenges with progress rings | 3 | 2 | Yes (reuse `HealthRing`; small-N target noise) |
| Consistency calendar with streak-freeze + perfect weeks | 3 | 3 | Yes (DataStore for freeze tokens; daily streak is a poor fit for a bike) |

### Navigation & context (weather, cluster, route memory, greetings)

| Title | Value | Effort | Feasible |
|---|---|---|---|
| Range-remaining cluster readout with personal km/bar | 4 | 2 | Yes — see Top Picks #1 |
| Elevation-aware ride profile from GPS track | 3 | 2 | Yes (build with haversine distance, not odo; vertical-noise filter) |
| Live efficiency feedback on the cluster idle frame | 3 | 2 | Yes (byte25 near-static intra-ride; glyph-vs-median is the real signal) |
| Per-ride weather/temp stamp (cold-start detector) | 2 | 2 | Yes only as **ambient** weather — temp is phone/Open-Meteo, NOT bike; no engine-temp signal exists, "cold-start" is a misnomer |
| Weather/temp-vs-mileage scatter | 2 | 2 | Yes but depends on the weather-stamp migration; signal likely swamped by route/idle noise |
| Contextual cluster greeting (weather/time-aware) | 2 | 2 | Yes but lands in a 4-char slot for ~1 s — micro-delight, badly truncated |

---

## Parked — needs data the bike doesn't expose

These were assessed **infeasible** under the verified protocol limits.

- **Economy-vs-speed scatter (the km/L cost of riding fast)** — _value 2 / effort 2._ The fields exist, but the economy axis is scientifically broken: `fuelEconKmlV2` is a monotonic **trip-average** that ticks during engine-off idle (`TelemetryFrame.kt:41-45`), so averaging it per ride yields a smeared artifact, not the ride's true economy — correlating it against `avgSpeedKmh` mostly captures *where in the trip-meter cycle* the ride fell. The only honest economy source (fill-interval km/L) spans a whole multi-ride tank and has the wrong granularity for a per-ride scatter point. The "₹Y per 100 km penalty" callout would present a confidently-wrong number as fact, violating the no-assumptions rule. **The bike does not expose per-ride actual fuel consumption.**

- **Crash-confirm countdown mirrored on the cluster** — _value 2 / effort 3._ Rests on the false premise that "the cluster renders arbitrary phone-pushed ASCII." It does not (`NavFrame.kt`): a531 is a rigid 30-byte frame whose only renderable surfaces are a turn-icon byte and short numeric distance/eta slots — **there is no field that will render the string "CRASH? SOS in 30s".** The closest buildable thing (abusing `distNext` as "SOS" + a counting-down number) is a cryptic shadow of the proposal, and whether the cluster even renders non-numeric letters in those slots is untested. Independently, the target population (conscious, fine, phone in pocket) is largely the same one `CrashDetector` already filters out via its 8 s confirm window, so the countdown rarely starts for the cases it's meant to rescue.
