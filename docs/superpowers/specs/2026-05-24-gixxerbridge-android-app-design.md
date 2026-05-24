# GixxerBridge — Android App Design

**Date**: 2026-05-24
**Author**: Arjun KR (with Claude)
**Scope**: Phase 2 + Phase 3-A combined. A Kotlin/Compose Android app that replaces Suzuki Connect on the K20 Pro, drives the bike cluster with Google Maps navigation, mirrors phone notifications, and exposes live telemetry on the phone.
**Target ship**: 2026-05-25 morning. Working debug APK side-loaded onto the K20 Pro and verified end-to-end against the 2023 Gixxer SF 150.

## Background

Phase 1 of this project (see `2026-05-23-phase1-protocol-understanding-design.md`) is functionally complete: the BLE protocol is decoded, all 7 frame types are encoded/decoded in `tools/protocol.py`, no application-layer auth exists, no encryption is in play. We can construct any frame and the bike will accept it.

GixxerBridge is the consumer of that work. It replaces the official Suzuki Connect Android app — which uses Mappls for navigation, ships with a renewal-banner cloud component, and locks the user into a closed ecosystem. The replacement gives Arjun: Google Maps nav on the cluster, all the side features Suzuki ships (call/SMS mirroring, dashboard data), plus features Suzuki does not (live telemetry on the phone, idle cluster widgets showing time + weather + now-playing, a built-in BLE frame inspector for ongoing protocol work).

The decision to merge Phase 2 (Google Maps replacement) and Phase 3-A (custom cluster display) into one app is deliberate — the same foreground service and BLE link power both. Splitting them would mean two apps fighting over the BLE connection.

## Goals

1. **Replace Suzuki Connect for daily use** on the K20 Pro. After install + first-pair, Arjun can uninstall or disable Suzuki Connect with no loss of bike-side functionality.
2. **Google Maps turn-by-turn on the cluster** with feature parity to the Mappls-driven experience.
3. **Live bike telemetry on the phone** (speed, fuel, odo, trip, fuel economy) — something Suzuki Connect does not surface in real time.
4. **Useful idle cluster** — when not navigating, show time + weather + (optionally) now-playing instead of "Searching for network."
5. **Phone notification mirroring** — calls, SMS, WhatsApp, and arbitrary configurable apps.
6. **Built-in dev tooling** for ongoing protocol work — live frame inspector, custom frame composer, replay-from-pcap.
7. **Local-only.** No login, no cloud, no analytics. Open-Meteo (no key) is the one external dependency, used only for weather.

## Non-Goals

- iOS port.
- Play Store distribution. Debug APK side-loaded via adb only.
- Multi-bike support.
- Cloud sync of trip data, accounts, or settings.
- OBD-II / firmware updates / any bike-side write that goes beyond known a531-a536 frames.
- Replicating the Suzuki cloud subscription / renewal flow. The bike doesn't need it (verified in Phase 1).
- Active fuzzing of the bike's BLE surface beyond known frame types.
- Replacement of Google Maps' actual routing — we consume its notifications, not its API.

## Constraints

- **Phone**: Redmi K20 Pro, LineageOS Android 16, KSU root. Min SDK 29, target SDK 34 (Compose-friendly, fits Android 16).
- **Bike**: 2023 Suzuki Gixxer SF 150. BLE MAC in `LOCAL_NOTES.md`. Single service `0xFFF0` with write `0xFFF1` and notify `0xFFF2`.
- **Bonding**: bike accepts open BLE pair (no PIN, no out-of-band). Arjun will perform the initial pair from Android Settings once.
- **Suzuki Connect interference**: Suzuki Connect must be force-stopped / disabled on the same phone, or it will race for the BLE connection. Built-in setup screen will detect this.
- **No subscriptions / API keys**: weather via Open-Meteo (free, no key); no other external services.
- **JDK 17 required** for Android Gradle Plugin. Arjun's laptop has JDK 26 (too new). One-time `sudo pacman -S jdk17-openjdk` install needed.
- **Storage**: app data + ride logs stay on phone. No background uploads.

## Feature Inventory

Features are tiered by what they cost and what they buy. All tiers are in scope tonight; the tier marks ship order and which can be cut if time runs out.

### MVP (must ship, no exceptions)

