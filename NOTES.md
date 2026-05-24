# Suzuki Connect Protocol Notes

> Living spec for the BLE protocol between the Suzuki Connect Android app and the Suzuki Gixxer SF 150 (2023). Grown milestone-by-milestone.

## Cloud API architecture (M1 — confirmed from decompiled source, re-audited 2026-05-24)

**Suzuki Connect's cloud API is minimal and Mappls-hosted:**

- **Base URL**: `https://projects.mapmyindia.com/`
- **HTTP client**: Retrofit (annotations stripped by ProGuard but interface signatures + callbacks intact)
- **Only TWO endpoints exist** in the entire app (both in `com.suzuki.interfaces.f` and `com.suzuki.interfaces.g`):
  - `GET /autolicverify/{BTID}/expiry/date/` — returns `ExpirationInfoResponse` (subscription expiry info)
  - `POST /autolicverify/...updatePlan` — returns `PlanUpdateResponse` (plan changes)
- **Auth**: OAuth-style. Gets `TokenResponse` with `access_token` + `expires_in`; stored in SharedPreferences `AppPrefs.ACCESS_TOKEN`. Sent as `authorization` header.

**There is NO cloud API for fuel/odometer/trip/last-location data.** The decompiled source has only the two license-management endpoints above. So **fuel/odo/trip data must come from BLE** (not cloud).

### Re-audited 2026-05-24 (broader sweep)

The "only 2 endpoints" claim has been **fully validated** by a more thorough audit:

