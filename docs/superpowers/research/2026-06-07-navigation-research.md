# Navigation deep-dive — Gixxer BLE companion app

Date: 2026-06-07
Scope: cluster rendering / maneuver accuracy and the Google-Maps-notification data pipeline for the a531 nav frame.
Author: research pass (citations verified against source on 2026-06-07).

## Reading notes / ground rules

- Every idea below is scoped to **build on** the in-progress maneuver-id-rework
  (`docs/superpowers/plans/2026-05-25-maneuver-id-rework.md`,
  `docs/superpowers/specs/2026-05-25-maneuver-id-rework-design.md`), not to
  re-propose it. The rework owns the two-stage pipeline: Stage 1
  `MapplsIdGuesser.fromText` (text -> Mappls id) and Stage 2
  `ManeuverMap.mapplsIdToClusterByte` (Mappls id -> cluster byte). These ideas
  sit on top of those stages.
- **The big honest caveat (verified):** `docs/cluster-byte-glyphs.md` exists but
  the on-bike sweep is **unrun** — the file currently holds 51 `_empty_` /
  `_to fill in_` rows (plan lines 377-385). So *which cluster byte renders which
  glyph* is, for nearly all bytes, NOT yet known. Almost every "maneuver
  accuracy" idea here is blocked on running `ManeuverSweepScreen` on the physical
  cluster first. The exceptions where a byte's rendering IS confirmed on-bike:
  byte 8 renders a literal up-arrow (WelcomeFrame.kt:25-28, confirmed
  2026-06-06), and byte `0x2e`/46 (`NO_MANEUVER_BYTE`) is the OEM "no real icon"
  value (ManeuverMap.kt:17-28, still a HYPOTHESIS that it renders blank).
- **Second honest caveat:** the entire Google-Maps notification pipeline is
  **PARKED** (GoogleMapsParser.kt:19-24, parked 2026-06-04 because the
  notification-scrape -> guessed-Mappls-id path produced wrong arrows;
  `NotificationDispatcher` no longer routes Maps SBNs here). Every
  pipeline-section idea is *revival groundwork*, not a live fix. The current
  default cluster content is the idle producer, not Maps nav
  (BikeBridgeService.kt:227-239).
- Only one Maps capture exists and it is at **navigation start** (progress=0):
  `captures/maps-notification-dump-20260525-075849.md`. Anything about mid-route
  or arrival behaviour is unverified until captured on a real ride.

---

## Top picks

1. **OEM distance-rounding + sub-10km decimal formatter** (effort S, no on-bike
   block) — pure-JVM numeric fidelity for distNext/distTotal; the one idea here
   that is fully shippable and testable today without the cluster sweep.
2. **Status byte driven from real phone GPS state** (effort M) — replaces the
   hardcoded `status="1"` with truthful degraded/GPS-lost/recalc digits; uses
   cluster UX the rider already knows. Wiring is code-only; the per-digit
   rendering needs one ride capture to confirm.
3. **Roundabout exit-number disambiguation** (effort M) — the single biggest
   *accuracy* win inside the existing byte vocabulary (today `MapplsIdGuesser`
   can only ever emit Mappls 72 -> generic byte 45; the per-exit glyphs at bytes
   20-26 are unreachable). Gated on the sweep proving which byte is which exit.
4. **Pipeline-robustness pair for revival**: `shortCriticalText` distance source
   (S) + positive Maps-active gating by template+action (S). Both are
   ground-truth present in the capture and directly de-risk the un-parking.

Everything else is valuable but is either blocked on the unrun sweep, blocked on
the parked pipeline, or both — sequence those after picks 1-2 and after the
sweep.

---

## Section 1 — Cluster rendering & maneuver accuracy

### 1.1 Roundabout exit-number disambiguation
- **What:** Parse the exit ordinal from Maps' roundabout text ("take the 2nd
  exit") and select the specific exit-angle cluster byte instead of the generic
  3-arrow roundabout. Stage-1 enrichment of `MapplsIdGuesser`.
- **Why:** The generic roundabout glyph tells the rider nothing at the exact
  moment a glance matters. The ROM has seven distinct exit-angle glyphs
  (cluster bytes 20-26) that today's code can never reach.
