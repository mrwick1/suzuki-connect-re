# GixxerBridge — Handoff

> Built overnight 2026-05-24 → 2026-05-25 by Arjun + Claude. Single-author from a
> spec, with parallel subagents for independent modules. This doc is the map for
> picking it up cold.

## What it is

A Kotlin/Compose Android app that replaces the official Suzuki Connect app for
the 2023 Gixxer SF 150. Decoded BLE protocol from Phase 1 is in `tools/protocol.py`;
this app is the runtime that consumes that knowledge.

Five tabs: **Home / Dashboard / Stats / Trips / Settings**. Inspector is
reachable from Settings → Developer.

## Project layout

```
android/
├── app/build.gradle.kts          # AGP 8.10, Kotlin 2.1, Compose BOM 2024.12, Material3 1.3
├── gradle/libs.versions.toml     # all version pins
├── app/src/main/kotlin/dev/mrwick/redline/
│   ├── GixxerApp.kt              # Application class — notification channel
│   ├── MainActivity.kt           # AppCompatActivity (biometric needs this), 5-tab NavHost
│   │
│   ├── app/AppGraph.kt           # Process-wide singletons (frameStream, bleClient, navMux, bikeInfo, …)
│   │
│   ├── ble/
│   │   ├── BikeBridgeService.kt  # Foreground service — composition root
│   │   ├── BleClient.kt          # BluetoothGatt wrapper, autoConnect=true
│   │   ├── BleScanner.kt         # Pair-wizard scan
│   │   ├── FrameWriter.kt        # Priority queue (URGENT > HEARTBEAT > NAV) + dedupe
│   │   ├── HeartbeatLoop.kt      # 1Hz a533 builder
│   │   ├── FrameStream.kt        # Hot TX+RX flow for the Inspector
│   │   ├── ConnectionState.kt    # sealed state machine
│   │   ├── SuzukiGatt.kt         # UUIDs (vendor 0xFFF0/1/2 + Device Info Service)
│   │   ├── BikeInfo.kt           # Device Info Service decoded snapshot
│   │   └── BootCompletedReceiver.kt
│   │
│   ├── protocol/                  # Kotlin port of tools/protocol.py — all 7 frame types
│   │   ├── Frame.kt, FrameCodec.kt
│   │   ├── NavFrame.kt (a531), CallFrame.kt (a532), HeartbeatFrame.kt (a533),
│   │   ├── MissedCallFrame.kt (a534), SmsFrame.kt (a535), IdentityFrame.kt (a536),
│   │   └── TelemetryFrame.kt (a537)
│   │
│   ├── nav/
│   │   ├── GoogleMapsParser.kt    # RemoteViews-walking turn parser (A1)
│   │   ├── ManeuverMap.kt         # text -> maneuver-id + persisted bitmap-hash table
│   │   ├── BitmapHasher.kt        # aHash64 for Maps icon Bitmaps
│   │   ├── ManeuverClassifier.kt  # bitmap-first lookup with text fallback + self-train
│   │   ├── IdleClockGenerator.kt  # clock+weather a531 frames
│   │   ├── WelcomeFrame.kt        # one-shot greeting a531
│   │   ├── MapsNavSource.kt       # singleton handoff: NotificationListener -> NavMux
│   │   ├── NavMux.kt              # priority mux: Maps > idle
│   │   └── ParsedNavData.kt       # intermediate DTO + toNavFrame()
│   │
│   ├── notifications/
│   │   ├── NotificationCaptureService.kt   # NotificationListenerService bind point
│   │   ├── NotificationDispatcher.kt       # per-package router (Maps/phone/SMS/allowlist)
│   │   ├── NowPlayingProvider.kt           # MediaSession polling
│   │   └── Dedup.kt                        # 8s cooldown by fingerprint
│   │
│   ├── telemetry/
│   │   ├── TelemetryRepository.kt # StateFlow<TelemetryFrame?> + 60-frame history
│   │   ├── RideLogger.kt          # auto-saves rides on telemetry; watchdog ends after 10 min silence
│   │   ├── DemoTelemetrySource.kt # synthetic a537 stream for UI work without bike
│   │   └── PhoneState.kt          # battery + signal observers
│   │
│   ├── data/
│   │   ├── Settings.kt            # DataStore prefs (~15 keys, see Keys object)
│   │   ├── BikeProfile.kt
│   │   ├── AppAllowlist.kt
│   │   ├── RideStore.kt           # Room entities + DAO (RideEntity, RideSampleEntity, RideLocationEntity)
│   │   ├── GixxerDatabase.kt      # @Database(v=2)
│   │   └── QuickDestinations.kt   # Home/Work quick-launch DataStore
│   │
│   ├── location/
│   │   ├── RideLocationTracker.kt # FusedLocationProvider @ 5s during rides
│   │   └── LastParkedTracker.kt   # Snapshot phone GPS on disconnect
│   │
│   ├── analytics/
│   │   ├── RideAnalytics.kt       # totalsFor / speedHistogram / calendarMap / personalBests / summarize
│   │   ├── AnalyticsModels.kt
│   │   └── RangeEstimator.kt      # km/bar from history + estimateRemainingKm
│   │
│   ├── weather/
│   │   ├── WeatherProvider.kt     # Open-Meteo OkHttp client (no API key)
│   │   ├── WeatherCodeMap.kt      # WMO -> Suzuki 0-11
│   │   ├── WeatherCache.kt        # 30-min poll, cache fallback
│   │   └── WeatherSnapshot.kt
│   │
│   ├── system/DndController.kt    # Auto-DND on connect
│   ├── safety/
│   │   ├── CrashDetector.kt       # 30 km/h drop heuristic
│   │   ├── SosController.kt       # SmsManager sender
│   │   └── SosScreen.kt           # full-screen "Are you OK?" prompt
│   │
│   ├── export/GpxExporter.kt      # Pure GPX 1.1 string builder
│   │
│   ├── util/
│   │   ├── Hex.kt, Bytes.kt, RingLog.kt
│   │
│   └── ui/
│       ├── home/                  # HomeScreen + ServiceDueBanner + LastParkedCard + QuickDestinationsCard + RideSummaryCard + ClusterPreview (via cluster/)
│       ├── dashboard/             # DashboardScreen + speed/fuel/odo/trip/range cards
│       ├── stats/                 # StatsScreen + charts/ (LineChart, BarChart, HistogramChart, CalendarHeatmap)
│       ├── trips/                 # TripsScreen + TripDetailScreen (+ Share GPX)
│       ├── inspector/             # Live BLE frame timeline (TX + RX, hex + decoded)
│       ├── compose/               # FrameComposerScreen — build any a5xx by hand
│       ├── settings/              # SettingsScreen + PairingScreen + AllowlistScreen + SafetySection + NotificationAccessRow + AboutCardLink
│       ├── cluster/               # ClusterPreview + ClusterState
│       ├── safety/                # SafetyViewModel + SafetySection
│       ├── lock/                  # AppLockGate (biometric)
│       └── about/                 # AboutScreen with version + protocol + bike info
│
└── app/src/test/kotlin/...        # 170+ unit tests (JVM only — no Robolectric); androidTest/ holds Room tests
```

