# GixxerBridge Assumptions Log

> Per project rule (`CLAUDE.md` — "no assumptions"): every claim that powers
> code decisions in the GixxerBridge build gets logged here as it's made,
> verified, or refuted. Anything left UNVERIFIED at handoff is a known
> open thread for Arjun to chase.

## Status legend

- **OPEN** — claim made, not yet checked
- **VERIFIED** — checked against a primary source; evidence cited
- **REFUTED** — claim turned out false; spec/code updated; evidence cited
- **PARTIAL** — checked but with caveats; treat with care

## Log

### A1 — Google Maps nav notification format
**Status**: OPEN
**Claim**: Google Maps' active turn-by-turn notification exposes `EXTRA_TITLE` / `EXTRA_TEXT` / `EXTRA_SUB_TEXT` such that we can parse "In 220 m · Turn right · MG Road · 5 min".
**Why it matters**: GoogleMapsParser depends on this format. If extras are blank (Maps uses a custom RemoteViews layout), we need a different extraction path.
**To verify**: research current Maps notification behavior; or empirically dump a Maps notification on the K20 Pro and inspect extras.
**Resolution**: TBD

### A2 — Google Maps turn-icon resource IDs are stable enough to map
**Status**: OPEN
**Claim**: `Notification.smallIcon` returns an `Icon` whose `resId` (when type=Resource) is stable per Maps version, so we can build a `resId -> Mappls maneuver id` lookup table.
**Why it matters**: cluster shows wrong arrow if mapping is garbage.
**Mitigation already in plan**: text-parsing fallback ("turn right" → maneuver 4); log unknown resIds.
**Resolution**: TBD (will resolve empirically over time)

### A3 — Bike accepts WRITE_TYPE_DEFAULT (with-response) on 0xFFF1
**Status**: OPEN
**Claim**: Our Kotlin `BleClient.write` will use `BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT` (writeWithResponse) and the bike will accept it.
**Why it matters**: wrong write type → silent drops.
**To verify**: check `tools/forge_display.py` and `tools/protocol.py`-callers in the project for which `bleak` write API they use (`write_gatt_char(..., response=True)` vs `False`). Mirror that.
**Resolution**: TBD

### A4 — Open-Meteo WMO codes → Suzuki 0-11 codes
**Status**: OPEN
**Claim**: There's a clean mapping. Suzuki's codes from `C.r()`: 1=sunny, 2=cloudy, 3=fog, 6=rain, 7=snow, 11=windy + others. Open-Meteo uses WMO codes 0-99.
**Why it matters**: wrong weather icon (cosmetic, but visible).
**To verify**: dump both code sets, write mapping table, document gaps (default to 1=sunny).
**Resolution**: TBD

### A5 — JDK 26 too new for Android Gradle Plugin 8.x
**Status**: OPEN
**Claim**: AGP 8.x requires JDK 17 (or 21). JDK 26 will fail. Install JDK 17.
**Why it matters**: build won't start.
**To verify**: AGP release notes for the version we're targeting (current stable as of 2026-05); attempt build with JDK 26 first as a quick check.
**Resolution**: TBD

### A6 — Android cmdline tools alone are sufficient (no Android Studio)
**Status**: OPEN
**Claim**: We can build the app with `~/Android/cmdline-tools/latest/bin/sdkmanager` + Gradle wrapper, no Android Studio needed.
**Why it matters**: Android Studio install would be a multi-GB AUR yank.
**To verify**: Google's official docs say cmdline tools are sufficient for headless builds; sanity-check by running `sdkmanager --list` and downloading platform-34.
**Resolution**: TBD

### A7 — LineageOS Android 16 == API 34
**Status**: OPEN
**Claim**: K20 Pro on LineageOS Android 16 reports `Build.VERSION.SDK_INT == 34`. Target SDK 34 is correct.
**Why it matters**: install failure or reduced features if mismatched.
**To verify**: `adb shell getprop ro.build.version.sdk`.
**Resolution**: TBD (Arjun's device, easy to verify once we're ready to install)

### A8 — BIND_NOTIFICATION_LISTENER_SERVICE works on LineageOS
**Status**: OPEN
**Claim**: LineageOS preserves stock AOSP notification listener behavior; no OEM-specific quirks.
**Why it matters**: nav parsing dead-on-arrival otherwise.
**To verify**: research; LineageOS is generally close to AOSP — likely fine.
**Resolution**: TBD

### A9 — READ_SMS is still grantable on Android 16 to non-default SMS apps
**Status**: OPEN
**Claim**: We can request READ_SMS + RECEIVE_SMS at runtime and mirror SMS to bike.
**Why it matters**: SMS mirroring feature.
**Fallback if false**: use NotificationListener on the Messages app — same content, no SMS API needed.
**Resolution**: TBD