| # | Feature | Frame(s) |
|---|---------|----------|
| 1 | BLE connect + auto-reconnect to bike (foreground service) | — |
| 2 | Identity frame on connect (a536 with rider name) | a536 |
| 3 | 1 Hz heartbeat with phone battery, time, weather, signal | a533 |
| 4 | Google Maps turn-by-turn → cluster (a531) | a531 |
| 5 | Phone calls (in / missed) → cluster | a532, a534 |
| 6 | SMS + WhatsApp → cluster | a535 |
| 7 | Live telemetry decode (a537) → phone dashboard | a537 (RX) |
| 8 | Idle cluster: clock + weather code/temp via a531 | a531 |
| 9 | First-run pair flow: pick bike from scan, save MAC + name | — |
| 10 | Suzuki Connect detection on launch (warn if installed + enabled) | — |
| 11 | Notification permission flow (BIND_NOTIFICATION_LISTENER_SERVICE) | — |

### Stretch (target — pull in as we go)

| # | Feature | Notes |
|---|---------|-------|
| 12 | Generic notification passthrough — any user-allowlisted app → a535 | Music apps (Spotify, YT Music), Telegram, Slack, etc. |
| 13 | Per-ride summary card | Auto-saved on disconnect; date, duration, distance, max speed, avg speed, fuel burned |
| 14 | Trip log screen | List of all past rides with summary + delete |
| 15 | Live BLE frame inspector | Hex + decoded view, scrolling timeline of TX + RX |
| 16 | Custom frame composer | Build any a5xx, send to bike (dev tool) |
| 17 | Auto-DND on bike connect | Restore phone's previous DND state on disconnect |
| 18 | Now Playing on cluster idle widget | Song · artist scrolling in a531 text region when idle |
| 19 | Phone battery + signal bars on idle cluster | Already in a533 but also surfaced as idle a531 text |
| 20 | Service-due reminder | Configurable interval (default 5000 km); banner when due |
| 21 | App lock | PIN or biometric on app launch |
| 22 | Replay-from-pcap mode | Load any project pcap, replay its TX stream to the bike |
| 23 | Demo mode | Simulate a537 stream for UI work without the bike |
| 24 | Welcome message on connect | "Hi Arjun · 27 °C · Sunny" or similar one-shot a531 |
| 25 | Logcat export + bug report bundle | Share-sheet target: zip of last N MB of logs + last 100 frames |

### Future (deferred, document in spec but don't ship tonight)

| # | Feature | Why deferred |
|---|---------|--------------|
| 26 | Fuel-fill log + true mileage tracker | Needs persistence schema and UI; not core |
| 27 | GPX export of rides | Requires phone GPS recording on top of the a537 stream; more code than buys |
| 28 | Geofence + anti-theft alert | Needs background location which is a permission rabbit hole on Android 16 |
| 29 | Crash detection from a537 deceleration | Needs tuning and false-positive testing; out of one-night scope |
| 30 | Tasker plugin / Home Assistant MQTT | Integration scaffolding for users who don't have HA isn't worth the time |
| 31 | Multiple rider profiles | Single-user is fine; can extend later |
| 32 | Voice control via Assistant intents | Nice but doesn't move the needle for the bike |
| 33 | Stock/crypto/quote ticker idle modes | Easy but feature creep |
| 34 | Caller-ID lookup for unknown numbers | Needs READ_CONTACTS permission; not worth the UX cost |
| 35 | Per-app silencer / time-of-day filters | Allowlist + on/off toggle covers 90% of value |

## Architecture

Single-module Android app, MVVM-lite with a thin service-and-repository core. No DI framework — `object` singletons where needed, constructor injection where not.

