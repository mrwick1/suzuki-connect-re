# GixxerBridge Morning Smoke Test

> First-real-bike test, after a night of building. The app is feature-complete; verifying it actually works against your 2023 Gixxer SF 150 is the last step. Each phase below is binary pass/fail. Capture logcat on any fail.

## What's in the build

5-tab bottom nav: **Home / Dashboard / Stats / Trips / Settings**

Home: cluster preview (live mirror of a531 frames the app is sending), service-due banner, last-parked, riding summary (today + week), quick destinations (Home/Work), active-ride card (live stats while riding), connection card, Start button, pair button.

Dashboard: hero speed card (mono numerals, color-shifted by speed), fuel bars + range estimate, odometer, Trip A/B, trip avg fuel economy.

Stats: weekly/monthly/yearly km totals, riding heatmap (12 weeks), fuel-econ line chart, avg-vs-max bar chart per ride, personal bests grid.

Trips: list of past rides, GPX export per ride (FileProvider + share-sheet), tap to see ride detail with samples.

Settings: rider identity, bike profile, pairing, cluster toggles, phone behavior, runtime perm grants (DND, SMS, Notification access), service-interval reminder, fuel-fill log shortcut, allowlist, safety (crash detection + SOS), demo mode, frame inspector + frame composer, about screen.

Frame Inspector: live colorized hex + decoded view of every TX+RX frame. Filter by type. Pause/clear.

Frame Composer: build any a5xx by hand and send to the bike (dev tool).

## Setup

```bash
cd ~/coding/projects/suzuki-connect-re/android
adb devices                            # Redmi K20 Pro (5f9e4a44) plugged in
adb shell getprop ro.build.version.sdk # 36 (Android 16)
adb shell am force-stop suzuki.com.suzuki   # quiet the official app
```

## Phase 0 — App on phone

- [ ] Launcher shows GixxerBridge icon (cyan gauge + chevron on navy)
- [ ] Open app. If first run, the 4-step onboarding wizard appears (Welcome → Permissions → Pair → Start). Tap through.
- [ ] Bottom nav shows 5 tabs. Tap each — no crashes.

If onboarding doesn't appear: previous install left `onboardingComplete=true`. Reset via Settings → About → Reset all data, then re-launch.

## Phase 1 — Permissions

In Settings, scroll the perm rows:

- [ ] Notification access: Granted
- [ ] DND access: Granted (only needed if Auto-DND toggle ON)
- [ ] SMS sending: Granted (only needed if you set an emergency contact + want SOS)

If any not granted, tap "Grant" — Android opens the right settings page; toggle on; tap back.

## Phase 2 — Pair bike

1. Turn bike key ON.
2. Home → Pair / re-pair bike (or onboarding step 3).
3. Scan appears within 5 s; bike shows up as `SBM…` with a `74:B8:39:…` MAC.

- [ ] Bike appears in scan list
- [ ] Tap it; Pairing screen dismisses
- [ ] Settings → Bike section shows the saved MAC

## Phase 3 — Connect

1. Home → tap **Start GixxerBridge** (big cyan button)
2. Persistent notification: "Connecting to bike…" → "Bike connected" within 10 s
3. Tapping the notification opens the app

- [ ] Connection card on Home flips to **Connected** (green)
- [ ] Inspector tab shows:
  - One `a536` IDENTITY frame (TX) — your name in the bytes
  - One `a531` "welcome" frame (TX) — greeting
  - 1 Hz `a533` HEARTBEAT frames (TX)
  - ~5 s cadence `a537` TELEMETRY frames (RX) — speed 000 if engine off
- [ ] Dashboard: Speed = 0 (engine off), Odometer = your actual bike odo, Trip A/B match cluster, fuel bars = correct count
- [ ] Cluster preview (top of Home and top of Dashboard) shows the live a531 the app is sending

If a537 never starts → identity didn't reach the bike. Capture: `adb logcat -s BikeBridge:* BleClient:* | head -50`

## Phase 4 — Google Maps → cluster (the big one)

1. Open Google Maps. Set any nearby destination → Start nav.
2. Watch the bike cluster.

- [ ] Cluster shows a turn arrow + distance + ETA
- [ ] Inspector tab: a531 NAV frames stream at ~2-3 Hz with mono cyan-highlighted type byte

