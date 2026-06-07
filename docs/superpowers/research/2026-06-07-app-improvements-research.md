# App Improvements & Feature Ideas — Gixxer BLE Companion

**Date:** 2026-06-07
**Scope:** Phone-side Android app + cluster repurposing for the 2023 Gixxer SF 150 BLE bridge.
**Method:** Code-read review. Citations spot-verified against source (Settings.kt:101-102, BikeBridgeService.kt:209/215/216/614-616, ActiveRideScreen.kt:155-163, TelemetryRepository.kt:27-34, and the absence of `ACTION_STATE_CHANGED` anywhere under `ble/`). Effort = S/M/L. Items needing the physical phone+bike to confirm are marked **[ON-BIKE]** or **[ON-DEVICE]**.

> No-assumptions caveat: every cluster-render claim in this app already ships under an UNVERIFIED-on-bike flag (IdleClockGenerator.kt:16-23, WelcomeFrame.kt:21). Ideas built on cluster rendering inherit that risk and are flagged. Nothing below claims a cluster behaviour proven that the codebase itself only assumes.

---

## Top picks (ranked by value-to-effort)

1. **Make onDestroy ride-flush actually run (S, data-integrity).** A near-certain data-loss bug: `onDestroy` launches `endRide()` on `lifecycleScope` then lets `super.onDestroy()` cancel that scope (BikeBridgeService.kt:614-616). Cheap fix, protects the data every stats/Wrapped feature stands on. Highest value-to-effort.
2. **Recover BLE on Bluetooth toggle off/on (S, reliability) [ON-DEVICE].** Nothing watches the adapter (zero `ACTION_STATE_CHANGED` hits in `ble/`); a common rider action silently wedges the bridge. Small receiver, big perceived-reliability win.
3. **Wire the ETA metric + tap-to-cycle on the ride overlay (M, UX).** Two of four advertised lower-third metrics render a dead `—` (ActiveRideScreen.kt:155-163) — a visible broken promise. Fix the honest half, drop the unfixable half.
4. **Cache cluster-toggle settings instead of re-reading DataStore at 1 Hz (S, perf).** Three `.first()` flow collections per second for the whole ride (BikeBridgeService.kt:209/215/216); the `stateIn` pattern is already in the same file (kmPerBarFlow, :198-200).
5. **SOS countdown survives process death even without exact-alarm permission (S, safety).** Worst-case safety gap: denied exact-alarm falls back to an in-process timer that dies with the process — the exact crash scenario (BikeBridgeService.kt:739-747).
6. **Greeting/idle context packs — time/weather/streak aware (S, delight).** Pure reuse of existing greeting, streak, and weather plumbing; high perceived personalization for low effort.

S-effort, high-value cluster: items 1, 2, 4, 5, 6 are all S. Item 1 ships first (correctness, no UX surface).

---

## Theme: Reliability / Robustness / Performance

### R1. Make onDestroy ride-flush run before scope cancellation — S
- **What:** Replace `lifecycleScope.launch { rideLogger.endRide() }` in `onDestroy` (BikeBridgeService.kt:614-616) with a bounded synchronous flush (timeout-guarded `runBlocking` on Dispatchers.IO), mirroring the accepted SosScreen.kt pattern.
- **Why:** On a clean service stop or OS reclaim, `super.onDestroy()` cancels `lifecycleScope` right after the launch, so the `endRide()` coroutine (Mutex + Room writes, RideLogger.kt:141-182) is very likely cancelled mid-write — leaving a ride that never closes (relies on the 10-min watchdog) or a missing post-ride summary. Ride integrity underpins every stats feature.
- **Feasibility:** `endRide()` is already a serialized suspend; project already accepts short `runBlocking` on Room/DataStore. No schema change.
- **Risk:** `runBlocking` in `onDestroy` must be tightly bounded or it risks an ANR if Room is mid-write. Verify a ride row closes on a real stop — observable in the Trips screen, **not** bike-dependent.