```
suzuki.gixxer.bridge/
├── app/
│   └── src/main/kotlin/dev/mrwick/gixxerbridge/
│       ├── GixxerApp.kt                  # Application class, single-process
│       ├── MainActivity.kt               # Hosts NavHost + Compose screens
│       │
│       ├── ble/
│       │   ├── BikeBridgeService.kt      # Foreground service, owns BLE link
│       │   ├── BleClient.kt              # connectGatt + write + notify wrapper
│       │   ├── FrameWriter.kt            # Queue + pacing for a5xx writes
│       │   └── FrameStream.kt            # Hot flow of TX + RX frames for inspector
│       │
│       ├── protocol/
│       │   ├── Frame.kt                  # Common frame helpers (header, csum, terminator)
│       │   ├── NavFrame.kt               # a531
│       │   ├── CallFrame.kt              # a532
│       │   ├── HeartbeatFrame.kt         # a533
│       │   ├── MissedCallFrame.kt        # a534
│       │   ├── SmsFrame.kt               # a535
│       │   ├── IdentityFrame.kt          # a536
│       │   └── TelemetryFrame.kt         # a537
│       │
│       ├── nav/
│       │   ├── NavSource.kt              # sealed Flow<NavFrame?> producer
│       │   ├── GoogleMapsParser.kt       # Notification -> NavFrame
│       │   ├── IdleClockGenerator.kt     # time + weather -> NavFrame
│       │   ├── NavMux.kt                 # priority mux: Maps > NowPlaying > IdleClock
│       │   └── ManeuverMap.kt            # Maps icon resId -> Mappls maneuver id
│       │
│       ├── notifications/
│       │   ├── NotificationCaptureService.kt   # NotificationListener
│       │   ├── PhoneEventBridge.kt             # call/SMS/missed-call -> a532/a534
│       │   ├── AppNotificationBridge.kt        # allowlisted apps -> a535
│       │   └── NowPlayingExtractor.kt          # media-session metadata
│       │
│       ├── weather/
│       │   ├── WeatherProvider.kt        # Open-Meteo HTTP client (OkHttp + kotlinx.serialization)
│       │   └── WeatherCodeMap.kt         # WMO -> Suzuki 0-11
│       │
│       ├── telemetry/
│       │   ├── TelemetryRepository.kt    # StateFlow<TelemetryFrame?>
│       │   └── RideLogger.kt             # Auto-record rides on connect, save on disconnect
│       │
│       ├── data/
│       │   ├── Settings.kt               # DataStore-backed prefs
│       │   ├── BikeProfile.kt            # mac, name, paired-at
│       │   ├── RideStore.kt              # Room db for ride summaries + samples
│       │   └── AppAllowlist.kt           # which apps mirror to bike
│       │
│       ├── ui/
│       │   ├── theme/                    # Material3, custom palette
│       │   ├── home/
│       │   │   ├── HomeScreen.kt         # connection state + quick actions
│       │   │   └── HomeViewModel.kt
│       │   ├── dashboard/
│       │   │   ├── DashboardScreen.kt    # live telemetry, big gauges
│       │   │   └── DashboardViewModel.kt
│       │   ├── trips/
│       │   │   ├── TripsScreen.kt        # ride log list
│       │   │   ├── TripDetailScreen.kt
│       │   │   └── TripsViewModel.kt
│       │   ├── inspector/
│       │   │   ├── InspectorScreen.kt    # live frame stream, hex + decoded
│       │   │   └── InspectorViewModel.kt
│       │   ├── compose/
│       │   │   └── FrameComposerScreen.kt   # build + send arbitrary frame
│       │   ├── settings/
│       │   │   ├── SettingsScreen.kt
│       │   │   ├── PairingScreen.kt
│       │   │   ├── AllowlistScreen.kt
│       │   │   └── SettingsViewModel.kt
│       │   └── lock/
│       │       └── AppLockScreen.kt      # PIN / biometric
│       │
│       └── util/
│           ├── Hex.kt
│           ├── Bytes.kt
│           └── Logging.kt                # ring-buffered logs for bug-report export
│
└── app/build.gradle.kts                  # Gradle KTS, single module
```

### Components & their contracts

**`BikeBridgeService`** — Foreground service with persistent notification. Owns:
- one `BleClient` instance, connected to the bike MAC stored in `Settings`
- one `HeartbeatLoop` coroutine sending a533 at 1 Hz
- one collector on `NavMux.frame` that forwards a531 frames to `FrameWriter` at ~2.7 Hz when the frame changes
- one `NotificationListener` connection to `NotificationCaptureService` for a532/a534/a535 frames
- one `TelemetrySink` that decodes incoming a537 from `BleClient.notifications` and pushes into `TelemetryRepository`

Lifecycle: starts on first user "Start" tap. Auto-restarts on boot if user enabled it. Stops on user "Stop" tap.

**`BleClient`** — Single `BluetoothGatt` connection. Public API:
- `suspend fun connect(): Result<Unit>`
- `suspend fun disconnect()`
- `suspend fun write(frame: ByteArray): Result<Unit>` — writes to 0xFFF1 with WRITE_TYPE_DEFAULT (with response), enforces 30-byte length
- `val notifications: SharedFlow<ByteArray>` — emissions from 0xFFF2 subscription
- `val connectionState: StateFlow<ConnectionState>` — Disconnected / Connecting / Connected / Failed

Internally uses `BluetoothGattCallback` and routes to coroutine continuations.

**`FrameWriter`** — Queues writes, paces them to avoid BLE write congestion. Strict order: a533 heartbeat → a531 nav (if changed) → other a5xx (event-driven). Max 3 writes per heartbeat tick to match the Suzuki app's behavior.

