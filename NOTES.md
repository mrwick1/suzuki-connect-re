# Suzuki Connect Protocol Notes

> Living spec for the BLE protocol between the Suzuki Connect Android app and the Suzuki Gixxer SF 150 (2023). Grown milestone-by-milestone.

## Cloud API architecture (M1 — confirmed from decompiled source)

**Suzuki Connect's cloud API is minimal and Mappls-hosted:**

- **Base URL**: `https://projects.mapmyindia.com/`
- **HTTP client**: Retrofit (annotations stripped by ProGuard but interface signatures + callbacks intact)
- **Only TWO endpoints exist** in the entire app (both in `com.suzuki.interfaces.f` and `com.suzuki.interfaces.g`):
  - `GET /autolicverify/{BTID}/expiry/date/` — returns `ExpirationInfoResponse` (subscription expiry info)
  - `POST /autolicverify/...updatePlan` — returns `PlanUpdateResponse` (plan changes)
- **Auth**: OAuth-style. Gets `TokenResponse` with `access_token` + `expires_in`; stored in SharedPreferences `AppPrefs.ACCESS_TOKEN`. Sent as `authorization` header.

**There is NO cloud API for fuel/odometer/trip/last-location data.** The decompiled source has only the two license-management endpoints above. So **fuel/odo/trip data must come from BLE** (not cloud).

### Local storage

- **Suzuki Connect uses Realm Mobile Database** (`io.realm.*`) for local persistence.
- Realm model classes are in `com.suzuki.pojo.*` (28 files, A-Z + numbered). Field names are heavily obfuscated single letters (`a`, `b`, `c`, `a0`, `b0`, ...) — without runtime trace it's hard to map field-name → semantic meaning.
- App-state singleton at `com.suzuki.pojo.e` holds ~80 static fields for current session state (boolean flags + ints + strings).

### BLE callback architecture

- Suzuki uses **FastBle library** (`com.clj.fastble.*`).
- Suzuki's callback classes only extend two FastBle callback types:
  - `callback.a` (`BleGattCallback` — connection state) — `C0853p0`, `C0855q0`, `services/d`
  - `callback.b` (`BleWriteCallback` — write confirmation) — `services/a`, `services/b`
- **No class explicitly extends `BleNotifyCallback`** — the notify subscription is set up somewhere we haven't found yet (possibly via lambda / anonymous inner class to a `.notify(...)` call we missed). Once found, it will be the code that parses incoming `0xA5...0x7F` bytes from the bike and writes them to Realm.

### Data flow (confirmed direction)

```
Bike --[BLE notify]--> phone (FastBle) --[??? handler ???]--> Realm DB --> Activity/Fragment UI
                                                                ^
                              fuel/odo/trip values stored here, read by FuelConsumptionActivity,
                              TripDetailsActivity, etc.
```

The "???" is the FastBle notify subscription + parser that we haven't located yet. This is the next thing to find to fully close the data-flow question.

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

### Decoded field positions in `a531` (CONFIRMED 2026-05-23 by template-to-capture mapping)

Template field lengths derived from the captured NO-SIM and WITH-SIM messages, mapped against `A0.D()`'s template `"?110" + p0 + m0 + n0 + "000" + q0 + o0 + str + str2 + "00000"`:

| Byte position(s) | Field | Content |
|---|---|---|
| 0 | header | `0xA5` (constant) |
| 1 | type | `0x31` `'1'` (constant for a531) |
| 2 | maneuver ID | The Mappls maneuver int — or `0x2e` (`'.'`=46) if signal degraded (see "degraded mode" below) |
| 3 | pad | `0xFF` (constant — forced override) |
| 4-7 | `p0` | 4-char ASCII number. Examples: `"0080"`, `"0110"`. Unknown semantic (possibly trip-elapsed minutes, temperature, or similar). |
| 8 | `m0` | 1-char ASCII. Always `"M"` in captures. Possibly a unit/separator. |
| 9-14 | `n0` | 6-char ASCII. Examples: `"0517PM"`, `"0641PM"`. **CURRENT TIME** in 4-digit `HHMM` + `AM`/`PM` format. |
| 15-17 | pad | `0xFF` (constant — code forces `bytes[15..17] = -1`) |
| 18-21 | `q0` | 4-char ASCII number. **DISTANCE TO NEXT INSTRUCTION** (zero-padded). Examples: `"05.6"` (with decimal point), `"0348"` (integer). |
| 22 | `o0` | 1-char ASCII unit. **`"K"` = km, `"M"` = meters**. |
| **23** | **`str` (H1)** | **SIGNAL-STATUS digit. Confirmed: `'0'` = no signal (triggers "Searching for network"), `'1'` = good signal (cluster shows arrows).** |
| 24 | `str2` (I1) | Secondary status digit. Both captures showed `'1'`. Other values possible per A0.H1 logic. |
| 25-27 | pad | `0xFF` (constant — code forces `bytes[25..27] = -1`) |
| 28 | checksum | `SuzukiApplication.a(bytes)` = `sum(bytes[1:28]) mod 256` |
| 29 | terminator | `0x7F` (constant) |

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

### `a531` template field map (TBD — needs further tracing)

The template `"?110" + p0 + m0 + n0 + "000" + q0 + o0 + str + str2 + "00000"` has 5 instance fields (`p0`, `m0`, `n0`, `q0`, `o0`) of unknown lengths. From captured payloads we know roughly:
- The chars after `?110` and before `"000"` carry: 4-char number ("0080"), then "M", then time-like 4 chars ("0517"), then "PM" — so `p0` ≈ "0080", `m0` = "M", `n0` = "0517PM" or similar.
- Distance + units appears at positions ~18-24 — that's likely `q0` + `o0`.