## How to build

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
cd ~/coding/projects/suzuki-connect-re/android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.mrwick.redline.debug/dev.mrwick.redline.MainActivity
```

Tests: `./gradlew :app:testDebugUnitTest`

If KSP fails with "unexpected jvm signature V": that's KSP2 vs Room 2.6 — `gradle.properties` already pins `ksp.useKSP2=false`.

If a stale Kotlin incremental cache hits: `./gradlew … --rerun-tasks` clears it.

## Permissions checklist (granted at runtime on first launch)

- BLUETOOTH_CONNECT / BLUETOOTH_SCAN (BLE link)
- ACCESS_FINE_LOCATION (BLE scan + GPS ride tracking + last-parked snapshot)
- POST_NOTIFICATIONS (foreground service notif)
- BIND_NOTIFICATION_LISTENER_SERVICE (granted via Settings → Notification access — there's a "Grant" button in our Settings → Notification access row)
- SEND_SMS (only if SOS feature used)

## Where the data lives

- DataStore: `/data/data/dev.mrwick.redline.debug/files/datastore/{gixxer_settings,last_parked,quick_destinations}.preferences_pb`
- Room: `/data/data/dev.mrwick.redline.debug/databases/gixxer.db`
- Maneuver hash table: `filesDir/maneuver_hash_table.tsv`

Wipe all: `adb shell pm clear dev.mrwick.redline.debug` (resets perms too).

## Known unverified assumptions

See `docs/superpowers/specs/2026-05-24-assumptions-log.md` for the full list. The ones most likely to surface on first bike run:

| # | Risk |
|---|------|
| A1 / Maps notif | Maps version may change RemoteViews entry names. Run `tools/dump_maps_notification.py --watch` to verify. |
| A2 / Bitmap maneuver | The text fallback covers English perfectly; the bitmap-hash table starts empty and self-trains. Misses fall to maneuver 8 (generic arrow). |
| A12 / Heartbeat cadence | We send 1 Hz a533. If bike times out, drop to 2 Hz. |
| A13 / Suzuki Connect coexistence | We force-stop suzuki.com.suzuki in the smoke test but don't auto-do it. If both apps fight for the BLE link, disable Suzuki Connect in Android Settings. |
| A17 / Idle widget byte layout | We put clock in ETA, temp in distTotal, weather in distNext. Cluster might render these in unexpected positions. Test + adjust IdleClockGenerator. |

## Where the protocol is decoded

`tools/protocol.py` (Python) is the canonical reference. The Kotlin port in `android/app/src/main/kotlin/dev/mrwick/redline/protocol/` mirrors it byte-for-byte; both pass the same test fixtures.

## Smoke test

See `SMOKE_TEST.md` in the project root. 10 phases, binary pass/fail, capture logcat on fail.

## Feature additions in the second half of the build (E + F + G batches)

### E batch (Phase 6)
- **Onboarding wizard** (`ui/onboarding/`) — 4 steps: welcome, permissions, pair bike, start. Gated by `Settings.onboardingComplete`. Skips a lot of first-time confusion.
- **Fuel-fill log + true mileage** (`ui/mileage/`, `data/FuelFill.kt`, `analytics/MileageAnalytics.kt`) — record receipts, compute per-tank km/L. Reachable from Stats and Settings → Maintenance.
- **Empty-state polish** across all screens — actionable copy instead of bare "—".

### F batch (Phase 9)
- **Branded app icon** — cluster gauge + cyan chevron on deep navy. Recognizable in launcher next to other apps.
- **Active ride card** (`ui/home/ActiveRideCard.kt`) — pulsing green RIDING badge + live elapsed/distance/avg/max while riding.
- **Permission grant rows** (`ui/settings/PermissionRow.kt`) — reusable card; concrete `DndAccessPermissionRow`, `SendSmsPermissionRow`, `NotificationAccessRow` with re-check on screen resume.
- **Service notification reflects state** + tap opens MainActivity.
- **Inspector hex colorization** — per-frame-type colors on the type byte for fast scan.
- **About screen** — full diagnostics + "Reset all data" + "View on GitHub" + long-press version row to copy diagnostics.

### G batch (Phase 10)
- **Keep-screen-on while connected** — opt-in flag in Settings; window keeps screen awake while bike is Ready.
- **Reset onboarding** — Settings → Developer button to replay the wizard.

## What was deliberately deferred

- iOS port
- Play Store distribution (no signing key, debug build only)
- Multi-bike support
- Cloud sync of rides
- Tasker plugin / Home Assistant integration
- Voice control
- Geofence + anti-theft
- Audio TTS via Bluetooth helmet
- Per-app silencer (allowlist toggle covers 90% of value)

These are in the "Future" tier of the spec. Each is 2-5 hours of focused work; revisit when the bike-side features are stable.