**`NavMux`** — `Flow<NavFrame?>` combining:
- `GoogleMapsParser.frame: Flow<NavFrame?>` (highest priority when emitting)
- `IdleClockGenerator.frame: Flow<NavFrame>` (always emits, takes over when Maps emits null)

The mux picks Maps when present, falls back to IdleClock otherwise. Debounces 250 ms to avoid jitter.

**`NotificationCaptureService`** (extends `NotificationListenerService`) — single entry point for all notifications. Dispatches:
- `com.google.android.apps.maps` package → `GoogleMapsParser`
- system phone calls (via `TelecomManager.callState` callback, not notification) → `PhoneEventBridge`
- system SMS → `PhoneEventBridge`
- allowlisted apps → `AppNotificationBridge`
- media notifications → `NowPlayingExtractor` (feeds `IdleClockGenerator` for now-playing scroll)

**`WeatherProvider`** — Open-Meteo `/v1/forecast?latitude=...&longitude=...&current_weather=true` poll. Uses last-known phone location (FusedLocationProviderClient, single-shot). Refreshes every 30 min. Caches last result in `Settings`. Maps WMO code via `WeatherCodeMap`.

**`TelemetryRepository`** — `StateFlow<TelemetryFrame?>` + ring buffer of last N=60 frames for graphing. Plus derived flows: `instantSpeed`, `fuelBars`, `tripA`, `tripB`, `fuelEconKml`.

**`RideLogger`** — On bike connect, starts a "ride" with start timestamp + initial odometer. Records a sample every 5s (one a537 per sample). On disconnect or 10-min telemetry silence, finalizes the ride: distance = end_odo - start_odo, avg/max speed, fuel bars delta. Saves to `RideStore` (Room DB).

### Data flow — Google Maps to cluster (the headline flow)

```
1. User opens Google Maps, picks destination, starts navigation
2. Maps posts an ongoing notification with extras:
   - EXTRA_TITLE = e.g. "In 220 m"
   - EXTRA_TEXT = street name or maneuver hint, e.g. "Turn right onto MG Road"
   - EXTRA_SUB_TEXT = "5 min · 1.2 km" (ETA + total distance)
   - smallIcon = R.drawable.ic_dir_<direction> (Maps' internal resource id)
3. NotificationCaptureService.onNotificationPosted fires with the StatusBarNotification
4. GoogleMapsParser extracts the fields, then:
   - Maps EXTRA_TITLE "220 m" -> dist_next="0220", dist_next_unit="M"
   - Maps EXTRA_SUB_TEXT "5 min · 1.2 km" -> dist_total="01.2", dist_total_unit="K"
   - Maps EXTRA_SUB_TEXT "5 min" + current wall clock -> eta="HHMMAM/PM"
   - smallIcon resId -> ManeuverMap.lookup(resId) -> Mappls maneuver int
   - status='1' always (we are pretending to have good cellular signal)
5. Constructs NavFrame, emits to NavMux
6. BikeBridgeService observes NavMux, sends a531 via FrameWriter to BleClient
7. Bike cluster renders arrow + distance + ETA
```

When Maps notification is dismissed, `GoogleMapsParser.frame` emits null; `NavMux` falls through to `IdleClockGenerator`, cluster shows clock+weather.

### Data flow — bike telemetry to phone

```
1. BleClient subscribes to 0xFFF2 on connect
2. Bike starts streaming a537 frames once we send our first a536 identity
3. Each notify byte[] is emitted on BleClient.notifications
4. TelemetrySink in BikeBridgeService decodes via TelemetryFrame.decode()
5. TelemetryRepository.update(frame)
6. DashboardViewModel observes the StateFlow, Compose recomposes gauges
7. RideLogger appends sample to current ride
```

### Data flow — phone call

```
1. TelephonyManager.callState changes to RINGING
2. PhoneEventBridge picks up via PhoneStateListener
3. Builds CallFrame(number=incoming_number, is_whatsapp=false, state=0x31)
4. Posts to FrameWriter (event-priority, sent within one heartbeat tick)
5. On call end with no answer -> MissedCallFrame -> a534
```

WhatsApp / Telegram calls come through the notification listener instead, since they don't trigger TelephonyManager.

## Key technical decisions

**Why native `BluetoothGatt` instead of FastBle / Nordic library** — We control the exact write timing and frame format already (`tools/protocol.py`). Adding a dependency that abstracts BLE wraps things we want to see. The Suzuki app uses FastBle, but that's a legacy choice; we don't need it.

**Why Compose over XML** — Faster iteration, less boilerplate, well-supported on min SDK 29. Material3.