- **Feasibility (cited):** Today `MapplsIdGuesser.fromText` returns only `72`
  for "roundabout" (MapplsIdGuesser.kt:29). Stage 2 already maps the per-exit
  Mappls ids 65-71 -> cluster bytes 20-26 (ManeuverMap.kt:245-251), while the
  generic 72 -> 45 (ManeuverMap.kt:252). So the work is purely in Stage 1:
  extract the ordinal, map ordinal -> Mappls 65..71. No frame/schema change.
- **Effort:** M.
- **Risk:** Which cluster byte (20-26) renders which exit angle is UNVERIFIED —
  `docs/cluster-byte-glyphs.md` rows for bytes 20-26 are `_empty_`. The
  ordinal->Mappls-65..71 ordering and the CW/CCW (left-hand-traffic / India)
  assumption must be confirmed with `ManeuverSweepScreen` on the bike before
  trusting it; a wrong mapping points the rider at the wrong exit, which is worse
  than the generic glyph. Maps phrasing for exits varies by locale/version and
  may omit the ordinal.

### 1.2 Status byte = real degraded / recalc / GPS-lost state  (TOP PICK)
- **What:** Drive the a531 status digit from actual phone state instead of the
  hardcoded `"1"`: GPS fix lost -> `'4'`, recalculating -> `'2'`, via-point
  reached -> `'3'`.
- **Why:** Today a stale or GPS-less fix still shows a confident arrow, which is
  actively misleading at a junction. The cluster has native UX for the
  non-normal states ("Searching for network"), so this surfaces truthful state
  the rider already understands, and gives a clean way to *suppress* a wrong
  arrow.
- **Feasibility (cited):** Status is hardcoded `"1"` in every producer:
  `ParsedNavData.toNavFrame()` (ParsedNavData.kt:42), `IdleClockGenerator`
  (IdleClockGenerator.kt:55, :92, :126), `WelcomeFrame` (WelcomeFrame.kt:83).
  Status semantics are decoded from the OEM source: `'1'`=normal,
  `'0'`=exit/airplane, `'2'`=X-flag (recalc?), `'3'`=b0-flag, `'4'`=GPS-lost,
  `'5'`/`'6'`=other flags; the cluster shows "Searching for network" UX when
  status is NOT in {1,3,5} (NOTES.md:183). Phone GPS fix state is already
  available as `LocationSample.accuracyM` / `speedMps`
  (RideLocationTracker.kt:30-37, :68-75). No schema change.
- **Effort:** M.
- **Risk:** Setting a degraded status makes the OEM logic force the maneuver byte
  to `0x2e` (no arrow) — NOTES.md:175, :191; desirable when truly degraded but
  must not fire spuriously. The exact rider-visible rendering for `'2'`/`'3'`/
  `'5'`/`'6'` is only partially pinned (NOTES.md:183 marks several as
  flag-named-but-unconfirmed). "Phone GPS lost" is not the same event as "bike
  GPS lost" — that equivalence is an assumption to check on-bike. Verify each
  digit's on-cluster rendering with one ride capture before wiring to real
  triggers.

### 1.3 Arrival / destination glyph + final-leg numeric treatment
- **What:** Detect Maps' arrival step and emit a terminal cluster byte plus
  zeroed distances, instead of leaving the previous turn arrow up at the moment
  of arrival.
- **Why:** Arrival is the one moment the rider definitely looks at the cluster,
  and right now it shows whatever the last turn was — the worst stale state.