### R2. Recover the BLE link when Bluetooth is toggled off/on — S [ON-DEVICE]
- **What:** Register a `BroadcastReceiver` for `BluetoothAdapter.ACTION_STATE_CHANGED` in `BikeBridgeService` (onCreate/onDestroy). On STATE_OFF tear down GATT cleanly + surface a distinct ConnectionState; on STATE_ON re-issue `bleClient.connect(lastMac)`.
- **Why:** `connectGatt(autoConnect=true)` (BleClient.kt:103) recovers when the peer disappears, but toggling the phone's Bluetooth invalidates the GATT client object and autoConnect does not resurrect it. Airplane-mode and "let me toggle Bluetooth" are routine rider behaviours that currently wedge the bridge until the bike MAC is re-toggled.
- **Feasibility:** Adapter already held (BleClient.kt:72-73); `lastMac` tracked (BleClient.kt:67); `connect()` idempotent (BleClient.kt:79). Bluetooth-only, no schema change.
- **Risk:** STATE_ON fires before the stack is fully ready on some OEM ROMs — add a short reconnect delay. Toggle-recovery is only verifiable on the physical phone+bike. **On-device verification required.**

### R3. Single-flight guard for handshake-retry vs bikeMac-collector reconnects — M [ON-BIKE]
- **What:** `failHandshake()` runs its own disconnect+delay+connect loop (BleClient.kt:160-175) while a separate collector reconnects on any `settings.bikeMac` emission (BikeBridgeService.kt:536-546). `connect()` only resets `handshakeFailCount` on a MAC *change* (BleClient.kt:83), so a same-MAC re-pair or spurious re-emit can race the in-flight retry — overlapping `connectGatt` calls. Add an AtomicBoolean/Job "reconnecting" single-flight flag.
- **Why:** Overlapping `connectGatt` on the same device is a known source of status=133 GATT_ERROR / stuck pairing — likely the intermittent "won't connect, had to toggle the MAC" failure the retry was meant to cure.
- **Feasibility:** Both paths in code we own. `distinctUntilChanged` on bikeMac (BikeBridgeService.kt:537) reduces but does not eliminate the race. No schema change.
- **Risk:** Hard to reproduce; overlap window is narrow. Real proof needs on-bike connect/disconnect cycling with logcat. **Do not claim fixed without observing single-flight in the diag log.**

### R4. Cache cluster-toggle settings instead of re-reading DataStore every second — S
- **What:** The idle producer runs at 1 Hz and calls `idleClockEnabled.first()` / `nowPlayingOnCluster.first()` / `rangeOnCluster.first()` each tick (BikeBridgeService.kt:209/215/216), spinning up and cancelling a fresh DataStore flow collection per second per read. Replace with `stateIn`-backed StateFlows (`.value` reads on the tick), the pattern already used for `kmPerBarFlow` (BikeBridgeService.kt:198-200).
- **Why:** `.first()` spins a collector, hits the cache (occasionally disk), allocates per call — 3x/sec for the entire ride on the path meant to be the cheap idle loop.
- **Feasibility:** In-memory only; pattern proven in the same file. Defaults match Settings.kt definitions.
- **Risk:** Seed `stateIn` with `SharingStarted.Eagerly` + the DataStore default so the first tick isn't briefly wrong. Low risk, unit-testable off-bike.

### R5. Skip history snapshot allocation when no one observes TelemetryRepository.history — S
- **What:** `update()` does `historyBuffer.toList()` into `_history.value` on every a537 frame (TelemetryRepository.kt:27-34) regardless of observers. Gate the snapshot on `_history.subscriptionCount` so the 60-element copy only happens when a chart screen is foregrounded.
- **Why:** a537 arrives continuously; history is consumed only by chart/stats screens almost never foregrounded mid-ride (phone in pocket). Steady GC pressure for data nobody reads. (`_latest` keeps updating — RideLogger + cluster range tile depend on it; only the history copy is gated.)
- **Feasibility:** `TelemetryRepository` is a plain object exposing `subscriptionCount`. No schema change.
- **Risk:** A screen subscribing mid-ride sees an empty/partial window until the next frame — acceptable for a rolling 5-min view. Off-bike verifiable via the demo source.

