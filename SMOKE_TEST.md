# GixxerBridge Smoke Test Checklist

> Run this in the morning when you wake up. Each step is binary pass/fail. Note anything weird at the end — the bugs will be in the integration layer (BLE bond, Maps notification shape, foreground service launch), not in the units (which have 145+ green tests).

## Setup

1. Laptop and K20 Pro both powered, phone unlocked, USB connected, bike key OFF.
2. From the laptop:
   ```bash
   cd ~/coding/projects/suzuki-connect-re/android
   adb devices                     # should show 5f9e4a44 device
   adb shell getprop ro.build.version.sdk   # should be 36
   ```

## Phase 0 — App launches cleanly

- [ ] Open the app launcher on the phone. **GixxerBridge** appears with the dark-blue icon.
- [ ] Tap it. App opens to a **Home** tab.
- [ ] Header reads "GixxerBridge", connection status shows "Idle".
- [ ] Bottom nav: Home / Dashboard / Trips / Inspector / Settings — all 5 tabs tap and load without crashing.

If the app crashes on launch, capture: `adb logcat -d | grep -iE "gixxer|fatal|androidruntime" > /tmp/crash.log`

## Phase 1 — Notification access grant

- [ ] Tap Settings tab → scroll to bottom → no special grant button yet, but visible.
- [ ] Open phone Settings → Apps → Special access → Notification access → **GixxerBridge** should be in the list. Toggle ON. Confirm.
- [ ] Back in GixxerBridge: Settings shows correct allowlist (default has Google Dialer, Messages, WhatsApp, Telegram, Spotify, YT Music, Pocket Casts, Discord, Slack).

## Phase 2 — Pair with the bike

1. Turn bike key ON. Wait 3 seconds.
2. In GixxerBridge → Home → tap **Pair / re-pair bike**.
3. Pairing screen says "Scanning…". Within 5 seconds the bike should appear with its name (SBM-something) and a MAC starting with `74:B8:39:…`.

- [ ] Bike shows up in the pairing list.
- [ ] Tap it. Returns to Home.
- [ ] Settings → Bike section shows the saved MAC.

## Phase 3 — Connect

1. Make sure Suzuki Connect is force-stopped: `adb shell am force-stop suzuki.com.suzuki`
2. On Home: tap **Start GixxerBridge**.
3. A persistent notification appears: "Connected to bike".

- [ ] Within 10s the Home connection card flips to **Connected** (green).
- [ ] Open **Inspector** tab. You should see:
  - One `a536` IDENTITY frame (TX) — your name in the bytes
  - One `a531` "welcome" frame (TX)
  - 1 Hz `a533` HEARTBEAT frames streaming (TX)
  - ~5s cadence `a537` TELEMETRY frames (RX) — speed=000 if engine off

- [ ] Open **Dashboard**. Speed = 0 (engine off), Odometer = your actual bike odo, Trip A/B match cluster, fuel bars = correct count.

If a537 frames never start, identity frame likely didn't reach the bike. Cross-check:
```bash
adb logcat -s BikeBridge:* BleClient:* | head -50
```

## Phase 4 — Google Maps → cluster (the big one)

1. Open Google Maps. Set any nearby destination, hit Start nav.
2. Watch the bike cluster.

- [ ] Cluster shows a turn arrow + distance.
- [ ] In GixxerBridge Inspector tab, `a531` NAV frames stream (~2-3 Hz) with maneuver text matching what Maps shows.

Variations:
- [ ] Turn right → cluster shows a right arrow
- [ ] Turn left → cluster shows a left arrow
- [ ] "Continue straight" → cluster shows generic forward
- [ ] Cancel nav → cluster reverts to clock + weather

If parsing fails (cluster stays on "Searching for network"), run the empirical dumper from the laptop:
```bash
cd ~/coding/projects/suzuki-connect-re
python tools/dump_maps_notification.py --watch
```
This dumps Maps' notification structure so we can update GoogleMapsParser if the field names changed in a recent Maps build.

## Phase 5 — Telemetry while riding

Take a short ride (parking lot loop).

- [ ] Dashboard speed updates as you accelerate.
- [ ] Trip A increments.
- [ ] After disconnect (key off), open Trips → a new ride appears with start/end time + distance + max speed.

## Phase 6 — Notification mirroring

- [ ] Send yourself an SMS → cluster shows the SMS frame briefly.
- [ ] Call yourself from another phone → cluster shows incoming call.
- [ ] Stop accepting the call → cluster shows missed-call frame.
- [ ] Play a song in Spotify/YT Music → if "Now Playing on cluster" enabled, cluster shows track scroll.

## Phase 7 — Idle widget

- [ ] With no Maps nav active, cluster shows clock + temp + weather icon (NOT "Searching for network").
- [ ] Wait ~30 min, weather refreshes from Open-Meteo (you can verify in Inspector: a533 byte 21/22 changes if weather changed).

## Phase 8 — Dev tools

- [ ] Inspector → tap pause → frames stop scrolling.
- [ ] Inspector → tap clear → list empties.
- [ ] Settings → toggle "Demo mode" ON → Dashboard shows synthetic speed sweeping 0→80, odo incrementing.
- [ ] Disable Demo mode.
- [ ] Frame composer (if reachable) → build a custom a531 with "Hi mom!" text → Send → see it on the bike cluster.

## Phase 9 — Resilience

- [ ] Turn bike key OFF → Home shows Disconnected within ~10s.
- [ ] Turn bike key back ON → Home returns to Connected within ~15s (autoConnect=true does its job).
- [ ] Force-stop the app (Settings → Apps → GixxerBridge → Force stop). Re-open → still works after granting permissions again. Service has to be manually re-started.
- [ ] Reboot phone → if "Auto-start on boot" enabled in Settings, GixxerBridge service starts on its own.

## Phase 10 — Known issues to verify (assumptions log open items)

Per `docs/superpowers/specs/2026-05-24-assumptions-log.md` — items still open at handoff:

- A2 (Maps icon bitmap classification): see if cluster shows the correct arrow type for each turn type. Misses will be silently logged as "Learned hash=X -> maneuver=Y" in logcat — `adb logcat -s ManeuverClassifier:I` to see new entries.
- A17 (cluster accepts our idle-widget layout): verify clock/temp render in correct positions. If misaligned, take a photo of the cluster + report.
- Maps notification format on the K20's Maps version: if Phase 4 fails, run `tools/dump_maps_notification.py` and share output.

## When done

- Note pass/fail for each item.
- For any fail, capture `adb logcat -d > /tmp/gixxer-fail.log` immediately and share.
- The first run-on-bike test is when Android quirks (BLE bond, OS-version-specific notification shapes) usually surface — most of these are 10-line patches once we see the actual symptom.