**Why no DI framework** — One module, ~15 components. Hilt would be overkill. Object singletons + constructor injection.

**Why DataStore + Room (not SharedPreferences)** — DataStore for typed prefs, Room for ride history (multi-column queries, lazy loading). Both are first-party.

**Why Open-Meteo (not OpenWeatherMap)** — Free, no API key, decent global coverage including India. WMO weather codes are well-documented.

**Why merge Phase 2 and Phase 3-A** — Same BLE link, same foreground service. Two apps would compete for the connection. Keeps the entire stack in one process.

**Why 1 Hz heartbeat** — Matches what we observed Suzuki Connect doing in `f.java` (1s timer). Bike expects it; deviation may cause cluster timeouts.

**Why connect-with-response writes** — Reliable delivery confirmation. Marginally slower than write-without-response but well within 1 Hz budget.

**Why store ride samples at 5s cadence** — Matches the bike's a537 rate; no point sampling faster than the source.

**Why Open-Meteo WMO and not weather descriptions** — Mapping is unambiguous (WMO is a standardized integer code). Suzuki's 0-11 weather codes are also documented from `C.r()`.

## Permissions

| Permission | Why |
|------------|-----|
| `BLUETOOTH_CONNECT` (runtime, API 31+) | Connect to bike GATT |
| `BLUETOOTH_SCAN` (runtime, API 31+) | First-run scan for bike MAC |
| `ACCESS_FINE_LOCATION` (runtime) | BLE scan on Android < 12 requires it; also for weather location |
| `POST_NOTIFICATIONS` (runtime, API 33+) | Foreground service notification |
| `FOREGROUND_SERVICE` (normal) | BikeBridgeService |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` (API 34+) | Specific foreground service type |
| `READ_PHONE_STATE` (runtime) | Detect incoming/missed calls |
| `READ_SMS` (runtime) | SMS receiver |
| `RECEIVE_SMS` (runtime) | SMS receiver |
| `INTERNET` (normal) | Open-Meteo |
| `WAKE_LOCK` (normal) | Keep BLE link during screen-off |
| `RECEIVE_BOOT_COMPLETED` (normal) | Auto-start if user enabled |
| `BIND_NOTIFICATION_LISTENER_SERVICE` (declared on NotificationCaptureService) | The whole Maps/notification capture story |

Setup screen walks through enabling each in order, with a clear failure state if any is denied.

## UI / screens

1. **Home** — connection status, big "Start/Stop" toggle, current frame snapshot (small), last known telemetry summary
2. **Dashboard** — live speed gauge, fuel bars, odometer, Trip A, Trip B, fuel economy, instant connection indicator
3. **Trips** — list of past rides; tap to see detail
4. **Trip detail** — start/end time, duration, distance, avg/max speed, fuel bars at start/end, samples graph
5. **Inspector** — split view: live TX/RX frame stream (hex + decoded), filter by type, pause/clear
6. **Frame composer** — type selector + form fields for each frame, "Send" button, recent sends list
7. **Settings** — rider name, bike profile (re-pair), weather location override, allowlist, idle widget options, app lock toggle
8. **Pairing** — first-run wizard: scan → pick bike → save MAC + name → permission walkthrough
9. **Allowlist** — installed apps with toggle for each (defaults: Phone, Messages, WhatsApp, Telegram, Spotify, YT Music, Pocket Casts)
10. **App lock** — full-screen lock when app launched (if enabled)

Navigation: bottom bar with Home / Dashboard / Trips / Inspector / Settings. Composer is reachable from Inspector overflow.

## Risks & mitigations

| Risk | Mitigation |
|------|------------|
| Google Maps notification format varies by version / locale | Parser falls back to text-only parsing (regex on "In X m" + "Turn left/right") when extras incomplete; logs unknown shapes for iteration |
| Maps icon resource ID → maneuver mapping incomplete | Seed with text-derived guess (turn-left text → maneuver 4, etc.); log unknown icon resIds; nightly we improve table |
| Bike BLE bonding required (no open-pair) | Verified in Phase 1: open-pair works. If bond is needed, Arjun pairs from Android Settings once; we read the bonded device |
| Suzuki Connect on the same phone races for the BLE connection | First-launch detection + warning + offer "Disable Suzuki Connect" intent |
| BLE write throttling on Android causes frame drops | FrameWriter queues + retries; logs drops; 1 Hz heartbeat well within Android's BLE budget |
| Open-Meteo down / phone offline | Cache last weather for 6 h; if stale, send Suzuki code 0 (no data) — bike falls back to its own display |
| Music notification format different per app | NowPlayingExtractor uses `MediaSessionManager` (standard API) instead of parsing notification text |
| Bike disconnects mid-ride | BleClient auto-reconnects with exponential backoff; ride logger marks the gap |
| Maps gives ETA in wrong format for our 6-byte field | Parser normalizes to HHMMAM/PM (12-hour); user can set 24-hour preference |
| User has multiple bikes / multiple paired devices | Out of scope; single bike profile in MVP |
| Phone reboots mid-ride | RideLogger saves samples every 30s; on app restart, prompts "Resume previous ride?" |
| WhatsApp blocks notification reading (silent mode etc.) | Standard Android behavior; can't work around. Document in Settings |

## Build & distribution

- **Build host**: Arjun's Arch Linux laptop. JDK 17 (`sudo pacman -S jdk17-openjdk`), Android cmdline tools (download standalone zip to `~/Android/cmdline-tools/`), no Android Studio needed.
- **Gradle**: wrapper checked in; `./gradlew assembleDebug` from project root.
- **Output**: `app/build/outputs/apk/debug/app-debug.apk` (~10 MB target).
- **Install**: `adb install -r app-debug.apk` after enabling USB debugging.
- **Iteration**: rebuild + reinstall takes ~30s; logcat with `adb logcat -s GixxerBridge:* BikeBridgeService:* BleClient:*` for live debugging.

App code lives in the project repo at `android/` (new directory). Single git commit per milestone.

## Verification plan

Each milestone has a concrete verification step the human can do.

1. **Build succeeds** → APK file exists and is signed with debug keystore.
2. **App installs** → `adb install` exits 0; app icon appears in launcher.
3. **First-run wizard** → permissions granted, bike discovered in scan, MAC saved.
4. **BLE connects** → Home screen shows "Connected" within 5s of bike key-on.
5. **Identity sent** → bike cluster shows "Hi Arjun" (or whatever name). a537 notifies start streaming, visible in Inspector.
6. **Heartbeat valid** → Inspector shows a533 every ~1s. Cluster shows time + weather icon.
7. **Telemetry decoded** → Dashboard shows correct speed (compare to bike speedo), fuel bars, odometer.
8. **Google Maps nav** → start Maps nav to nearby destination. Cluster shows turn arrow + distance + ETA. Arrow updates as we approach turn.
9. **Notification mirroring** → call yourself; cluster shows incoming-call frame. Send SMS; cluster shows SMS sender.
10. **Idle widget** → stop Maps nav. Cluster falls back to clock + weather within 1s.
11. **Trip log** → take a 5-min ride. After disconnect, Trips screen shows the ride with correct start/end time + distance.
12. **Inspector** → toggle to Inspector mid-ride, see real-time frame stream.

A "smoke test checklist" doc will be generated alongside the spec.

## What I'll need from Arjun overnight

Minimal but specific:

1. **One sudo**: `sudo pacman -S jdk17-openjdk` (single command, Arjun runs in his terminal). Everything else is sudo-free.
2. **K20 Pro available** with USB-debugging on, plugged in or on Wi-Fi-debugging.
3. **Bike-side test access** during morning verification (key-on, paired) — not needed overnight while I'm coding.
4. **Open-Meteo coordinates** for default weather location — can derive from phone GPS on first run, but a fallback is good (e.g. Bangalore: 12.97, 77.59).

## Assumptions to verify (the "no assumptions" log)

Per project rule (`CLAUDE.md`): nothing in this spec is treated as fact unless verified. Below is every claim I made above that is currently **unverified** — each will be either confirmed before code that depends on it is written, or annotated in code with `// ASSUMED: …` so we can find it later.