### R6. Monotonic clock for crash-detector and ride-timing windows — S [ON-BIKE thresholds]
- **What:** CrashDetector measures impact→settle→confirm with `System.currentTimeMillis()` (CrashDetector.kt:85/98/102); RideLogger watchdog/duration too (RideLogger.kt:67/101/157). Switch interval math to `SystemClock.elapsedRealtime()` — already used correctly for the SOS alarm (BikeBridgeService.kt:729).
- **Why:** Wall-clock can jump (NTP, DST, manual set), prematurely firing or suppressing the 8s crash-confirm window or mis-measuring duration. For a safety feature a clock step could fire a false SOS or skip a real one. Strictly-more-correct swap.
- **Feasibility:** No cloud, no schema change. Keep wall-clock only where an absolute timestamp is stored (tMillis, autoName bucketing RideLogger.kt:219).
- **Risk:** Must not change stored `tMillis` semantics (wall-clock by design for display); only elapsed-delta comparisons change. Crash *thresholds* remain UNVERIFIED on-bike per the existing CrashDetector docstring — orthogonal to this fix.

---

## Theme: Safety

### S1. SOS countdown survives process death even without exact-alarm permission — S [ON-DEVICE]
- **What:** `onCrashSuspected` schedules an AlarmManager exact alarm (survives death) but when `SCHEDULE_EXACT_ALARM` is denied falls back to an in-process `lifecycleScope` delay (BikeBridgeService.kt:739-747) — which dies with the process, the exact scenario (a crash) where the process is most likely killed. Fall back to an inexact alarm (`setAndAllowWhileIdle`) or a WorkManager one-shot.
- **Why:** Worst-case safety path: real crash, exact-alarm denied, process killed by impact or OEM task-killer → SOS never sends. An inexact alarm fires (maybe slightly late) across process death; an in-process delay guarantees silence.
- **Feasibility:** SMS+location via SosController, SIM/Bluetooth-independent. `sosArmed` already persisted (Settings.kt:131, set at BikeBridgeService.kt:719) so the receiver no-ops if disarmed. No schema change.
- **Risk:** Inexact alarms can be Doze-delayed minutes — document the tradeoff vs the exact path. Full SOS firing only verifiable with a controlled on-bike/phone test. **Do not claim it works untested.**

---

## Theme: UX / Polish

### U1. Active-ride overlay — make ETA real, drop road-type, tap-to-cycle metrics — M [ON-BIKE display only]
- **What:** Lower-third metric is rider-chosen, but `eta` and `road-type` are hard-coded dead `—` placeholders (ActiveRideScreen.kt:155-163). Wire `eta` to the live NavMux ETA slot when nav is active, drop `road-type` entirely, and add swipe/long-press to cycle the lower-third metric live (trip-A / fuel / range / ETA) without entering Settings.
- **Why:** Two of four advertised metrics show nothing — a visible broken promise. Live cycling lets the rider surface what matters now (range when low, ETA when navigating) exactly when Settings is unreachable.
- **Feasibility:** NavMux already carries eta/distNext/distTotal (idle frame formats ETA at IdleClockGenerator.kt:38); range already computed (HomeViewModel.kt:89, fuelEstimate). Cycle state can live in ActiveRideController or reuse `setActiveRideMetric` (Settings.kt:311). No schema change.
- **Risk:** ETA only exists during a live nav session — must fall back to `—` off-nav (honest, not faked). Road-type genuinely has no data source; removing it is correct, not fabricating it. The overlay itself is phone-side; only on-cluster mirroring of it (if any) is on-bike.

### U2. First-run cluster preview + dry-run "send test frame" wizard — M [ON-BIKE for test-send]
- **What:** Onboarding step (and a Settings re-entry) that uses the existing `ClusterPreview` composable to show the rider's chosen idle screen / greeting / range live, plus a "send test frame to bike" button to confirm rendering before relying on it mid-ride.
- **Why:** Every cluster feature ships flagged ASSUMED/unverified-on-bike (IdleClockGenerator.kt:16, WelcomeFrame.kt:21). A guided preview + one-tap test turns the rider into the verifier in a safe parked context and de-risks the whole cluster-repurposing strategy.
- **Feasibility:** `ClusterPreview.kt` + `ClusterState.kt` already exist; `AppGraph.sendFrame` already used by the dev sweep (ManeuverSweepScreen). Reuses existing frame builders.
- **Risk:** The in-app preview is the app's *guess* of cluster rendering, not the cluster ROM — must label it "approximate preview" and make the on-bike test frame the source of truth (no-assumptions). Test-send needs an active link — gate the button on `ConnectionState.Ready`.