- **Feasibility (cited):** `MapplsIdGuesser` already maps arrive/destination text
  to Mappls `40` (MapplsIdGuesser.kt:58), but Stage 2 has **no branch for 40**,
  so `mapplsIdToClusterByte(40, ...)` falls to `else -> null` (ManeuverMap.kt:256;
  design spec:144-146 confirms unmapped ids return null = "leave the cluster
  showing the last glyph"). The fix is to re-target arrival to a byte the table
  *does* map: the rework collapses Mappls 8/9/10 -> cluster byte 9, documented as
  the "destination/arrival cluster" (rework plan:64-67, :385;
  ManeuverMap.kt:211). `a0`/zeroed-distance handling exists (NOTES.md idle
  layout). No schema change.
- **Effort:** S (code) — but see risk.
- **Risk (significant):** Whether cluster byte 9 actually reads as an arrival
  marker is UNVERIFIED — `docs/cluster-byte-glyphs.md:385` (byte 9 row) is
  `_empty_`, and the project has previously noted there may be no dedicated
  "finish" glyph in the icon set. Without the sweep this trades a stale arrow for
  a wrong-but-different glyph. Arrival title strings are locale-specific and
  every existing capture is start-of-route, so arrival *detection* is also
  unverified. Blocked on the sweep + an end-of-route capture.

### 1.4 Bounded, confidence-gated self-train with a verified-glyph allowlist
- **What:** Replace the all-or-nothing self-train toggle with a guarded learner:
  register a `bitmap-hash -> Mappls-id` mapping only when (a) the text fallback
  returned a non-default id, (b) the same hash->id pairing repeats N times, and
  (c) the resulting cluster byte is one whose glyph has been on-bike-verified.
  Quarantine in memory; persist only after confirmation.
- **Why:** Self-train is OFF by default today specifically because "a single
  wrong text guess can poison the bitmap table indefinitely"
  (ManeuverClassifier.kt:18-20). That throws away the hash table's value — it
  never learns. A bounded, verified-only learner captures the bitmap-match
  reliability for ambiguous instructions without the runaway-pollution failure.
- **Feasibility (cited):** Machinery exists: `registerBitmapHash` appends
  last-write-wins (ManeuverMap.kt:80-85), `fromBitmapHashNearest` does the
  Hamming lookup (ManeuverMap.kt:100-113), and `classifyDetailed` already
  computes both ids and emits a DISAGREE warning (ManeuverClassifier.kt:60-69,
  :74-117). Add a counter map + a verified-byte set. Persistence is a TSV in
  `filesDir` (ManeuverMap.kt:41, :150-157), no Room concern. Stays in Mappls-id
  space, consistent with the rework.
- **Effort:** M.
- **Risk:** The verified-byte allowlist cannot be defined until the sweep
  populates `docs/cluster-byte-glyphs.md` (today all `_empty_`) — so this cannot
  ship before on-bike glyph verification. Also: the Hamming tolerance of 5/64 is
  flagged ASSUMED and possibly too loose for cross-icon collisions
  (ManeuverClassifier.kt:28-36); tighten it as part of this work or collisions
  will poison even a gated learner. Maps `largeIcon` hashing stability across
  Maps versions is unverified (only one capture, taken before any largeIcon was
  even examined for nav arrows).

### 1.5 OEM distance-rounding + sub-10km decimal rule  (TOP PICK — shippable now)
- **What:** Centralize one formatter that matches the OEM: round
  distance-to-next to nearest 10, switch to one-decimal below 10km (e.g.
  "05.6"). Apply to both distNext and distTotal.
- **Why:** These two distance slots are the main quantitative content the rider
  reads. Matching OEM rounding makes the readout feel native and smooths jittery
  last-meter counts (0237->0231->0228 becomes a calm 0240->0230->0220).
- **Feasibility (cited):** Current logic is split and inconsistent:
  `normalizeDistance` (ParsedNavData.kt:75-110) zero-pads/truncates ad hoc, while
  `distanceFromProgress` (GoogleMapsParser.kt:121-133) does its own "XX.X"
  formatting from meters. Replace both with one tested formatter. Pure JVM,
  covered by the existing unit-test patterns. No Room, no DataStore.
- **Effort:** S.
- **Risk:** Lowest of the set — no cluster-glyph dependency. Two things still
  want a live capture: the `distTotal` unit byte may be wrong/swapped in the OEM
  source (NOTES.md flags an inverted-unit possibility), and the exact rounding
  boundary (does 5m round to 0000 or 0010?) is unverified on-cluster. Match OEM
  byte-for-byte only after one capture confirms the unit byte.

### 1.6 Idle-frame readability pass (weather-code slot + layout verification)
- **What:** The idle clock writes the raw Suzuki weather *code* (0-11) as a digit
  into the distNext slot, which renders as a meaningless number beside the clock.
  Replace it with a temperature-only or short-mnemonic layout, and run all idle
  layouts (clock/temp, now-playing, range, welcome) through an on-bike check.
- **Why:** The idle frame is what's on the cluster the *majority* of the time
  (Maps nav is parked; idle is the default path, BikeBridgeService.kt:227-239).
  A weather digit nobody can read wastes a slot.
- **Feasibility (cited):** `IdleClockGenerator.build` puts
  `weatherStr = "%04d".format(suzukiWeatherCode...)` into `distNext`
  (IdleClockGenerator.kt:46, :51); temperature is already computed and placed in
  `distTotal` (IdleClockGenerator.kt:40-44, :53). All idle layouts route through
  this one class plus `WelcomeFrame`; producer rotation is at
  BikeBridgeService.kt:227-239. A layout change is local. No Room; idle prefs are
  DataStore.
- **Effort:** S.
- **Risk:** Every idle layout is explicitly flagged "ASSUMED ... NOT proven on
  the bike" (IdleClockGenerator.kt:16-23, :74-78, :110-115;
  WelcomeFrame.kt:18-21). The core unverified assumption — that the cluster
  renders arbitrary ASCII in the distance/eta slots and how many chars stay
  legible — must be confirmed with on-bike photos before investing in polish.
  Note the partly-disproven precedent: byte 8 was *assumed* a friendly default
  arrow but the bike renders it as a literal up-arrow (WelcomeFrame.kt:25-28).
  The `@`/`*`/` ` unit bytes and the blank-vs-glyph behaviour of `0x2e`/46 are
  also unverified (ManeuverMap.kt:25-28 HYPOTHESIS).