| # | Assumption | Risk if wrong | Verification plan |
|---|-----------|---------------|-------------------|
| A1 | Google Maps' nav notification exposes turn data via standard `Notification.extras` (EXTRA_TITLE / EXTRA_TEXT / EXTRA_SUB_TEXT) | Parser doesn't work, no nav forwarding | Spawn research agent to read recent Google Maps notification-listener literature + current Maps version behavior; if extras are RemoteViews-only, fall back to reflection on the RemoteViews bound resource ids |
| A2 | Google Maps' turn icon resource IDs are stable enough to build a `resId -> maneuver` map | We map garbage to maneuver 0; cluster shows wrong arrow | Empirical: log every unique resId seen with the accompanying text; build table over time. Text parsing fallback covers the common cases ("turn right" → maneuver 4) |
| A3 | Bike accepts BLE writes with WRITE_TYPE_DEFAULT (with response) on 0xFFF1 | Frames silently dropped | Verify by reading what `tools/protocol.py`'s send path uses (bleak) and what worked in `forge_display.py`. If bike requires WRITE_TYPE_NO_RESPONSE, switch |
| A4 | Open-Meteo's WMO weather codes map cleanly to Suzuki's 0-11 codes (set by `C.r()`) | Wrong weather icon on cluster (cosmetic) | Look up both code sets, build a mapping table, document gaps |
| A5 | JDK 26 is too new for Android Gradle Plugin 8.x | Build fails on first attempt | Verify against AGP 8.x release notes; install JDK 17 if needed |
| A6 | Android cmdline tools standalone zip is sufficient (no full Android Studio install needed) | Have to add Android Studio install step | Verify against Google's docs; sdkmanager + Gradle wrapper should suffice |
| A7 | LineageOS Android 16 = API 34 (target SDK 34 is correct) | App fails to install / install with reduced features | Verify via `adb shell getprop ro.build.version.sdk` against Arjun's K20 Pro |
| A8 | `BIND_NOTIFICATION_LISTENER_SERVICE` works on LineageOS without OEM-specific hacks | No notification capture | LineageOS uses AOSP notification stack — should work; verify by reading the granted listeners list on first launch |
| A9 | `READ_SMS` is still grantable to non-default-SMS apps on Android 16 | Can't mirror SMS | Verify from Android 16 / API 34 docs; if not, fall back to NotificationListener for SMS (Messages app posts notifications anyway) |
| A10 | `TelephonyManager.callStateChanged` callback works for normal incoming calls on Android 16 | Can't mirror cellular calls | Verify; otherwise use NotificationListener on system phone app |
| A11 | `MediaSessionManager` exposes Now Playing metadata cross-app (Spotify, YT Music) | "Now Playing" idle feature broken | Standard Android API; verify it doesn't require additional permission on Android 16 |
| A12 | Bike's 1 Hz heartbeat cadence comes from observed Suzuki Connect behavior, not bike requirement | If bike cares about exact rate, our pacing may cause timeout | Verify by reducing heartbeat rate in dev mode and observing cluster — if it stays alive at 0.5 Hz, we know it's not a hard requirement |
| A13 | Suzuki Connect can be safely disabled / force-stopped without losing bike pairing | Re-pair friction for Arjun | Test: disable Suzuki Connect, launch GixxerBridge, see if bike still connects |
| A14 | Foreground service of type `connectedDevice` is the correct type for BLE on Android 16 | Service killed by OS | Verify from Android 14+ foreground service type docs |
| A15 | `BluetoothGatt.connectGatt` + manual reconnect handles the bike's connection lifecycle without `autoConnect=true` | Auto-reconnect doesn't work | Test both approaches; the Suzuki app uses FastBle's autoConnect setting; we should match |
| A16 | Maps icon parsing: `Notification.smallIcon` returns an `Icon` with a `resId` accessible to us | Can't identify the turn icon | If Maps uses an embedded bitmap instead, fall back to text parsing only |
| A17 | Bike accepts arbitrary text in a531 idle widget mode (clock/weather chars in non-Mappls positions) | Cluster ignores or garbles our idle widget | We already proved general text acceptance via `forge_display.py`; verify it survives the specific byte positions used by idle widget |
| A18 | The K20 Pro has working USB / Wi-Fi adb throughout the night | Can't iterate | Verify at start of session; have a fallback method (sideload via SD card or browser download) |
| A19 | DataStore + Room are available in min SDK 29 with current Jetpack libs | Build fails on dep resolution | Verify against current androidx artifact versions |
| A20 | Bike's "first notify starts after first a536 write" pattern (Phase 1 finding) is still true | Telemetry never starts | Already verified in M3; carry forward |