---

## Theme: New Features

### F1. Low-fuel + service "errand bundle" actionable home card — S [maps handoff phone-side]
- **What:** The home screen already computes the refuel bucket + fill-before-service co-prompt (HomeViewModel.kt:186-214, rendered as flat text in HomeScreen.kt:254/322/326). Make it actionable: tap opens maps to nearest fuel/service POI + offers a phone reminder; dims/snoozes once the rider logs a fill.
- **Why:** The co-prompt is read-only text today ("Refuel soon — service due too. Do both this trip."). The insight is correct (rides on exact odometer-gated kmRemaining); one tap converts a notice into a completed errand.
- **Feasibility:** RefuelPromptUi + bundle decision already exist. "Open maps" reuses the ACTION_VIEW geo intent already in ParkedHero (HomeScreen.kt:182). Snooze state = a DataStore timestamp keyed to last-fill odo, recompute-on-read, no Room.
- **Risk:** The refuel-bucket half is coarse by design (6-bar quantization, anecdotal km/bar) — tie the CTA to the reliable service-km half, not the soft fuel-day estimate. No offline POI DB exists; "nearest fuel" must hand off to the maps app, not claim to know stations (no-assumptions / no-cloud).

### F2. Cluster greeting + idle rotation context packs (time/weather/streak) — S [ON-BIKE legibility]
- **What:** Greetings are already user-editable and rendered via `renderGreeting` to a one-shot welcome frame (WelcomeFrame.kt:48-65). Add a context layer on top: pick the greeting by time-of-day + weather (a "RAIN" set when the Open-Meteo code is wet, a "NIGHT" set after dark), and surface a streak milestone ("DAY 12") the morning after a streak ticks.
- **Why:** Turns the welcome from a random line into a small daily ritual acknowledging context the rider already cares about. Pure delight, zero new data capture; reuses streak (HomeViewModel.kt:134) and weather (IdleClockGenerator already takes a weather code).
- **Feasibility:** Greetings already pass through `renderGreeting` into the same numeric/text slots IdleClockGenerator uses. Categorise existing greetings (or add named DataStore pools) + select by LocalTime + weather code. Streak already computed on read.
- **Risk:** Whether the cluster renders arbitrary letters in the slots is UNVERIFIED ON THE BIKE (IdleClockGenerator.kt:16-23, WelcomeFrame.kt:21). Greetings already ship under that same assumption, so this adds *selection* logic, not new render risk — but on-bike legibility confirmation still pending. Keep within proven char limits. **[ON-BIKE]**

### F3. Resend the current nav frame on reconnect — M [ON-BIKE render]
- **What:** FrameWriter dedups NAV frames by content via `_lastNav` (FrameWriter.kt:33-38). On a mid-nav drop the drain discards the in-flight frame (BikeBridgeService.kt:519-522); the producer only re-enqueues on the next `distinctUntilChanged` change (BikeBridgeService.kt:486). If the maneuver hasn't changed during the outage, the reconnected cluster shows its native idle with no current a531 until the next turn. On the Ready transition, force-resend the current navMux frame alongside the identity/welcome resend (BikeBridgeService.kt:363-390).
- **Why:** Mid-ride BLE drops are routine. After auto-reconnect the rider should immediately see the current turn arrow, not wait for the next maneuver. Built around (not overlapping) the in-progress maneuver-id-rework — this concerns delivery, not arrow-ID mapping.
- **Feasibility:** NavMux.frame exposes a current value; the Ready collector already resends URGENT identity. Add a NAV-priority resend there; clear `_lastNav` to force it past dedup. a531 stays a rigid 30-byte numeric frame, no new channel, no schema change.
- **Risk:** Must not resend a *stale* arrow contradicting a turn already taken during the outage — gate on an "active nav" signal once the maneuver-id-rework lands one. Cluster render is **on-bike-only** verification.

