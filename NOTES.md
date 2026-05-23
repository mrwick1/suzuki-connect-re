# Suzuki Connect Protocol Notes

> Living spec for the BLE protocol between the Suzuki Connect Android app and the Suzuki Gixxer SF 150 (2023). Grown milestone-by-milestone.

## App identity (M1)

- Package name: (TBD M1.2)
- Version (versionName / versionCode): (TBD M1.2)
- APK SHA256: (TBD M1.1)
- Pulled on: (TBD M1.1)

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
| `0x37` ('7') | 163 | every ~5 sec | `.7[22 digit ID]N49..&.` | **Bike heartbeat**: long ID (looks like vehicle ID or session ID) + sequence counter. In the M0 capture this is the ONLY notify type observed. |

### Key implications

- **No encryption**: payloads are plaintext ASCII. M4 likely resolves to "no encryption layer."
- **Bike cluster is phone-driven**: the bike displays whatever ASCII the phone sends in `a531` messages. The cluster's "smart" display behavior (time, distance, turn arrows) is entirely a function of phone-side rendering pushed over BLE. **Phase 3 Branch A (custom display) is wide open** — we can probably display arbitrary text in the cluster's text regions.
- **Bike does not push live telemetry over THIS BLE protocol** — the notify stream is pure heartbeat in our 18-min capture. See `## Where do the app's fuel/odo/trip values come from?` below for the corrected understanding.

### Open puzzles

1. **Turn-arrow / nav-instruction encoding**: We expected to see explicit turn-type bytes (TURN_LEFT etc.). We don't — but the bike DOES show turn instructions when we navigate. Hypothesis: turn type + distance is embedded in `a531` payload fields we haven't decoded yet (one of the byte regions we currently read as "distance" might be a compound `<direction><distance>`). To be resolved in M5 with targeted captures.

2. **Variable checksum on identical visible content**: Of the 36 `a536` ("ARJUN") messages, all have identical visible payloads, BUT two distinct trailing checksums (`46 f7 7f` and `52 03 7f`) — split by capture timeline (early vs late). Pure-payload CRC would give a single answer. This means the checksum involves **hidden state** — most likely a session counter or per-session HMAC key established at pairing. **This is the hardest problem for Gate 2**: we cannot construct valid messages unless we can reproduce the checksum, which requires understanding the hidden state. Resolved in M4.

## Where do the app's fuel/odo/trip values come from? (CONFIRMED)

The Suzuki Connect app surfaces fuel level, odometer, trip A/B, last bike location, etc. **These values are NOT exchanged over BLE in this protocol** — confirmed at the protocol level (not by absence of a sample, but by absence of any data-read mechanism). See `DISCOVERIES.md` "2026-05-23 — Exhaustive capture re-analysis" for the full opcode-level evidence.

Specifically, across **5 separate BLE connection events** (each with fresh service discovery) over an 18-min capture totaling 7300 ATT packets:

- **Zero Read Requests (opcode 0x0a)** — the phone never directly reads any characteristic value.
- The only application data exchanged is: phone heartbeat (`a533`), user identity push (`a536`), display refresh (`a531`), bike heartbeat (`a537`).
- No bulk telemetry burst on any reconnect.

**Therefore**: fuel/odo/trip/last-location come from **Suzuki's cloud API via HTTPS**, populated by the bike's cellular TCU (Telematics Control Unit) uploading telemetry independently of any paired phone. The "last sync time" displayed in the app refers to a cloud refresh or paired-connection check-in, not a BLE data fetch.

This is the industry-standard architecture for connected vehicles (Tesla, BMW ConnectedRide, Honda RoadSync all work this way). "Last bike location" working when the phone isn't paired or when the bike is off is direct evidence the bike phones home over its own cellular link.

### Implications for Phase 3 Branch B (telemetry dashboard)

- **"Telemetry dashboard via BLE subscription" is DEAD.** The bike does not expose live telemetry over BLE in this protocol.
- **"Telemetry dashboard via Suzuki cloud API" is the viable path.** Requires HTTPS interception (mitmproxy + Frida SSL pinning bypass) to RE the cloud API, document endpoints + auth, then build a dashboard.
- This is arguably more powerful than BLE-only — the cloud likely has historical data (trip history, fault code archives, fuel economy trends) that real-time BLE could not provide.
- Project shape changes: less "subscribe to live BLE notify stream," more "RE a REST API." Likely a sub-phase between Phase 1 and Phase 2.

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