### A10 — TelephonyManager.callStateChanged fires for normal incoming calls on Android 16
**Status**: OPEN
**Claim**: PhoneStateListener / TelephonyCallback gives us RINGING / OFFHOOK / IDLE.
**Why it matters**: cellular call mirroring.
**Fallback**: NotificationListener on system Phone app.
**Resolution**: TBD

### A11 — MediaSessionManager exposes Now Playing cross-app
**Status**: OPEN
**Claim**: We can read currently-playing metadata (title, artist) from any app via `MediaSessionManager.getActiveSessions`.
**Why it matters**: Now Playing on cluster idle widget.
**Caveat**: `getActiveSessions` needs the NotificationListener to be a granted listener (Android security tie-in).
**Resolution**: TBD

### A12 — 1 Hz heartbeat cadence is not strict
**Status**: OPEN
**Claim**: Suzuki Connect sends a533 at 1 Hz (`f.java` 1s timer), but the bike likely tolerates slower; not a hard contract.
**Why it matters**: if bike does enforce, slower cadence leads to cluster timeouts.
**To verify**: empirical — drop to 0.5 Hz in dev mode, observe.
**Resolution**: TBD

### A13 — Suzuki Connect can be safely disabled without losing pair
**Status**: OPEN
**Claim**: Disabling Suzuki Connect doesn't unpair the bike from the phone at the BLE-bond level. Our app can take over the connection.
**Why it matters**: friction at install time.
**To verify**: empirical — disable Suzuki Connect, try to connect from a python script with `bleak`.
**Resolution**: TBD

### A14 — Foreground service type `connectedDevice` is correct on Android 14+
**Status**: OPEN
**Claim**: `foregroundServiceType="connectedDevice"` is the right value for a BLE-link foreground service on Android 14+ (post the Android 14 foreground service type enforcement).
**Why it matters**: service killed otherwise.
**To verify**: Android docs.
**Resolution**: TBD

### A15 — BluetoothGatt.connectGatt(autoConnect=true) reconnect semantics fit our needs
**Status**: OPEN
**Claim**: `autoConnect=true` makes Android automatically reconnect when the bike (peripheral) comes back in range — what we want for bike key-on auto-pair.
**Why it matters**: if false, we have to implement reconnect logic ourselves.
**To verify**: Android Bluetooth docs + Suzuki Connect's behavior (FastBle wraps both).
**Resolution**: TBD

### A16 — Maps `Notification.smallIcon` exposes a usable resId
**Status**: OPEN
**Claim**: `notification.smallIcon.resId` returns a non-zero int we can use as a lookup key.
**Why it matters**: icon-based maneuver mapping fails otherwise.
**Fallback**: text parsing only.
**Resolution**: TBD

### A17 — Cluster accepts our forged idle-widget a531 bytes
**Status**: OPEN
**Claim**: We can populate a531 with non-Mappls values (clock string in ETA field, temp in distance field, etc.) and the cluster will render them as text.
**Why it matters**: idle widget is dead without this.
**Existing evidence**: `forge_display.py` already demonstrates text acceptance (commit `5932d70`). Need to verify the specific positions we want for clock/weather.
**Resolution**: PARTIAL — text acceptance proven; specific layout needs a test.

### A18 — K20 Pro adb is usable throughout the night
**Status**: OPEN
**Claim**: Wi-Fi adb or USB adb stays connected for iteration.
**Why it matters**: install loop breaks.
**Fallback**: APK on phone via web download.
**Resolution**: TBD (operational, not a design risk)

### A19 — DataStore + Room are available on min SDK 29 with current Jetpack
**Status**: OPEN
**Claim**: Latest stable androidx Room and DataStore artifacts compile against min SDK 29.
**Why it matters**: dependency resolution.
**To verify**: androidx release notes.
**Resolution**: TBD (very likely true)

### A20 — Bike's notify-stream-starts-after-first-write rule still holds
**Status**: VERIFIED (Phase 1 M3 evidence)
**Claim**: Bike doesn't notify on 0xFFF2 until phone writes its first a536 identity. Subscribing alone yields nothing.
**Evidence**: `NOTES.md` line 485, multiple captures, `tools/passive_listen.py` vs `tools/provoke_and_listen.py` outcomes.
**Implication for code**: BleClient must subscribe **before** sending identity (to not miss any notify), but must send identity to actually receive anything.

---

## Additions during implementation

(Append below as new assumptions get made. Format: same as above. Promote to numbered entry on first verification check.)