This list will be reviewed during implementation. As each is resolved (confirmed or refuted), the spec gets an inline edit and the assumption moves to a "Verified" or "Refuted" subsection with the evidence. Refuted ones trigger spec updates before continuing.

Anything new I assume during implementation (e.g. "this Kotlin pattern works on min SDK 29") gets logged in a running `docs/superpowers/specs/2026-05-24-assumptions-log.md` so it's all in one place.

## Orchestration plan (multi-agent strategy)

This is too big for a single session of straight-line work. I'll fan out parallel agents for research + independent module implementation, and serialize only the integration layer.

### Phases & parallelism

**Phase 0 — Setup (serial, me only)** — 30 min
- Verify Arjun's laptop env (JDK, adb, K20 Pro)
- Install JDK 17 (one sudo)
- Install Android cmdline tools (no sudo, ~/Android/)
- Scaffold the Android project skeleton (gradle wrapper, single module, build.gradle.kts, AndroidManifest)

**Phase 1 — Parallel research** — 30 min wall-clock
Dispatch 4 research subagents simultaneously, each answering one specific question. Each returns a short report I consume. **No code written in this phase.**
- **Agent R1**: Google Maps notification structure on current versions — what's in extras, what's in RemoteViews, can we get the icon's resId, do alternatives exist (Android Auto's CarAppService, accessibility service)
- **Agent R2**: WMO weather code semantics + map to Suzuki's 0-11 set
- **Agent R3**: Verify the assumptions list — JDK version, Android cmdline tools sufficiency, SDK API for LineageOS Android 16, foreground service type rules on Android 14+, READ_SMS on Android 16
- **Agent R4**: Audit `tools/protocol.py` and existing Phase 1 `forge_display.py` for the BLE write semantics we need to mirror in Kotlin (write type, response/no-response, MTU expectations)