These need to be confirmed by tracing where each field gets set in C.java / A0.java.

### Maneuver-ID → arrow-icon mapping (from `C0897z.java`)

The adapter maps Mappls maneuver IDs to icon resource IDs:

| Mappls maneuver ID | Icon ID | Notes |
|--------------------|---------|-------|
| 9, 10 | 8 | (something) |
| 26, 27, 28 | 15 | (one arrow type) |
| 29, 30, 31 | 16 | (another arrow type) |
| 65 | 67 | |
| 72 + bike state b0=20 | 65 | Roundabout variant |
| 72 + b0=21 | 66 | Roundabout variant |
| 72 + b0=22 | 67 | Roundabout variant |
| 72 + b0=23 | 68 | Roundabout variant |
| 72 + b0=24 | 69 | Roundabout variant |
| 72 + b0=25 | 70 | Roundabout variant |
| 72 + b0=26 | 71 | Roundabout variant |
| 72 (default) | 71 | |
| others | same as input | Direct passthrough |

These icon IDs are what the bike's cluster renders for arrows. So forging `bytes[2]` with values like `0x10` (16), `0x43` (67), etc. should produce specific turn arrows on the cluster — once we also forge the signal-status digit so `bytes[2]` isn't overwritten with `'.'`.

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
- Type byte: an ASCII digit (`'1'`, `'3'`, `'6'`, `'7'`) — i.e., type byte is `0x31`, `0x33`, `0x36`, `0x37`
- Payload: ASCII text fields, often separated by `0xFF` or null bytes
- Trailing byte: `0x7F` (end-of-message marker)
- Byte before `0x7F`: appears to be a checksum that varies with content

### Observed message types

**From phone to bike** (writes on `0xFFF1`):

| Type byte | Count in M0 capture | Rate | Decoded sample | Purpose (hypothesis) |
|-----------|---------------------|------|----------------|---------------------|
| `0x31` ('1') | 2988 (~86%) | ~2.7/sec | `.1..0080M0517PM...05.6K01...L.` | **Display update**: current time + distance-to-destination + flags. Pushed continuously to refresh cluster display. |
| `0x33` ('3') | 446 (~13%) | ~0.4/sec | `.33Y214.050154NN.........` | **Phone heartbeat / keepalive** with incrementing counter (050154, 050155, 050156...) |
| `0x36` ('6') | 36 (~1%) | episodic | `.6ARJUN.....................` | **User identity** — pushes user name (for display) on connection and at certain events |

**From bike to phone** (notifies on `0xFFF2`):

| Type byte | Count | Rate | Decoded sample | Purpose (hypothesis) |
|-----------|-------|------|----------------|---------------------|
| `0x37` ('7') | 163 | every ~5 sec | `.7[22 digit ID]N4[X]..[Y].` | **Bike heartbeat + slow telemetry**: long ID (looks like vehicle/session ID), then a `N4X` field where the byte at position 25 (the `X`) encodes **engine coolant temperature in degrees C**. Confirmed in 2026-05-23 trigger test (see DISCOVERIES.md): position 25 fell monotonically while engine off (bike cooling), then rose monotonically immediately after Arjun started the engine, in the 83-89 °C range typical for warm operating temp. In the static M0 capture this byte was constant; the variation only appears in live sessions with state changes. |

### Confirmed `a537` notify frame layout

```
pos: 0    1     2-22                      23 24 25         26-27  28      29
     0xA5 0x37  ASCII "00001672904911000984 9"  N  4  TEMP   ff ff  csum   0x7F
                                            ^ position 21      ^ position 25
                                            (last digit of      (engine
                                            embedded ID)        temp °C)
```

- Position 0: `0xA5` (header)
- Position 1: `0x37` ('7', message type)
- Positions 2-22: 21-char ASCII numeric, **constant across all observations** — this is some bike/session identifier (NOT the published serial, which is `SBM110202788`). Could be VIN-derived or internal device ID. Treat as PII; do not log to git.
- Position 23: `0x4E` ('N', constant)
- Position 24: `0x34` ('4', constant)
- Position 25: **engine coolant temperature, decimal-encoded as a single byte where the value is the degrees C** (e.g., 0x58 = 88°C). Decrements when engine off, increments when engine on.
- Positions 26-27: `0xFF 0xFF` (padding, constant)
- Position 28: checksum = `sum(payload[1:28]) mod 256` (confirmed algorithm)
- Position 29: `0x7F` (end-of-message)

### Confirmed: notify is response-driven, not autonomous

The bike does NOT push notifies on its own. It begins streaming `a537` heartbeats only AFTER the Central writes the first `a536` (identity) message. A passive listener that just subscribes and waits will receive nothing. This was tested in the 2026-05-23 experiment: 30+ seconds of pure subscribe-and-wait produced zero notifications; once we sent the captured identity write, notifications started immediately at the 5-sec heartbeat cadence.

### Open: what other notify message types exist?

In all sessions to date (M0 18-min + 2026-05-23 ~3-min experiments), only `a537` notify has been observed. Triggering horn / rev / indicator did NOT produce different notify types. Possibilities:
- Other types exist but only push during events we haven't triggered (riding, braking hard, fault conditions, trip end, low fuel, etc.)
- Other types exist but the bike pushes them via the write channel (response to specific phone commands) — needs Frida hooks on phone to see
- These transient events simply don't push BLE events at all in this firmware

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
