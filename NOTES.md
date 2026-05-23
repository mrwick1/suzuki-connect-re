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

### Services

| UUID | Name (if standard) | App-used? | Notes |
|------|--------------------|-----------|-------|
| (TBD M1 full enumeration) | | | |

### Characteristics

| Service UUID | Char UUID | Properties | App-used? | Sample read | Inferred purpose |
|--------------|-----------|------------|-----------|-------------|------------------|
| (TBD M1 full enumeration) | | | | | |

### Preliminary observations from M0 capture (verify in M1/M2)

From the M0 pairing+nav HCI snoop, `tshark`'s dissector tentatively identified the bike's active write/notify characteristics by their UUIDs:

- **Handle 0x001e** — Write Request target (phone → bike). Wireshark labels: "Public Key Open Credential (PKOC): ICCE Digital Key".
- **Handle 0x0020** — Notify source (bike → phone). Wireshark labels: "Public Key Open Credential (PKOC): Aliro".
- **Handle 0x0021** — CCCD descriptor for handle 0x0020 (where the phone enables notifications).
- Write payloads observed at 42 bytes consistent length — consistent with public-key crypto material (e.g., ECC-P256 point = 64 bytes, signature = 64 bytes split across messages).

**If Wireshark's dissector is accurate**, Suzuki Connect uses an automotive digital-key BLE profile based on **Aliro** (Connectivity Standards Alliance contactless access standard) and/or **ICCE** (In-Car Connectivity & Engagement, Chinese digital-key spec) for the auth layer. Implications for Phase 1:

- M4 ("encryption layer") likely finds a public-key challenge-response handshake, not symmetric encryption with a static key.
- Gate 2 ("third-party send valid message") becomes substantially harder — we may need to either replicate the auth flow with the bike's public key, or replay a session captured from the legitimate app, or document this as a hard wall and relax Gate 2 to a passive proof.

**This is preliminary** — Wireshark's dissector pattern-matches UUIDs against its built-in dictionary; Suzuki may have chosen UUIDs that coincidentally overlap with PKOC's range. M1 will confirm by reading the actual UUIDs and cross-referencing against the published Aliro/ICCE specs.

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