Variations:
- [ ] Turn right → cluster right arrow
- [ ] Turn left → cluster left arrow
- [ ] "Continue straight" → generic forward arrow
- [ ] Cancel Maps nav → cluster reverts to clock + weather (idle widget)

If parsing fails (cluster stays on "Searching for network"):
```bash
cd ~/coding/projects/suzuki-connect-re
python tools/dump_maps_notification.py --watch
```
Dumps Maps' notification structure so we can update GoogleMapsParser if Maps' RemoteViews layout changed.

## Phase 5 — Telemetry while riding

Take a short ride (parking-lot loop).

- [ ] Dashboard speed updates as you accelerate (mono numerals, color-shifts cyan→amber→red over 60/80 km/h)
- [ ] Trip A increments
- [ ] Home shows the **RIDING** card with pulsing green dot + live elapsed/distance/avg/max
- [ ] After disconnect (bike key off), Trips → a new ride card appears with start/end + distance + max speed
- [ ] Stats → fuel-econ line chart updates with the new data point

## Phase 6 — Notification mirroring

- [ ] Send yourself an SMS → cluster shows SMS frame briefly
- [ ] Call yourself from another phone → cluster shows incoming-call frame
- [ ] Decline / let it ring out → cluster shows missed-call frame
- [ ] Play a song in Spotify/YT Music → if "Show Now Playing scrolling text" enabled, cluster idle widget shows track title

## Phase 7 — Idle cluster widget

- [ ] Stop any Maps nav. Cluster shows clock + temp + weather icon (not "Searching for network")
- [ ] Wait ~30 min — weather refreshes from Open-Meteo. Inspector shows a533 byte 21 (weather code) / byte 22 (temp) change if weather changed

## Phase 8 — Dev tools

- [ ] Inspector → pause → frames stop scrolling
- [ ] Inspector → clear → list empties
- [ ] Settings → Developer → Demo mode toggle ON. Dashboard shows synthetic speed sweeping 0→80, odo incrementing. Toggle OFF.
- [ ] Frame composer (Settings → Inspector → ?? not reachable yet; via composer route): build a custom a531 with custom text → Send → see it on the bike cluster

## Phase 9 — Resilience

- [ ] Turn bike key OFF → Home shows Disconnected within ~10 s
- [ ] Turn bike key back ON → Home returns to Connected within ~15 s (autoConnect handles this)
- [ ] Reboot phone → if "Auto-start GixxerBridge after boot" enabled in Settings, the service comes back up after boot

## Phase 10 — Last parked

- [ ] After turning key off, Home → "Bike was last parked" card appears with the time + coords from phone GPS
- [ ] Tap "Open in Maps" → Google Maps opens to that coordinate

## Phase 11 — Quick destinations

- [ ] Set Home address → tap Home tile → Google Maps opens with driving directions to that address
- [ ] Same for Work

## Phase 12 — Stats + Fuel fill

- [ ] Stats screen → chartsrender (heatmap, line, bars)
- [ ] Settings → Maintenance → Fuel log / true mileage → add a fuel fill (odo + litres). After 2 fills, Mileage screen shows km/L computed
- [ ] Trip detail → "Share GPX" → share sheet opens; pick Files / Drive → confirm valid GPX

## Phase 13 — Safety

- [ ] Settings → Safety → enter your emergency contact phone
- [ ] Tap "SEND TEST SOS" → SMS is sent to that contact with a maps.google.com link

## Known unverified assumptions

See `docs/superpowers/specs/2026-05-24-assumptions-log.md`. The ones most likely to surface:

- A1 / Maps notif format may have shifted in newer Maps. The dumper tool covers it.
- A2 / Maps turn icons → maneuver mapping is empirical; text fallback covers all major English turns
- A12 / 1 Hz heartbeat — if cluster times out, halve the cadence in `HeartbeatLoop.HEARTBEAT_INTERVAL_MS`
- A13 / Suzuki Connect coexistence: force-stop the official app before testing
- A17 / Idle widget byte layout: cluster might render clock/temp in different positions than we expect

## When done

For each failed item, capture:
```bash
adb logcat -d > /tmp/gixxer-fail.log
adb shell dumpsys notification --noredact > /tmp/notif-dump.txt
```
Share `/tmp/gixxer-fail.log` + the screenshot of the failure.

Pass rate target: 80%+. The first run-on-bike test surfaces Android quirks that no amount of unit testing catches; most are 10-line patches once we see the symptom.