**Phase 2 — Parallel module implementation** — 4-5 hours wall-clock
Once research is done and skeleton exists, dispatch independent coding agents per module. Each gets a tight contract + sample input/output + tests to satisfy. **Files written in parallel; integration done by me.**

Independent agent slots:
- **Agent C1**: `protocol/` package — full Kotlin port of `tools/protocol.py`. Pure functions, no Android deps. Comes with a unit test suite mirroring `tests/test_protocol.py`.
- **Agent C2**: `weather/` package — Open-Meteo client + WMO mapper. Pure HTTP + serialization, no Android UI.
- **Agent C3**: `nav/ManeuverMap.kt` + `nav/GoogleMapsParser.kt` (text fallback only initially — icon table is empirical, built later)
- **Agent C4**: `data/` package — Room entities, DAOs, DataStore prefs
- **Agent C5**: `util/` package — Hex, Bytes, Logging ring buffer
- **Agent C6**: First Compose screen prototype — `ui/inspector/InspectorScreen.kt` (good first screen because the data model is well-defined: list of frames)

**Phase 3 — Integration (serial, me)** — 3 hours
- Wire BLE service, frame writer, nav mux
- Hook NotificationCaptureService to the bridges
- Wire Compose screens to ViewModels to repositories
- This is where ownership of the integration matters; subagents can't see the whole graph

**Phase 4 — More features in parallel** — 2-3 hours
- **Agent C7**: Dashboard screen
- **Agent C8**: Trips screen + Trip detail
- **Agent C9**: Settings + Pairing + Allowlist screens
- **Agent C10**: Frame composer screen
- **Agent C11**: App lock

**Phase 5 — Final integration + smoke test** — 1 hour
- Wire remaining screens
- Build APK
- Generate smoke-test checklist for Arjun
- Bug fixes from logcat

### Agent contract conventions

Every coding subagent gets:
1. **Goal** (1-2 sentences)
2. **Files to create** (exact paths)
3. **Public API** (Kotlin function/class signatures the rest of the app will use)
4. **Behavior spec** (what it must do, with examples)
5. **Tests to satisfy** (specific assertions)
6. **Out-of-scope** (so it doesn't grow scope)
7. **No assumptions rule** — bring back questions instead of guessing; if must guess, mark `// ASSUMED: …` and report

Every research subagent gets:
1. **Question** (specific, scoped)
2. **Why we need to know**
3. **What "good enough" looks like** (rough format of the answer)
4. **Word limit** (< 300 words)

### Coordination

- I keep a live `docs/superpowers/specs/2026-05-24-orchestration-state.md` showing which agents are running, what they returned, what's blocked
- After each agent returns, I verify their work (run tests, eyeball code) before incorporating
- Assumption resolutions are logged in `docs/superpowers/specs/2026-05-24-assumptions-log.md`

### Hard limits

- Max 4 agents running in parallel (context budget)
- No agent runs > 20 min without a checkpoint
- If an agent comes back with "I assumed X to make progress," X gets logged and double-checked before merging

## Success criteria

Phase 2 + 3-A ships when:

- All MVP items pass verification on the K20 Pro against the actual bike.
- At least 70% of Stretch items ship and pass smoke tests.
- The codebase is structured for handoff — Arjun can read it cold and extend it without me.
- A short follow-up plan exists for Future items, scoped to next-session work.
- **All assumptions are either verified or explicitly catalogued in the assumptions log so they can be picked up later.**