---

## Section 2 — Data-source / pipeline (Google-Maps notification)

> All of Section 2 is gated on un-parking the pipeline (GoogleMapsParser.kt:19-24).
> These are revival groundwork; sequence them as part of that revival.

### 2.1 Parse `android.shortCriticalText` for distNext  (TOP PICK for revival)
- **What:** Prefer Maps' own `android.shortCriticalText` extra (the short
  "In 200 m" / "now" status-bar chip) for distNext, with title-regex and
  progress/progressMax as fallbacks.
- **Why:** Distance is currently scraped from a free-form English regex on
  `android.title` (GoogleMapsParser.kt:114-118) or computed from
  `progressMax - progress` meters (GoogleMapsParser.kt:121-133).
  `shortCriticalText` is Maps' already-formatted, locale-aware short distance —
  far less fragile.
- **Feasibility (cited):** Field is present in the capture
  (`captures/maps-notification-dump-20260525-075849.md:49`,
  `android.shortCriticalText = String ()`). Read via
  `extras.getString("android.shortCriticalText")`. Additive to
  `parseFromExtras` (GoogleMapsParser.kt:66-111).
- **Effort:** S.
- **Risk:** The only capture is at navigation start (progress=0) and the field is
  **empty** there. Whether it is populated/formatted as "In 200 m" during an
  active turn is UNVERIFIED — must capture mid-route on the bike. Log raw values
  across a real ride before relying on the format.

### 2.2 Maps-active gating by template + action  (TOP PICK for revival)
- **What:** Treat an SBN as live nav only when
  `android.template == "android.app.Notification$ProgressStyle"` AND an action is
  present (the "Exit navigation" PendingIntent), so search/"Drive to" cards and
  stale persistent notifications don't drive bogus maneuvers.
- **Why:** Maps posts non-navigation notifications on the same package. Without
  positive gating those can produce the exact wrong-arrow failure that PARKED the
  pipeline on 2026-06-04 (GoogleMapsParser.kt:19-24). Makes the un-parking safer.
- **Feasibility (cited):** Both signals are ground-truth present:
  `android.template = android.app.Notification$ProgressStyle`
  (capture:33) and `actions[0] = Exit navigation` (capture:53). Today
  `MapsNavSource` relies only on a 60s stale watchdog (MapsNavSource.kt:28-50)
  plus `onRemoved`. Additive guard in `GoogleMapsParser.parse`
  (GoogleMapsParser.kt:51-60).
- **Effort:** S.
- **Risk:** The template string and action label change across Maps
  versions/locales ("Exit navigation" is English). Gate on template + presence-
  of-any-action, not the localized action title. Necessary-not-sufficient — keep
  the stale watchdog alongside it.

### 2.3 Phone-GPS-derived turn-distance refresh between notifications
- **What:** Maps refreshes its nav notification only every 2-5s; between updates,
  decrement distNext using GPS ground speed so the meters-to-turn count down
  smoothly, re-syncing to the authoritative value on each fresh notification.
- **Why:** A turn-distance that updates once every few seconds reads laggy
  (shows 200m when the rider is at 120m). Interpolating from speed the app
  already collects makes the most time-critical number feel live.
