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
**Status**: REFUTED — but resolved with a new extraction path
**Original claim**: Maps notification exposes `EXTRA_TITLE` / `EXTRA_TEXT` / `EXTRA_SUB_TEXT` such that we can parse "In 220 m · Turn right · MG Road · 5 min".
**Actual behavior**: Google Maps uses a custom **RemoteViews** layout. `Notification.extras` carries `android.contains.customView=true` and `contentView=...` but NOT the parseable text strings. (Source: R1 research, primary evidence in B4X forum thread + 3v1n0/GMapsParser source.)
**New approach** (per R1 + decisions log):
1. `Notification.Builder.recoverBuilder(ctx, n).createBigContentView()` → returns a `RemoteViews` for the expanded notification
2. Inflate it with a `LayoutInflater` against the right `Context.createPackageContext(...)` — **note Android 12+ requires `"com.android.systemui"`, NOT `"com.google.android.apps.maps"`** (per unmerged GMapsParser PR #8)
3. Walk the inflated `ViewGroup`; for each `TextView`, match `child.resources.getResourceEntryName(child.id)` against the known entry-name set: `nav_title`, `nav_description`, `nav_time`, `lockscreen_directions`, `lockscreen_oneliner`, `lockscreen_eta`
4. For each `ImageView` matching `nav_notification_icon` / `right_icon` / `lockscreen_notification_icon`: extract `Bitmap` via `(drawable as BitmapDrawable).bitmap`
**Caveat**: GMapsParser is from ~2020 and has an unmerged Android 12 fix; the Jan 2026 maintainer question "still works?" is unanswered. **MUST empirically verify on the K20 Pro before relying on this path.**
**Resolution**: REFUTED + new path documented + empirical test queued (see Pre-Phase 2 task).

### A2 — Google Maps turn-icon resource IDs are stable enough to map
**Status**: REFUTED — pivot to bitmap classification
**Original claim**: `Notification.smallIcon.resId` is a stable lookup key for the turn type.
**Actual behavior**: R1 evidence: every reference implementation extracts the maneuver as a `Bitmap` from the inflated ImageView, not via `smallIcon.resId`. The `smallIcon` may be set, but its resId is internal to Maps' resource table and considered unstable across Maps' frequent app updates. Other consumers (ESP32 project) treat it as a bitmap blob and either render directly or classify.
**New approach** (per decisions log):
- We can't render bitmaps to the cluster; we have to classify to a Mappls maneuver id (single byte).
- New module: `nav/MapsBitmapClassifier.kt`. Build a seed table of bitmap perceptual-hash → maneuver id from empirically collected samples (logged when we encounter unknowns).
- Text fallback: parse `nav_description` for "Turn left/right", "U-turn", "Take exit", "Slight left", "Continue straight", etc. Maps these to maneuver IDs (English-only initially; deferred for other locales).
- Last-resort default: maneuver 8 (generic arrow).
**Resolution**: REFUTED + new path designed.

### A3 — Bike accepts WRITE_TYPE_DEFAULT (with-response) on 0xFFF1
**Status**: VERIFIED — both modes work
**Claim**: Our Kotlin `BleClient.write` will use `BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT` (writeWithResponse) and the bike will accept it.
**Why it matters**: wrong write type → silent drops.
**Evidence**:
- `tools/send_custom.py:140`, `tools/provoke_and_listen.py:93/106`, `tools/bike_full_diag.py:108/158/160` all use `response=True` (= WRITE_TYPE_DEFAULT) — these worked (provoked bike notifies, replayed captured frames).
- `tools/forge_display.py:201` (newest, commit 5932d70) uses `response=False` (= WRITE_TYPE_NO_RESPONSE) — also works for arbitrary cluster forging.
**Conclusion**: bike's 0xFFF1 characteristic has both `PROPERTY_WRITE` and `PROPERTY_WRITE_NO_RESPONSE` and accepts both. Recommended app strategy: use WRITE_TYPE_DEFAULT for identity (a536) and event-driven frames (a532/a534/a535) where delivery confirmation matters; use WRITE_TYPE_NO_RESPONSE for high-cadence a531 nav (where we send same content multiple times per second anyway). 1Hz a533 heartbeat: WRITE_TYPE_DEFAULT (cheap insurance).

### A4 — Open-Meteo WMO codes → Suzuki 0-11 codes
**Status**: VERIFIED (R2)
**Findings**: 12 Suzuki codes (0=unknown, 1=sunny, 2=cloudy, 3=fog, 4=light-rain, 5=thunder, 6=rain, 7=snow, 8=sleet/hail, 9=hot, 10=cold, 11=windy). All 12 verified by reading `C.r(String)` at `decompiled/jadx-out/sources/com/suzuki/application/fragment/C.java` lines 1568-1614.
**Mapping**: 26 WMO codes mapped to Suzuki codes. Default for unknown WMO → 1 (sunny). Suzuki codes 9/10/11 unreachable from WMO; derive from temperature + wind speed fields separately if we want them.
**Ambiguities flagged**: WMO 82 (violent showers), 96/99 (thunder + hail) — picked safer side, can revisit on cluster display.
**Resolution**: ready to drop into `weather/WeatherCodeMap.kt`.

### A5 — JDK 26 too new for Android Gradle Plugin
**Status**: VERIFIED (R3)
**Findings**: Latest stable is AGP 9.2.0 (not 8.x as I assumed). JDK 17 required. JDK 26 unsupported — AGP 9.2.0-alpha03 had a JdkImageTransform fix for JDK 26 indicating an unresolved compat gap. Install `jdk17-openjdk`.
**Source**: developer.android.com/build/releases/agp-9-2-0-release-notes

### A6 — Android cmdline tools alone are sufficient (no Android Studio)
**Status**: VERIFIED (R3)
**Findings**: Yes. Standalone cmdline-tools zip (`commandlinetools-linux-14742923_latest.zip`, 172.8 MB) is sufficient. Required packages: `platform-tools`, `platforms;android-36`, `build-tools;36.0.0`, `cmdline-tools;latest`. Plus `yes | sdkmanager --licenses`.
**Source**: developer.android.com/tools/sdkmanager

### A7 — LineageOS Android 16 == API 36, not 34
**Status**: VERIFIED (R3 + on-device 2026-05-24)
**Findings**: LineageOS 23 = Android 16 = API 36. K20 Pro adb confirms: `ro.build.version.sdk=36`, `ro.build.version.release=16`, `ro.product.model=Redmi K20 Pro`. Spec corrected: minSdk 29, targetSdk 35 (AGP 8.10.0 stable on this), compileSdk 35. Targeting 35 still runs on 36; bump later if we ship a 36-only API.
**Sources**: R3 web research + live `adb shell getprop`.

### A8 — BIND_NOTIFICATION_LISTENER_SERVICE works on LineageOS
**Status**: OPEN
**Claim**: LineageOS preserves stock AOSP notification listener behavior; no OEM-specific quirks.
**Why it matters**: nav parsing dead-on-arrival otherwise.
**To verify**: research; LineageOS is generally close to AOSP — likely fine.
**Resolution**: TBD

### A9 — READ_SMS is still grantable on Android 16 to non-default SMS apps
**Status**: REFUTED (R3) — switch to NotificationListener fallback
**Findings**: Google Play policy bars READ_SMS/RECEIVE_SMS for non-default SMS apps; the runtime APIs still exist on a sideload, but this is not a sustainable path. **Decision: drop READ_SMS/RECEIVE_SMS from manifest; mirror SMS via NotificationListener on the system Messages package** (`com.google.android.apps.messaging` on most ROMs; will detect on K20 Pro empirically). Bonus: NotificationListener also catches WhatsApp / Telegram / RCS chats with the same code path.
**Source**: support.google.com/googleplay/android-developer/answer/10208820

### A10 — TelephonyManager.callStateChanged fires for normal incoming calls on Android 16
**Status**: PARTIALLY DEFERRED — using NotificationListener fallback as primary
**Findings**: TelephonyCallback (replaces PhoneStateListener on API 31+) does fire for incoming calls but requires `READ_PHONE_STATE` (runtime). For consistency with the SMS approach (A9), I'm using NotificationListener on the system Phone app as the primary signal — same architecture, fewer permissions. If the phone app's notification doesn't give us caller number reliably, we'll add TelephonyCallback as the supplement.

### A11 — MediaSessionManager exposes Now Playing cross-app
**Status**: VERIFIED (R3)
**Findings**: Two paths: (a) `MEDIA_CONTENT_CONTROL` — signature-level, not grantable to us; (b) be an enabled NotificationListener, pass our listener's ComponentName to `getActiveSessions`. We already need the NotificationListener for nav/SMS/calls; reuse for Now Playing. Confirms architecture.

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
**Status**: VERIFIED (R3)
**Findings**: Yes. Manifest: `android:foregroundServiceType="connectedDevice"` + permission `FOREGROUND_SERVICE_CONNECTED_DEVICE`. Plus one of the connected-device runtime permissions (`BLUETOOTH_CONNECT` for us). Use `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)`.
**ADDITIONAL FINDING (high-impact)**: Android 12+ background-FGS-start restriction applies and **BLE is NOT on the exemption list**. We cannot start the service from a background broadcast (e.g. "bike came in range"). Allowed launch points: user tap in app UI, `BOOT_COMPLETED`, exact alarm, Companion Device Manager, or user disabling battery optimization.
**Design implication**: "bike key-on → app auto-connects" works because the FGS, once started, stays running and `BluetoothGatt(..., autoConnect=true)` handles peripheral reappearance. The user starts the service once via UI, optionally enables auto-start-on-boot, and it runs from there.

### A15 — BluetoothGatt.connectGatt(autoConnect=true) reconnect semantics fit our needs
**Status**: VERIFIED (R3)
**Findings**: Yes. `autoConnect=true` does no-timeout connection that the stack maintains in the background; survives peripheral disconnect + later reappearance — exactly the bike-key-on UX we want. Caveats: (a) MAC must be in the BT cache (if `getType()` returns `TYPE_UNKNOWN`, do a fresh scan first); (b) initial connect is slower than `autoConnect=false`; (c) OEM-stack reliability varies — keep a watchdog that re-issues `connectGatt` after N minutes of no STATE_CONNECTED.

### A16 — Maps `Notification.smallIcon` exposes a usable resId
**Status**: SUPERSEDED by A2 refutation
**Resolution**: No longer relevant. We don't use smallIcon at all; the maneuver bitmap comes from the inflated RemoteViews ImageView.

### A17 — Cluster accepts our forged idle-widget a531 bytes
**Status**: OPEN
**Claim**: We can populate a531 with non-Mappls values (clock string in ETA field, temp in distance field, etc.) and the cluster will render them as text.
**Why it matters**: idle widget is dead without this.
**Existing evidence**: `forge_display.py` already demonstrates text acceptance (commit `5932d70`). Need to verify the specific positions we want for clock/weather.
**Resolution**: PARTIAL — text acceptance proven; specific layout needs a test.

### A18 — K20 Pro adb is usable throughout the night
**Status**: VERIFIED (2026-05-24)
**Findings**: USB adb working: `5f9e4a44` (Redmi K20 Pro, raphaelin device codename). Visible to laptop. Can run `adb install`, `adb shell`, `adb logcat`, `adb pull` etc. without further setup.

### A19 — DataStore + Room are available on min SDK 29 with current Jetpack
**Status**: VERIFIED (R3)
**Findings**: Room 2.8.4 (minSdk 23, Nov 19 2025). DataStore-preferences 1.2.1 (Mar 11 2026). Both fine at minSdk 29.

### A20 — Bike's notify-stream-starts-after-first-write rule still holds
**Status**: VERIFIED (Phase 1 M3 evidence)
**Claim**: Bike doesn't notify on 0xFFF2 until phone writes its first a536 identity. Subscribing alone yields nothing.
**Evidence**: `NOTES.md` line 485, multiple captures, `tools/passive_listen.py` vs `tools/provoke_and_listen.py` outcomes.
**Implication for code**: BleClient must subscribe **before** sending identity (to not miss any notify), but must send identity to actually receive anything.

---

## Additions during implementation

(Append below as new assumptions get made. Format: same as above. Promote to numbered entry on first verification check.)