- **All Suzuki-code HTTP base URLs** (grep'd as quoted string literals across the entire `com.suzuki.*` source tree):
  - `https://projects.mapmyindia.com/` — only API host. 2 endpoints (above).
  - `https://maps.mapmyindia.com/@<lat>,<lng>` — deep link to open parked location in browser/Mappls app. NOT an API call.
  - `https://www.suzukimotorcycle.co.in/...`, `https://mappls.com/...`, `https://play.google.com/...`, `https://drive.google.com/...` — Help / About / Play Store / user manual deep links. All browser handoffs, no API.

- **No WebSockets** (`ws://` / `wss://`) referenced in Suzuki code.

- **MQTT is DEAD CODE.** `org.eclipse.paho.android.service.MqttService` is registered in `AndroidManifest.xml` because the Paho library is bundled in the dependency tree, but no Suzuki code instantiates `MqttClient`, calls `MqttService.class`, or contains any broker URL (`tcp://`, `ssl://`, `mqtt://`, `mqtts://` — all return zero hits in `com.suzuki.*`). The Paho `BroadcastReceiver` we found in `androidx/appcompat/app/z.java` is just the library's own `NetworkConnectionIntentReceiver` waiting for an MQTT client that never gets created.

- **Mappls SDK** (`com.mappls.sdk.*`) talks to many internal Mappls servers (map tiles, routing, search, geocoding, directions) — those are part of the bundled navigation library and orthogonal to bike telemetry/control. Suzuki does not see those calls; the SDK handles them opaquely.

**Conclusion**: bike control / data flow has only one channel (BLE on 0xFFF1/0xFFF2). The cloud is used solely for license verification + plan updates. Nothing else flows through the network.

### Manifest-declared Suzuki receivers (catalogued 2026-05-24)

For completeness, every Suzuki-owned `<receiver>` / `<service>` in the manifest:

| Component | Type | Purpose |
|-----------|------|---------|
| `com.suzuki.services.MyBleService` | service (foreground) | BLE bridge — the central write path (`MyBleService.f()`) and notify subscription |
| `com.suzuki.services.NotificationService` | service (BIND_NOTIFICATION_LISTENER_SERVICE) | Reads Android notifications → builds a532/a534/a535 frames |
| `com.suzuki.broadcaster.IncomingSms` | receiver | Catches SMS → builds a535 frame |
| `com.suzuki.broadcaster.CallReceiverBroadcast` | receiver | Catches call events → builds a532/a534 frames |
| `com.suzuki.activity.NotificationReceiver` | receiver | Internal notification dismissal handler |
| `com.suzuki.utils.SuzukiRideWidgetReceiver` | receiver | Home-screen widget refresh |
| `com.suzuki.broadcaster.BleConnection` | receiver | **Stub** — only logs received broadcasts, no real handling |
| `com.suzuki.broadcaster.MapShortDistBroadcast` | receiver | **Stub** — only logs received broadcasts |
| `com.suzuki.broadcaster.DataFromBle` | receiver | **Stub** — only logs received broadcasts |

The last three are skeleton/debug receivers that don't do anything useful (single `Log.d` call each). No hidden side-channel for telemetry through them.

### Local storage

- **Suzuki Connect uses Realm Mobile Database** (`io.realm.*`) for local persistence.
- Realm model classes are in `com.suzuki.pojo.*` (28 files, A-Z + numbered). Field names are heavily obfuscated single letters (`a`, `b`, `c`, `a0`, `b0`, ...) — without runtime trace it's hard to map field-name → semantic meaning.
- App-state singleton at `com.suzuki.pojo.e` holds ~80 static fields for current session state (boolean flags + ints + strings).

### BLE callback architecture

- Suzuki uses **FastBle library** (`com.clj.fastble.*`).
- Suzuki's callback classes only extend two FastBle callback types:
  - `callback.a` (`BleGattCallback` — connection state) — `C0853p0`, `C0855q0`, `services/d`
  - `callback.b` (`BleWriteCallback` — write confirmation) — `services/a`, `services/b`
- **No class explicitly extends `BleNotifyCallback`** — the notify subscription is set up via lambda inside `MyBleService.a(BleDevice)`, which calls `FastBle.BleManager.notify(device, "0000fff0...", "0000fff2...", callback)` on the 4th service (`0xFFF0`) / 2nd characteristic (`0xFFF2`).

### Data flow (FULLY MAPPED 2026-05-23/24)

```
Bike  --[notify on 0xFFF2]-->  FastBle BleManager
                                    │
                                    ▼ (callback Bundle "notify_value" → Handler.what=19)
                              androidx.localbroadcastmanager.content.a   ← wraps in C0944c event
                                    │
                                    ▼ EventBus.post(C0944c)
                              6× onClusterDataRecev subscribers (HomeScreenActivity,
                              NavigationActivity, RouteActivity, RiderProfileActivity,
                              CreateProfileActivity, application.fragment.C)
                                    │
                                    ▼ all gate on bArr[1]==0x37 (a537 only)
                              parse → Realm DB + com.suzuki.pojo.e static fields → UI
```

Fuel/odo/trip/speed/fuel-economy all originate from the `0xFFF2` notify stream (a537 frames), parsed by the subscribers above, and stored into Realm + `com.suzuki.pojo.e.*` static state for the UI to read.

## App identity (M1)

- Package name: `suzuki.com.suzuki`
- APKs: base.apk (191 MB) + split_config.{arm64_v8a, en, xxhdpi}.apk
- base.apk SHA256: `c7e3466beea3d9512c5b6f79a79377389b1bbc57dfc6986e43efa727e729f5cf`
- Pulled on: 2026-05-23
- Decompiled: 6751 Java files in `decompiled/jadx-out/sources/` via JADX
- **BLE library**: `com.clj.fastble` (FastBle, a popular Android BLE wrapper)
- Versions (versionName/versionCode): TBD (need apktool or aapt2 — neither installed yet; can extract from JADX-decompiled AndroidManifest later)

### Suzuki-namespace BLE code map

| Class | Responsibility |
|-------|----------------|
| `com.suzuki.services.MyBleService` | Connection orchestrator (Service) |
| `com.suzuki.services.work` | BLE write helper (`work.g(byte[])` → FastBle write) |
| `com.suzuki.services.f` | TimerTask, 1s heartbeat — calls `work.g` 3× per tick. Produces a heartbeat variant we have NOT seen in captures (different template than the a533 we captured) |
| `com.suzuki.services.NotificationService` | Pushes Android notifications to bike cluster (separate message type) |
| `com.suzuki.broadcaster.CallReceiverBroadcast` | Pushes call events |
| `com.suzuki.broadcaster.IncomingSms` | Pushes SMS events |
| `com.suzuki.application.fragment.A0` | Navigation / display refresh — constructs the `a531` messages we captured (line ~483) |
| `com.suzuki.application.fragment.B` | `PhoneStateListener` for cellular signal — reads RSRP, sets `c.I = "0"/"1"/"2"/"3"` for signal-strength digit |
| `com.suzuki.application.fragment.C` | Main UI fragment, holds BLE service+characteristic refs (`C.d1.f.getCharacteristics().get(0)`) |
| `com.suzuki.application.SuzukiApplication` | Holds the checksum function `static byte a(byte[])` |
| `com.suzuki.activity.HomeScreenActivity` | Holds `static byte i0` (SMS-present indicator) + `static byte j0` (Call-present indicator) |

### `a531` navigation message construction (confirmed from `A0.D()` source)

The full send function in `A0.java`:

```java
public final void D(int i, int i2, String str, String str2) {
    // i      = Mappls maneuver ID (turn arrow code)
    // i2     = category (1=normal, 3=alert, 10=clear)
    // str    = signal-status digit (the H1 field, controls "Searching for network")
    // str2   = secondary status (I1)

    String str3 = "?110" + this.p0 + this.m0 + this.n0 + "000"
                + this.q0 + this.o0 + str + str2 + "00000";

    // Override status conditions:
    if (airplane_mode) str = "0";
    if (!gps_enabled)  str = "4";

    // Maneuver ID overwrite:
    if (str.equals("1") || str.equals("3") || str.equals("5")) {
        // GOOD signal → preserve real maneuver ID (from saved n1)
        if (this.n1 != 0) { i = this.n1; this.n1 = 0; }
    } else {
        // BAD signal ("0","2","4","6") → force maneuver to '.' (46)
        if (this.n1 == 0) this.n1 = i;
        i = 46;
    }

    bytes = str3.getBytes("UTF-8");
    bytes[0] = -91;                        // 0xA5 header
    bytes[2] = (byte) i;                   // MANEUVER ID — '.' (0x2e) when bad signal
    bytes[3] = -1;                         // 0xFF
    // Special model patches:
    if (bike is "e-ACCESS" / "Access-TFT" / "Burgman Street-TFT"
        && this.n0.charAt(0) == '0') {
        bytes[9] = 32;                     // ASCII space override
    }
    bytes[15] = -1; bytes[16] = -1; bytes[17] = -1;
    for (int i4 = 25; i4 <= 27; i4++) bytes[i4] = -1;
    bytes[28] = SuzukiApplication.a(bytes);  // checksum
    bytes[29] = 127;                          // 0x7F terminator
}
```

### Decoded field positions in `a531` (FULLY CONFIRMED from source 2026-05-24)

Template `"?110" + p0 + m0 + n0 + "000" + q0 + o0 + str + str2 + "00000"` from `A0.D()`. All instance-field semantics now traced via `A0.C(com.mappls.sdk.navigation.model.a aVar)` (re-decompiled with `jadx --show-bad-code --comments-level debug --single-class`):

| Byte position(s) | Field | Content |
|---|---|---|
| 0 | header | `0xA5` (constant) |
| 1 | type | `0x31` `'1'` (constant for a531) |
| 2 | maneuver ID | The Mappls maneuver int — or `0x2e` (`'.'`=46) if signal degraded (see "degraded mode" below) |
| 3 | pad | `0xFF` (constant — forced override) |
| 4-7 | `p0` | **Distance to next maneuver** (4-char ASCII number, leading zeros). From `aVar.c` rounded to nearest 10. Examples: `"0080"` (80 of m0-units), `"0110"`. Sub-10 km values include a decimal point (e.g. `"05.6"`). |
| 8 | `m0` | **Unit for p0**: `"K"` (km) if Mappls returned "km", `"M"` (meters) otherwise. |
| 9-14 | `n0` | **ETA** (6-char ASCII). 24h locale: `"001730"` (HHMM, zero-padded to 6). 12h locale: `"0530PM"` (HHMMAA). Source: `aVar.e`. NOT current time — was misread in 2026-05-23 notes. For e-ACCESS / Access-TFT / Burgman bikes only: if `n0[0]=='0'`, position 9 gets overwritten to `0x20` (space). |
| 15-17 | pad | `0xFF` (constant — code forces `bytes[15..17] = -1`) |
| 18-21 | `q0` | **Total distance-to-go (DTG)** (4-char ASCII number). From `aVar.d`. Same format rules as `p0`. Examples: `"0348"`, `"05.6"`. |
| 22 | `o0` | **Unit for q0**: `"K"` or `"M"`. NOTE: source check at line 696 reads `if (!strB.contains("km")) o0="K"; else if (strB.contains("m")) o0="M"` — looks like a copy-paste bug vs `m0`'s correct check. May produce inverted/swapped units. Verify against live ride capture. |
| **23** | **`str` (H1)** | **Nav status digit**. `'1'`=normal (good signal), `'0'`=exit/airplane-mode, `'2'`=X-flag (recalc?), `'3'`=b0-flag, `'4'`=GPS-lost, `'5'`=a0-flag, `'6'`=v0-flag. Drives the "Searching for network" UX on the cluster when not `'1'`/`'3'`/`'5'`. |
| 24 | `str2` (I1) | Secondary status / continuation flag. `'0'` = terminate navigation (cluster will exit nav view). Other values keep nav active. |
| 25-27 | pad | `0xFF` (constant — code forces `bytes[25..27] = -1`) |
| 28 | checksum | `SuzukiApplication.a(bytes)` = `sum(bytes[1:28]) mod 256` |
| 29 | terminator | `0x7F` (constant) |

When `this.a0 == true` (some "no active maneuver" flag — semantic TBD), the code resets `p0` and `q0` to `"0000"` before building the frame. So all-zeros distances = nav idle / no current step.

### "Degraded mode" override (from `A0.D()` source)

When the signal-status string `str` (H1) is in `{"0", "2", "4", "6"}` (degraded states), the code:

```java
if (this.n1 == 0) this.n1 = i;   // SAVE the real maneuver ID for later
i = 46;                           // OVERWRITE with '.'
```

When `str ∈ {"1", "3", "5"}` (good signal states), the saved `n1` value is RESTORED:

```java
int i3 = this.n1;
if (i3 != 0) { this.n1 = 0; i = i3; }   // RESTORE the real maneuver ID
```

So `bytes[2]` carries either:
- The real maneuver ID (when signal good)
- `0x2e` `'.'` placeholder (when signal degraded — bike will not show real arrow)

This was confirmed by our two captures:
- NO-SIM (WiFi only): bytes[23] = `'0'` (signal degraded) → bytes[2] = `0x2e` (placeholder)
- WITH-SIM (cellular): bytes[23] = `'1'` (signal good) → bytes[2] = `0x08` (a real maneuver ID — icon 8 per C0897z.java's table)

### Forging strategy to defeat "Searching for network"

Set `bytes[23] = 0x31` (`'1'`) in our forged a531 messages, and set `bytes[2]` to a real maneuver ID from the C0897z table. Recompute `bytes[28]` with the sum algorithm. Bike should accept and render real arrow content.

`forge_network.py` (existing) targets the OLD wrong hypothesis (a533 pos 21/22) and will NOT work. Needs replacement with a tool that forges a531 pos 23 instead.

### `a531` template field map (RESOLVED 2026-05-24)

All five instance fields are now semantically mapped — see the table above. Source: `A0.C()` after re-decompilation with `--show-bad-code`. Summary:

- `p0` (4 chars, pos 4-7) = distance to next maneuver (`aVar.c`)
- `m0` (1 char, pos 8)    = unit for `p0` (`K`/`M`)
- `n0` (6 chars, pos 9-14) = ETA (`aVar.e`, padded to 6)
- `q0` (4 chars, pos 18-21) = total distance-to-go (`aVar.d`)
- `o0` (1 char, pos 22)   = unit for `q0` (`K`/`M`, with possible swap bug)

### Maneuver-ID → arrow-icon mapping

**Bike's icon set has 55 icons** (extracted from APK resources `res/drawable-nodpi-v4/ic_step_<N>.xml`):

```
Available icon IDs: 0, 1, 2, 3, 4, 5, 6, 7, 8,
                    10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25,
                    36, 37,
                    40, 41,
                    50, 51, 52, 53, 54, 55, 56, 57, 58, 59,
                    60, 61, 62, 63, 64, 65, 66, 67, 68, 69,
                    70, 71, 72, 73, 74, 75
```

Gaps: no icon at 9, none from 26-35, none from 38-39, none from 42-49. **Sending `bytes[2] = N` where N is a gap value may render nothing or fallback** — to be tested.

### App-side remap table (from `C0897z.java`)

The app's UI adapter remaps some Mappls maneuver IDs into the available icon space:

| Mappls maneuver ID | Icon shown by app | Inferred meaning |
|--------------------|-------------------|------------------|
| 9, 10 | icon 8 | Generic / fallback arrow (no straight maneuver = icon 8?) |
| 26, 27, 28 | icon 15 | One type of turn (likely slight-left variants) |
| 29, 30, 31 | icon 16 | Another type (likely slight-right variants) |
| 65 | icon 67 | (specific Mappls type) |
| 72 + bike-state `e.b0=20` | icon 65 | Roundabout exit 1 |
| 72 + `e.b0=21` | icon 66 | Roundabout exit 2 |
| 72 + `e.b0=22` | icon 67 | Roundabout exit 3 |
| 72 + `e.b0=23` | icon 68 | Roundabout exit 4 |
| 72 + `e.b0=24` | icon 69 | Roundabout exit 5 |
| 72 + `e.b0=25` | icon 70 | Roundabout exit 6 |
| 72 + `e.b0=26` | icon 71 | Roundabout exit 7 |
| 72 (default) | icon 71 | Roundabout (generic) |
| any other N | icon N | Direct passthrough |

### What gets sent to the BIKE in `bytes[2]`

From `A0.D()` source (line ~509): `bytes[2] = (byte) i;` where `i` is the maneuver parameter passed in. The caller (`v0.java`) passes `a0.e0` or `a0.f0`, both of which hold Mappls maneuver IDs directly (not the app's remapped icon IDs).

**Therefore the bike receives RAW Mappls maneuver IDs**, and the bike's firmware presumably has its own icon table matching the app's `ic_step_<N>` set. Sending `bytes[2] = 8` → bike shows `ic_step_8`, `bytes[2] = 67` → `ic_step_67`, etc.

### Confirmed real maneuver values from captures

| Capture | `bytes[2]` value | Likely icon | Notes |
|---------|------------------|-------------|-------|
| NO-SIM (no signal, degraded mode) | `0x2e` (46) | placeholder `'.'` not a real icon — the code forces this when signal is bad |
| WITH-SIM (signal good, nav active) | `0x08` (8) | `ic_step_8` | Real maneuver from a live nav session |

### To verify visually (next session)

To see what each icon looks like, install `apktool` (`yay -S apktool`) and run:

```bash
apktool d -f -o decompiled/apktool-out apk/base.apk
# Then view: decompiled/apktool-out/res/drawable-nodpi-v4/ic_step_<N>.xml
```

This will give a readable XML of each vector drawable. Or extract them and render via Android Studio's preview, or convert XML→SVG with a tool.

### Practical forging — known-safe icon IDs

For testing arrow display via `forge_signal_v2.py` or successors, these are SAFE icon IDs to send (all exist in the APK):

`0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 36, 37, 40, 41, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75`

Avoid: `9, 26-35, 38-39, 42-49` — these may render as blank / unknown / fallback on the cluster.

### Checksum function (confirmed from source)

```java
// SuzukiApplication.a(byte[])
public static byte a(byte[] bArr) {
    byte b = 0;
    for (byte b2 = 1; b2 <= 27; b2++) {
        b = (byte) (b + bArr[b2]);
    }
    return K.g
        ? (byte) (255 - (b % 256))    // INVERTED variant — gated by K.g flag
        : (byte) (b % 256);            // STANDARD variant — what we observe
}
```

Algorithm matches our hypothesis: `sum(payload[1:28]) mod 256`. Additionally there's an **inverted variant** triggered by a `K.g` boolean flag that produces `255 - (sum % 256)` instead. Not observed in our captures yet.

## Bike identity

- Model: Suzuki Gixxer SF 150, 2023
- BLE MAC: see `LOCAL_NOTES.md` (PII)
- BLE local name (advertised): see `LOCAL_NOTES.md` (PII, looks serial-derived)
- BLE chip OUI: `74:b8:39` → Texas Instruments (common automotive BLE SoC vendor)

## Tooling notes

- Spare phone: Redmi K20 Pro, LineageOS Android 16, arm64-v8a
- frida-server version: 17.9.10 (matched to laptop frida-tools)
- KSU peculiarity on this build: `su` binary is NOT on PATH for `adb shell` (UID 2000), so `adb shell su -c ...` fails with "su: inaccessible or not found". However `su` works from Termux (which uses the KSU kernel API via its own bundled wrapper). This affects how we pull root-owned files.

### HCI snoop log

- Path: `/data/misc/bluetooth/logs/btsnoop_hci.log`
- Root-only readable
- Enabled via: Settings → System → Developer options → "Enable Bluetooth HCI snoop log" → ON, then toggle Bluetooth off/on to start a fresh log
- **Pull workflow** (until/unless we fix `adb shell su`):
  1. From Termux: `su -c "cp /data/misc/bluetooth/logs/btsnoop_hci.log /sdcard/btsnoop_hci.log && chmod 644 /sdcard/btsnoop_hci.log"`
  2. From laptop: `adb pull /sdcard/btsnoop_hci.log captures/<descriptive-name>.pcap`

### Starting frida-server

- From Termux: `su -c "/data/local/tmp/frida-server &"` — then leave Termux open or detach
- From laptop, verify: `frida-ps -U | head -5`

## BLE GATT tree (M1)

Captured via direct GATT walk from laptop on M0 with bike key ON, Suzuki Connect closed. See `captures/gatt-walk-*.txt` for raw output.

### Services

| UUID | Name | App-used? | Notes |
|------|------|-----------|-------|
| `00001800-...` | Generic Access Profile (GAP) | partial (standard) | standard discovery |
| `00001801-...` | Generic Attribute Profile (GATT) | yes (standard) | no characteristics exposed |
| `0000180a-...` | Device Information | partial | metadata strings |
| `0000fff0-...` | Vendor specific (Suzuki) | yes | **the actual Suzuki protocol surface** |

### Characteristics

| Service | Char UUID | Properties | App-used? | Sample read | Inferred purpose |
|---------|-----------|------------|-----------|-------------|------------------|
| 0x1800 | `2a00` Device Name | write,write-without-response | no | (write-only) | rename device — write-only is unusual but bike accepts new name |
| 0x1800 | `2a01` Appearance | write,write-without-response | no | (write-only) | also write-only — bike lets app set its advertised appearance class |
| 0x1800 | `2a04` Preferred Connection Params | read | no | `5000a0000000e803` | conn interval min/max, latency, supervision timeout |
| 0x180a | `2a23` System ID | read | no | `f1da54000039b874` | MAC in BE encoding |
| 0x180a | `2a24` Model Number | read | no | `5846` = `"XF"` | Suzuki internal model code |
| 0x180a | `2a25` Serial Number | read | no | PII — see LOCAL_NOTES | matches advertised name |
| 0x180a | `2a26` Firmware Revision | read | no | `"From Host Controller"` | literal string, not a version |
| 0x180a | `2a27` Hardware Revision | read | no | `"From Host Controller"` | same |
| 0x180a | `2a28` Software Revision | read | no | `"0.1.5.8"` | actual version |
| 0x180a | `2a29` Manufacturer Name | read | no | `"Suzuki"` | |
| 0x180a | `2a2a` IEEE 11073-20601 Cert | read | no | `\xfe\x00"experimental"` | **odd**: bike firmware is flagged "experimental" |
| 0x180a | `2a50` PnP ID | read | no | `010d0000001001` | VID source=BT SIG, VID=0x000d, PID=0x0000, ver=0x0110 |
| **0xfff0** | **`fff1`** | **write** | **YES** | (write-only) | **phone → bike: nav commands, app actions** |
| **0xfff0** | **`fff2`** | **notify** | **YES** | (notify-only) | **bike → phone: status, telemetry (multiplexed)** |

### Key observations

- **The Suzuki protocol surface is exactly one Service (0xFFF0) with one write char (0xFFF1) and one notify char (0xFFF2).** Everything navigation, all status, all auth, and (if exposed) any telemetry/diagnostic data must go through this single channel pair.
- Wireshark's earlier "PKOC/Aliro/ICCE Digital Key" labels for these handles were a dissector misfire — the actual UUIDs are simple `0xFFF1/0xFFF2` vendor UUIDs, not the PKOC namespace. **There is no Aliro/ICCE digital-key protocol in play**, just a custom Suzuki protocol over a vendor service.
- The bike's firmware identifies as version `"0.1.5.8"` with an "experimental" cert string — bike is likely running a pre-prod or early-release firmware build.
- `2a23 System ID` confirms the bike's BLE MAC (in big-endian, with manufacturer ID stub bytes inserted).

### Implications for the plan

- **M4 (encryption layer)**: still TBD. With only one write+notify pair, encryption (if any) wraps the entire message payload — there's no separate "auth channel" we missed. M3 Frida hook on `BluetoothGattCharacteristic.setValue` plus M2 wire capture cross-reference will reveal whether 0xFFF1 writes are encrypted.
- **M5 (nav protocol decode)**: target characteristic for nav is `0xFFF1`. All writes we see on it during a nav session ARE nav messages (no need to filter handles — there's only one write char).
- **M7 (telemetry surface) / Phase 3 Branch B**: the bike does NOT expose separate "telemetry" characteristics — there are no `unused` notify chars to subscribe to. Any extra telemetry must be **multiplexed within the single 0xFFF2 notify stream**. M7 therefore becomes "what message types appear on 0xFFF2 that the app doesn't visibly act on?" rather than "subscribe to many unused chars." This makes Phase 3 Branch B (telemetry dashboard) substantially more constrained — only what Suzuki chose to push over notify is visible.
- **Gate 5 (full BLE surface enumerated)**: M1.6 deliverable already met by this walk. Will be expanded with `app-used` analysis once M1 APK decompile is done.
- **Gate 2 (send valid third-party message)**: no PKOC, so no public-key handshake to defeat. The auth layer (if any) is whatever Suzuki coded above `setValue()` — should be discoverable in M4.

## Message types and structure (M0 preliminary, M5 will refine)

All messages on `0xFFF1` (write) and `0xFFF2` (notify) share a common frame:

```
+------+------+-----------------------+------------+----------+
| 0xA5 | type | ASCII payload + 0xFF | 1-2B csum | 0x7F    |
+------+------+-----------------------+------------+----------+
                                                   end-of-msg
```

- All payloads are **exactly 30 bytes** (the protocol pads to fixed frame size with `0xFF`)
- Header byte: `0xA5`
- Type byte: an ASCII digit (`'1'` to `'7'`) — i.e., type bytes are `0x31` through `0x37`
- Payload: ASCII text fields, often separated by `0xFF` or null bytes
- Trailing byte: `0x7F` (end-of-message marker)
- Byte before `0x7F`: XOR checksum (`sum(bytes[1:28]) mod 256`)

### Complete message type inventory (7 types — confirmed from source 2026-05-24)

**From phone to bike** (writes on `0xFFF1`):

| Type byte | Frame | Sender(s) in source | Purpose | When sent |
|-----------|-------|---------------------|---------|-----------|
| `0x31` ('1') | a531 | `application/fragment/A0.D()` | Nav frame (turn arrow + dist + ETA + status) — fully decoded | Continuously during active navigation, ~2.7/sec |
| `0x32` ('2') | a532 | `broadcaster/CallReceiverBroadcast.d()`, `services/NotificationService:786` (WhatsApp call) | Incoming-call notification — caller's phone number at bytes 2-21 + state at byte 23 | When phone has incoming call |
| `0x33` ('3') | a533 | `services/f.java` (1Hz × 3), `application/fragment/C0940y.java` (every ~5s, two variants by `K.g`) | Phone heartbeat — SMS/call pending flags at bytes 14-15 | Continuous after pairing |
| `0x34` ('4') | a534 | `broadcaster/CallReceiverBroadcast.e()`, `services/NotificationService:729` | Missed-call notification — caller name + missed count at byte 3 | When missed-call event fires |
| `0x35` ('5') | a535 | `broadcaster/IncomingSms`, `services/NotificationService:412` | SMS / WhatsApp notification — sender name + app marker (W=WhatsApp, N=other) | When SMS/notification arrives |
| `0x36` ('6') | a536 | `application/fragment/A0.E()`, `application/fragment/C0940y.java` (first run) | User identity — display name (up to 20 chars at bytes 2-21) + `'F'`/`'R'` flag at byte 27 (`'F'`=new cluster, `'R'`=reconnect) | On connection / pairing |

**From bike to phone** (notifies on `0xFFF2`):

| Type byte | Frame | Purpose |
|-----------|-------|---------|
| `0x37` ('7') | a537 | Bike telemetry (speed, odo, Trip A, Trip B, fuel bars, fuel economy) — fully decoded |

**Important corrections to prior table:**
- Earlier NOTES.md only listed 3 TX types (a531, a533, a536). Missing: a532, a534, a535 (event-driven; absent in M0 because Arjun's phone was quiet during the 18-min capture).
- The a531 sample `.1..0080M0517PM...05.6K01...L.` was earlier described as "current time + distance"; the `0517PM` field is actually the ETA, not current time (see corrected a531 decode above).
- The a533 sample `.33Y214.050154NN...` is now **100% byte-decoded** (DISCOVERIES.md 2026-05-24). Bytes 2-3 = `c.G` = phone battery status: char 1 = battery bucket (`'0'`/`'1'`/`'2'`/`'3'` for 0-24% / 25-49% / 50-74% / 75-100%), char 2 = charging state (`'Y'` charging, `'N'` not). Our captured `"3Y"` = phone at 75-100% charged AND on USB power. Set by a BroadcastReceiver that ProGuard relocated to `androidx/appcompat/app/z.java` — outside the Suzuki package, which is why every grep within `com/suzuki/` missed it. Tool `tools/find_field_writes.py` (androguard) located it at the dex-bytecode level.
- The a536 sample `.6ARJUN...` is consistent with the source template (`"?6" + name + ...`), with `ARJUN` as the 22-char NUL-padded name (5 chars + NUL padding).

**From bike to phone** (notifies on `0xFFF2`):

| Type byte | Count | Rate | Decoded sample | Purpose (hypothesis) |
|-----------|-------|------|----------------|---------------------|
| `0x37` ('7') | 163 | every ~5 sec | `.7<speed><odo><tripA><tripB><fuel><FE>.` | **Bike → phone telemetry frame**. Carries speed, odometer, Trip A, Trip B, fuel bars, fuel economy. See "Confirmed a537 notify frame layout" below for the full decode. RETRACTED: earlier hypothesis that byte 25 was engine coolant temperature is wrong — byte 25 is the high byte of a 24-bit fuel-economy fixed-point value (see DISCOVERIES.md 2026-05-24). |

### Confirmed `a537` notify frame layout (DECODED FROM SOURCE 2026-05-23)

**Source**: `onClusterDataRecev(C0944c)` methods in `HomeScreenActivity`, `NavigationActivity`, `C.java` (fragment), `RouteActivity`, `RiderProfileActivity`, `CreateProfileActivity`. As of 2026-05-24 all six are decompiled (HomeScreenActivity required `jadx --show-bad-code --comments-level debug --single-class`). All gate on `bArr[1] == 0x37` — no other RX message types exist in the app.

```
pos: 0    1     2-4       5-10        11-16       17-22       23  24       25-27      28      29
     0xA5 0x37  SPEED     ODOMETER    TRIP_A      TRIP_B      ?   FUEL_LVL FUEL_ECON  csum    0x7F
                (km/h)    (km)        (km .1)     (km .1)     (sep) (bars)  (km/L 3B)
```

| Bytes | Field | Decode |
|-------|-------|--------|
| 0 | header | `0xA5` constant |
| 1 | type | `0x37` (`'7'`) constant |
| **2-4** | **Current speed (km/h)** | 3 ASCII digits; `int speed = Integer.parseInt(str.substring(2, 5))` |
| **5-10** | **Odometer (km, lifetime)** | 6 ASCII digits zero-padded; strip leading zeros; `String odo = str.substring(5, 11).replaceAll("^0+(?!$)", "")` |
| **11-16** | **Trip A (km with decimal)** | 6 ASCII digits; format as `XXXXX.X km`; `Float.parseFloat(substr(0,5) + "." + substr(5))` |
| **17-22** | **Trip B (km with decimal)** | Same encoding as Trip A |
| 23 | (separator) | Always observed as `'N'` (0x4E); not used by parser |
| **24** | **Fuel level (1-6 bars)** | Byte values: `'1'`=1 bar, `'2'`=2, ..., `'6'`=6 bars, else=0. Also computed as `Byte.toUnsignedInt(b) - 64` for an alternative scaled value `this.K`. |
| **25-27** | **Fuel economy / consumption** | 24-bit bitfield across 3 bytes. Formula varies by bike model: <br>• **Default (Gixxer SF 150)**: `(bits[0:13] + bits[13:24]/2048) / 10` → km/L <br>• **Access-TFT / Burgman Street-TFT**: `Integer.parseInt(bits[0:24], 2) / 10.0 / 2048` <br>• **Energy Consumption (EV models)**: `Integer.parseInt(bits[0:24], 2) / 10000` |
| 28 | checksum | `sum(payload[1:28]) mod 256` (confirmed) |
| 29 | terminator | `0x7F` constant |

### Decoded values from M0 capture

Captured (engine OFF, idle): `a5 37 30 30 30 30 31 36 37 32 39 30 34 39 31 31 30 30 30 39 38 34 39 4e 34 4d ff ff 3a 7f`

- **Speed**: `substring(2,5)` = `"000"` → 0 km/h ✓ (engine was off)
- **Odometer**: `substring(5,11)` = `"001672"` → 1672 km
- **Trip A**: `substring(11,17)` = `"904911"` → 90491.1 km (this value is suspiciously large — possibly a different encoding for the SF 150 specifically; needs confirmation)
- **Trip B**: `substring(17,23)` = `"000984"` → 98.4 km (or 9.84 km)
- **Fuel level**: byte[24] = `0x34` (`'4'`) → **4 bars out of 6**
- **Fuel economy**: bytes[25-27] = `4D FF FF` → 24-bit `01001101 11111111 11111111` → decoded as ~249.6 km/L (clearly invalid — sentinel value for "engine off / no data")

The Trip A "90491.1 km" value is odd — but our parsers were derived from `NavigationActivity` and other contexts. There may be SF-150-specific overrides we haven't found. To verify, capture with engine running and a known trip distance, compare to cluster display.

### Confirmed: notify is response-driven, not autonomous

The bike does NOT push notifies on its own. It begins streaming `a537` heartbeats only AFTER the Central writes the first `a536` (identity) message. A passive listener that just subscribes and waits will receive nothing. This was tested in the 2026-05-23 experiment: 30+ seconds of pure subscribe-and-wait produced zero notifications; once we sent the captured identity write, notifications started immediately at the 5-sec heartbeat cadence.

### RESOLVED 2026-05-24: bike → phone is a537-ONLY

After re-decompiling `HomeScreenActivity.onClusterDataRecev` with the JADX show-bad-code trick, all 6 EventBus subscribers of the cluster-data event were grep-confirmed to gate on `bArr[1] == 0x37`:

```
HomeScreenActivity:269, NavigationActivity:190, RouteActivity:284,
CreateProfileActivity:396, RiderProfileActivity:147, com/suzuki/application/fragment/C:817
   all read:  if (bArr[0]==-91 && bArr[1]==55 && bArr[29]==127)
```

Zero RX handlers for `a531` / `a533` / `a536`. The Suzuki app does not know how to receive any framing other than `a537`. So either:
- The bike physically only sends a537 (most likely — matches our captures), OR
- The bike sends other framings that Suzuki's own app silently discards (would be useless for us anyway since the official client doesn't decode them)

Either way, **for Phase 2/3 client work, treat bike→phone as a537-only**. Open question moves to TX side (decode `a533` heartbeat content, decode `a536` identity payload).

### Byte 24 has dual semantic by bike model

Per `HomeScreenActivity.onClusterDataRecev` (lines 279-350):
- **Petrol bikes (incl. Gixxer SF 150)**: byte 24 is ASCII `'1'`-`'6'` → fuel bars. Anything else (including `'0'`) → V=0 → triggers "Low Fuel" TTS alert.
- **e-ACCESS (EV)**: byte 24 is a raw integer; the app computes `unsignedByte - 64` and treats `< 16` as "Low Battery" → triggers TTS alert.

So the same byte position carries different encodings depending on which Suzuki bike is paired.

### Byte 25-27 (fuel/energy consumption) has 3 model-specific decodings

| Bike model | Formula on 24-bit big-endian value | Unit |
|------------|------------------------------------|------|
| Access-TFT Edition / Burgman Street-TFT Edition | `int24 / 10.0 / 2048.0` | proprietary |
| e-ACCESS (when `okhttp3...d.G().contains("Energy Consumption")`) | `int24 / 10000.0` | kWh/km (likely) |
| **Default (incl. Gixxer SF 150) — petrol** | `(top13bits + bottom11bits / 2048.0) / 10.0` | km/L |

Engine-off sentinel = `0xFFFFFF`. The code does NOT guard against this — decodes to ~819 km/L garbage. Treat any `bytes[25..27] == 0xFFFFFF` as "no data."

**This kills the old M0+ hypothesis that byte 25 alone encoded engine coolant temperature.** Byte 25 is just the high byte of a 24-bit fixed-point number; the X→W→V→U variation we observed during the engine-off capture was noise within the 0xFF sentinel region.

### Confirmed: checksum algorithm

**`checksum_byte = sum(payload[1:28]) mod 256`**

That is, sum the bytes from position 1 (type byte) through position 27 (last body byte before checksum), then mod 256. Verified across multiple message samples including content variations. This is the same algorithm for both writes and notifies.

### Key implications

- **No encryption**: payloads are plaintext ASCII. M4 likely resolves to "no encryption layer."
- **Bike cluster is phone-driven**: the bike displays whatever ASCII the phone sends in `a531` messages. The cluster's "smart" display behavior (time, distance, turn arrows) is entirely a function of phone-side rendering pushed over BLE. **Phase 3 Branch A (custom display) is wide open** — we can probably display arbitrary text in the cluster's text regions.
- **Bike does not push live telemetry over THIS BLE protocol** — the notify stream is pure heartbeat in our 18-min capture. See `## Where do the app's fuel/odo/trip values come from?` below for the corrected understanding.

### Open puzzles

1. **Turn-arrow / nav-instruction encoding**: We expected to see explicit turn-type bytes (TURN_LEFT etc.). We don't — but the bike DOES show turn instructions when we navigate. Hypothesis: turn type + distance is embedded in `a531` payload fields we haven't decoded yet (one of the byte regions we currently read as "distance" might be a compound `<direction><distance>`). To be resolved in M5 with targeted captures.

2. **Variable checksum on identical visible content**: Of the 36 `a536` ("ARJUN") messages, all have identical visible payloads, BUT two distinct trailing checksums (`46 f7 7f` and `52 03 7f`) — split by capture timeline (early vs late). Pure-payload CRC would give a single answer. This means the checksum involves **hidden state** — most likely a session counter or per-session HMAC key established at pairing. **This is the hardest problem for Gate 2**: we cannot construct valid messages unless we can reproduce the checksum, which requires understanding the hidden state. Resolved in M4.

## Where do the app's fuel/odo/trip values come from? (OPEN — verification in progress)

**What is observed (facts)**:
- Suzuki Connect app surfaces: fuel level, odometer, trip A/B, last bike location, etc.
- These values display even when the bike is OFF and only the phone is on.
- In the M0 18-min BLE capture (5 reconnections, 7300 ATT packets, exhaustive per-byte analysis):
  - Zero `Read Request` operations (opcode 0x0a) by phone on any characteristic.
  - Bike's 163 notifies are byte-identical except for a sequence byte and checksum — no telemetry encoded in any other position.
  - Phone's writes consist of: display refresh (`a531`), heartbeat (`a533`), user identity (`a536`). No telemetry-shaped payloads echoed.
- The Gixxer SF 150 has **no embedded SIM** (Arjun's physical inspection; consistent with Suzuki's product page describing connectivity as "Bluetooth-enabled digital console" only — no mention of cellular or telematics module).

**What is NOT known (open)**:
- Whether the bike pushes telemetry over BLE during lifecycle events we did not trigger (engine start/stop, ignition cycle while paired, trip end button, manual sync gesture, app foreground/background transitions).
- Whether the values shown in the app are stale (set at a prior moment, never updated).
- Whether the values come from Suzuki's cloud (populated by the user's main phone uploading over its own internet, against the same Suzuki account the spare phone is logged into).
- The exact meaning of "last sync time" displayed in the app.

**Three remaining hypotheses (unverified)**:

- **(P1) Cloud-cached from main-phone sessions.** Spare phone logged into Arjun's existing Suzuki account → cloud serves cached values originally uploaded by the main phone during its sessions with the bike. Plausible. To verify: log out / fresh install / check whether values disappear; check "last sync time" recency.
- **(P2) BLE pushes telemetry during specific events we did not capture.** The 18-min M0 session did not cleanly trigger many lifecycle events. To verify: laptop-as-Central listener subscribed to `0xFFF2`, with Arjun triggering each event one at a time.
- **(P3) Values are stale / set once and never refreshed.** App displays values from initial setup or last service center, never updated. To verify: ride bike (consume measurable fuel), reconnect, observe whether value changes.

**Previously claimed but RETRACTED**: "Data comes from Suzuki cloud via embedded SIM on the bike." This was wrong — the bike has no SIM. See `DISCOVERIES.md` for the walk-back.

### Implications for Phase 3 Branch B (telemetry dashboard) — REVISED

- Status: open. Depends on which of P1/P2/P3 is true.
- If P2 (BLE-on-event): a BLE-based dashboard becomes possible if we can identify and replay the right events. Could give live data when paired.
- If P1 (cloud-cached from main phone): a dashboard would require RE'ing Suzuki cloud API and using the user's credentials. Provides whatever the cloud has, not real-time.
- If P3 (stale): no live telemetry exists anywhere. Dashboard isn't possible at all without major architecture work.

## App-side BLE call chain (M3)

(TBD M3 — highest Suzuki-namespaced class that initiates BLE writes; notify handler class)

## Encryption / framing layer (M4)

(TBD M4 — cipher details, key derivation, framing bytes, or "no encryption" verdict)

## Navigation protocol (M5)

(TBD M5 — message schema: header, turn type enum, distance encoding, street name encoding, checksum)

## Non-navigation commands (M6)

(TBD M6 — app-issued commands beyond nav; display engine verdict)

## Telemetry & diagnostic surface (M7)

(TBD M7 — unused characteristics categorized; Phase 3 Branch B feasibility note)
