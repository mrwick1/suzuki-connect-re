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