- **Feasibility (cited):** a531 supports 3-5Hz writes and the writer dedupes
  identical NAV frames (FrameWriter.kt:14, :33-36), so emitting interpolated
  frames is cheap. GPS speed is `LocationSample.speedMps`
  (RideLocationTracker.kt:36-37, :74). distNext is recompute-on-read, no Room.
- **Effort:** M.
- **Risk:** This is dead-reckoning: it can only decrement, cannot detect an
  early/late turn or going off-route — so it must hard-reset to Maps' value on
  every notification and never extrapolate past zero. Whether the cluster
  visibly benefits from >2Hz updates is UNVERIFIED on-bike. GPS speed is null at
  low speed and noisy (`speedMps` is nullable, RideLocationTracker.kt:74) — needs
  a floor/clamp. Rides on the parked pipeline.

### 2.4 Robust ETA: duration+wall-clock primary, clock-time secondary
- **What:** Restructure `normalizeEta` so a locale-independent "remaining minutes
  + wall clock" path is primary and the English "Arrive 8:17 am" clock regex is a
  refinement — so a non-English Maps locale never emits a garbage ETA but
  degrades to now+duration.
- **Why:** The a531 ETA slot is 6 chars (NavFrame.kt:18-19; bytes 9-14). A
  mis-parse writes a wrong arrival time. Today a non-English arrival string falls
  through to bare current wall-clock (ParsedNavData.kt:143-161), which looks
  right but isn't.
- **Feasibility (cited):** Pure-JVM string code in `normalizeEta`
  (ParsedNavData.kt:143-170), covered by existing test patterns. Ground truth
  confirms "Arrive 8:17 am" (capture:32). No schema/DataStore impact.
- **Effort:** M.
- **Risk:** Locale behaviour is genuinely unverified — only English en-IN
  captures exist (the ASSUMED note is at ParsedNavData.kt:138-141). Ship as
  "fails safe to wall-clock + duration"; do not claim multi-language works.
  Capture real non-English Maps strings on the bike first. The "N min" segment
  may be next-turn vs total-trip time (current `MINUTES_REGEX` at
  ParsedNavData.kt:125) — disambiguate on a real route.

### 2.5 Live-GPS weather (feed WeatherCache from RideLocationTracker)
- **What:** Switch `WeatherCache`'s coordinate source from the static DataStore
  lat/lng to the live `LocationSample` flow during a ride, falling back to the
  stored value when there's no fix.
- **Why:** The idle frame shows weather code + temp (IdleClockGenerator.kt:34-58,
  fed by `weatherCache.currentEncoded()` at BikeBridgeService.kt:160, :227). On a
  long ride the rider moves tens of km but weather stays pinned to a home
  coordinate. Live GPS makes on-cluster weather reflect where the bike is — the
  point of Open-Meteo over the Mappls SDK (per the phase2-weather-api memory).
- **Feasibility (cited):** `WeatherCache.latLngProvider` is already an injectable
  `suspend () -> Pair<Double,Double>?` (WeatherCache.kt:17, :35) wired today from
  settings with a Bangalore default (BikeBridgeService.kt:142-148, :788-789).
  `RideLocationTracker.samples` exposes lat/lng as a StateFlow
  (RideLocationTracker.kt:61-75). Swap the lambda; 30-min refresh keeps API cost
  flat (WeatherCache.kt:18). No Room change.
- **Effort:** S.
- **Risk:** Needs ACCESS_FINE_LOCATION granted (tracker `start()` no-ops without
  it, RideLocationTracker.kt:85-86); must fall back to the stored coordinate when
  no fix. Temperature stays phone-ambient, not engine temp — honour the
  no-cloud-telemetry constraint (Open-Meteo is generic weather HTTP, not bike
  data).

### 2.6 Decode `android.progressSegments` for total route distance
- **What:** Probe the `progressSegments` Bundle list (and `progressPoints`) to
  extract per-segment lengths and sum to a real total-route distance + segment
  count, feeding distTotal more reliably than regexing subText.
- **Why:** distTotal is currently scraped from subText with a generic distance
  regex (GoogleMapsParser.kt:230-239), which usually misses — the real subText
  "Arrive 8:17 am" contains no distance at all (capture:32).
  progressSegments + progressMax is the structured source the regex can't reach.