### F4. Auto-DND quiet hours + per-app cluster mirroring rules — M
- **What:** Extend the flat mirror allowlist (Settings.kt:101-102, DEFAULT_ALLOWLIST at :411) with a "quiet hours" window + a per-app priority tier, so only calls/SMS break through at night while chat apps stay muted on the cluster. Small schedule editor in NotificationsSettingsScreen, persisted entirely in DataStore.
- **Why:** Mirroring is all-or-nothing per package today; a commuter gets spammed by group chats on the cluster mid-ride. Quiet hours + a "calls/SMS only" tier makes the existing `autoDndOnConnect` (Settings.kt:67-69) feel intentional rather than a blunt switch.
- **Feasibility:** Allowlist is already a DataStore stringSet; add a stringSet for priority packages + two int keys for quiet-hour start/end. No Room. This only gates *which* notifications mirror, not *how* — no cluster free-text constraint.
- **Risk:** The actual mirror gate lives in the `notifications/` listener (not read in full here) — **verify the decision point reads the Settings flow synchronously enough.** No on-bike verification needed; phone-side logic.

### F5. Per-ride favourites, tags, and notes via a DataStore side-store — M
- **What:** Let the rider star a ride, attach notes, and tag it (commute / weekend / track) from TripDetailScreen. Because GixxerDatabase uses `fallbackToDestructiveMigration`, store these in a DataStore map keyed by ride id rather than new Room columns, and recompute filtered/favourite views on read.
- **Why:** Rides only have an auto-name (RideEntity.name, RideStore.kt:36) and a flat unfilterable list today. Favourites + tags make the trip list revisitable and feed per-category stats (commute vs weekend km) with no new capture.
- **Feasibility:** Feasible under no-new-Room-column. Filtering/grouping = recompute-on-read over `observeRides()` joined to the side-store.
- **Risk:** Ride `autoGenerate` id resets to 0 on destructive migration — **key the side-store on `startedAtMillis`** (stable natural key) to avoid silent mis-association. Orphaned entries after a wipe need a periodic prune. No bike verification needed.

### F6. Restore the theme accent picker + auto dark/light by sunrise/sunset — S
- **What:** DataStore `themeAccent` (Settings.kt:142-144) and `ACCENT_PALETTE` (Theme.kt:152-158) still exist but the picker is "retired." Re-expose a constrained accent choice (the 5 brand palette colours only) + an auto dark/TARMAC-light toggle that flips on the phone's day/night (sunrise/sunset from the same Open-Meteo/GPS the weather uses).
- **Why:** The design system ships both a dark cockpit and a TARMAC sunlight mode (Theme.kt:67-89) but no auto-switch — riders hit unreadable screens in bright sun. Auto day/night + a restrained accent is high perceived-personalization for low effort, respecting the rationed-green design rule by sourcing colours only from the palette.
- **Feasibility:** `GixxerTheme` already takes `darkTheme` (Theme.kt:23-27); `accentColorFor` + `ACCENT_PALETTE` + `themeAccent` flow exist. Add a DataStore enum (system/dark/light/auto); compute auto from civil sunrise/sunset off the GPS lat/lng already used for weather. No Room.
- **Risk:** Accent must stay sourced from GixxerTokens/ACCENT_PALETTE — hardcoded hex outside `ui/theme` is forbidden by the Konsist HardcodedHexLintTest (GixxerTokens.kt:7). "Retired" may be a deliberate design decision — **confirm with Arjun before un-retiring** rather than assuming it was an oversight.

---

## Verification ledger (open items)

| Item | Needs | Channel |
|------|-------|---------|
| R2, S1, R3 | physical phone+bike connect/disconnect/toggle cycling, logcat | on-device |
| R6 thresholds | controlled impact test | on-bike (orthogonal to the clock-swap, which is code-only) |
| U2, F2, F3 | cluster legibility / render of letters & arrows | on-bike (inherits existing ASSUMED flags) |
| F4 | the `notifications/` listener decision point (not fully read here) | code-read follow-up |