- **Feasibility (cited):** `progressSegments` and `progressMax` are present in the
  capture (`android.progressSegments = [Bundle[dataSize=124]]` at capture:41;
  `android.progressMax = 8734` at capture:38). `progressMax` already drives
  distance (GoogleMapsParser.kt:121-133); reading the Bundle list is the same
  access pattern, reflection-free.
- **Effort:** M.
- **Risk:** The segment Bundle's internal key names are UNKNOWN (only
  `dataSize=124` is visible). Must dump the segment `keySet()` on a live route to
  learn the schema. `progressMax` semantics (current-segment vs whole-route
  meters) is unconfirmed mid-route — the 8734 value was captured at progress=0.

### 2.7 Reroute detection via `progressMax` resets
- **What:** Track last-seen `progressMax` in `MapsNavSource`; on a significant
  unexplained change, reset any smoothing/last-good distance caches and recompute
  distances from the fresh values instead of carrying stale ones forward.
- **Why:** Any cached/last-good distance (or one the stale watchdog keeps alive)
  will show a shrinking distance that should suddenly grow on reroute, confusing
  the rider. Anchoring on `progressMax` gives a clean reroute boundary.
- **Feasibility (cited):** `progressMax` is confirmed present (capture:38) and
  already read (GoogleMapsParser.kt:121-133). `MapsNavSource` is a singleton that
  already holds per-update state + a watchdog (MapsNavSource.kt:32-50), so a
  prev-vs-current comparison fits there.
- **Effort:** M.
- **Risk:** "progressMax jump == reroute" is a HYPOTHESIS — Maps may bump
  progressMax for other reasons, and a single start-of-route capture can't
  confirm reroute behaviour. Observe progressMax across a deliberate off-route
  detour on the bike before trusting it. Pairs naturally with the 2.3
  interpolation cache reset.

### 2.8 Explicit arrival-frame handoff at route end
- **What:** When Maps signals arrival (title "You have arrived" /
  progress==progressMax / no further turns), emit a terminal NavFrame at cluster
  byte 9, then clear `MapsNavSource` cleanly rather than letting the 60s watchdog
  blank a stale mid-route arrow.
- **Why:** Today nav goes stale and the watchdog clears after up to 60s
  (MapsNavSource.kt:43-49), so the last arrow lingers nearly a minute after
  arriving. An explicit arrival glyph is the correct end-of-route signal.
- **Feasibility (cited):** Shares the byte-9 target with idea 1.3
  (ManeuverMap.kt:211; rework plan:64-67, :385). Arrival needs a dedicated path
  because Mappls 40 returns null (ManeuverMap.kt:256). Pure Kotlin, no schema;
  add detection from title text + progress saturation.
- **Effort:** M.
- **Risk:** Same blockers as 1.3: byte-9 rendering is UNVERIFIED
  (`docs/cluster-byte-glyphs.md:385` is `_empty_`), and arrival title strings are
  locale-specific and uncaptured (every dump is start-of-route). Verify byte 9
  on the cluster and capture an end-of-route notification first.

---

## What needs on-bike verification before anything ships (consolidated)

1. **Run `ManeuverSweepScreen`** to populate `docs/cluster-byte-glyphs.md` (51
   rows currently `_empty_`). This unblocks 1.1, 1.3, 1.4, 1.6, 2.8. Highest
   leverage single action.
2. **One mid-route Maps capture** (notification with progress>0): confirms
   `shortCriticalText` format (2.1), `progressMax` segment semantics (2.6, 2.7),
   the "N min" total-vs-next ambiguity (2.4).
3. **One end-of-route Maps capture:** confirms arrival title strings + progress
   saturation (1.3, 2.8).
4. **One ride with GPS-degraded moments:** confirms per-status-digit cluster
   rendering for 1.2.
5. **One distTotal unit-byte check:** confirms the possibly-inverted unit before
   matching OEM rounding byte-for-byte (1.5).

## Recommended sequencing

- **Do now (no bike needed):** 1.5 (distance formatter), 2.5 (live-GPS weather
  wiring — code-only, GPS already gated). Land 2.1 + 2.2 code behind the parked
  flag so revival is a flip, not a build.
- **After the sweep:** 1.1, 1.3/2.8 (arrival), 1.6, then 1.4 (gated self-train,
  which depends on the verified allowlist).
- **At pipeline revival:** 2.1, 2.2, 2.3, 2.4, 2.6, 2.7 together.
