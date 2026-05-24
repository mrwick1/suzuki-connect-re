# Suzuki Connect RE

Personal educational reverse-engineering of the **Suzuki Connect BLE protocol** on Arjun's 2023 Suzuki Gixxer SF 150 motorcycle. The bike has a Bluetooth-enabled instrument cluster that pairs with the Suzuki Ride Connect Android app — currently using Mappls maps for turn-by-turn navigation. Goal: understand the protocol fully, then build a Google-Maps-powered replacement app and explore what else can be done with the cluster.

## For a new conversation picking this up

If you're a fresh Claude agent loading this project for the first time, **read in this order**:

1. **`CLAUDE.md`** (this directory) — project rules. The hard "no assumptions" rule especially.
2. **This README** — what's the project, what's been done.
3. **`NOTES.md`** — current state of protocol knowledge (the polished spec).
4. **`DISCOVERIES.md`** — chronological log of how we got here, including walked-back wrong assumptions. Read this to understand the journey, not just the current state.
5. **`docs/superpowers/specs/2026-05-23-phase1-protocol-understanding-design.md`** — original Phase 1 design spec.
6. **`docs/superpowers/plans/2026-05-23-phase1-protocol-understanding.md`** — original implementation plan.
7. **`LOCAL_NOTES.md`** (gitignored) — PII (bike MAC, serial). Local only.

## Goal — three phases

| Phase | Goal | Status |
|-------|------|--------|
| **Phase 1** | Understand the BLE protocol fully. Document it so an encoder can be implemented from spec alone. | **~80% done** (M0 + most of M1 done; some open threads in NOTES) |
| **Phase 2** | Build an Android app that listens to Google Maps' nav notifications and translates them to the Suzuki protocol, sending to the bike via BLE. Replaces Mappls with Google Maps. | Not started — pending Phase 1 close |
| **Phase 3-A** | Custom cluster display — render arbitrary content (clock / weather / messages) to the bike's instrument cluster using its own text/icon fields. | Not started — depends on Phase 1 |
| **Phase 3-B** | Telemetry dashboard (RPM / fuel / etc.) on phone. May not be possible — bike has no SIM and only exposes engine temperature (so far) over BLE. | Open — needs more investigation |

## Current state (as of 2026-05-23)

### What's confirmed (with evidence)

- **Bike has NO SIM card** (Arjun confirmed by physical inspection; Suzuki India product page describes connectivity as Bluetooth-only). The bike does NOT phone home over cellular. All data exchange with Suzuki cloud happens via the paired phone.
- **BLE protocol surface** (from direct GATT walk): one vendor service `0xFFF0` with two characteristics:
  - `0xFFF1` (write) — phone → bike
  - `0xFFF2` (notify) — bike → phone
  - Plus standard GAP, GATT, Device Information services (metadata only).
- **Frame structure** (every message is exactly 30 bytes): `0xA5` header + ASCII type byte + body + checksum + `0x7F` terminator.
- **Checksum algorithm** (confirmed from decompiled source `SuzukiApplication.a()`):
  `checksum = sum(payload[1:28]) mod 256`
  Plus a `K.g`-gated INVERTED variant `255 - (sum % 256)` that exists but we haven't seen used.
- **Message types observed**:
  - `a531` — display refresh (time + distance + maneuver byte) — from `A0.D()` in source
  - `a533` — phone heartbeat (every 1 sec, sent 3× per tick) — from `f.java` (plus other variant we haven't traced)
  - `a536` — user identity push
  - `a537` — bike heartbeat (every 5 sec, response-driven — bike doesn't push until phone writes first)
- **Engine coolant temperature** is encoded at byte position 25 of `a537` notifies (degrees C, 83-89 °C observed range).
- **Notify is response-driven**: bike doesn't push notifies until the Central writes at least one message. Passive listener gets zero notifications.
- **"Searching for network"** on the cluster appears when the signal-status digit (`A0.H1` in source) is `"0"`. Set when no cellular signal. Real cellular SIM with active signal sets it to `"1"`-`"3"`.
- **App package**: `suzuki.com.suzuki` (APKs in `apk/`, decompiled in `decompiled/jadx-out/`).
- **App uses FastBle library** (`com.clj.fastble.*`) for BLE — most Suzuki class names are NOT obfuscated, big win for static analysis.

### Walked-back wrong assumptions (documented in DISCOVERIES.md)

1. *"Wireshark labels the bike's chars as PKOC/Aliro"* — dissector misfire. Real UUIDs are simple `0xFFFx` vendor space.
2. *"Bike has embedded SIM in TCU, fuel/odo data comes from cloud"* — bike has no SIM. Cloud is fed via paired phone.
3. *"Position 14 of a533 is the network YES/NO flag"* — actually SMS-present (i0) and Call-present (j0) indicators, inverted semantics (`'N'`=present, `'Y'`=cleared).
4. *"a533 pos 21=0x02 and pos 22=0xc9 are the signal-strength bytes"* — likely wrong. The real signal-status is the ASCII digit in `A0.H1` (which is `c.I` set by `B.java`'s PhoneStateListener) embedded into a531 templates. The 0x02/0xc9 may be coincidence from a different message variant.
5. *"Phase 3-B telemetry dashboard is dead because bike doesn't push telemetry"* — partial truth. Bike pushes engine TEMP via notify (slow), but appears to not push RPM/throttle/fuel. Still need to verify by capturing during more events.

### What's open / unknown

- **Real arrow byte values**: our captures always had degraded signal (no SIM or only WiFi), so the maneuver ID byte was always `0x2e` (`.`). We have the maneuver-ID → icon-ID mapping from `C0897z.java` but haven't seen real values on the wire.
- **A531 template field mapping**: the construction is `"?110" + p0 + m0 + n0 + "000" + q0 + o0 + str + str2 + "00000"`. We know what type of data each field carries roughly (time, distance, units) but not the exact instance-field origins.
- **Where fuel/odo/trip/last-location values come from**: not BLE (proven by exhaustive opcode analysis). Not bike-cellular (no SIM). Three remaining hypotheses (cloud-via-phone, stale local cache, BLE-event-we-didn't-trigger) — none verified.
- **K.g flag effect on checksum**: code shows alternate `255 - sum%256` variant; never observed in captures.

## File map

```
suzuki-connect-re/
├── README.md                       # ← you are here. Start-here guide.
├── CLAUDE.md                       # Project rules (NO ASSUMPTIONS, etc.)
├── NOTES.md                        # Living protocol spec (current best understanding)
├── DISCOVERIES.md                  # Chronological journey + walked-back assumptions
├── LOCAL_NOTES.md                  # PII (bike MAC, serial). Gitignored.
├── .gitignore
├── apk/                            # 4 APK splits (gitignored — copyrighted)
├── decompiled/                     # JADX output, 6751 Java files (gitignored)
├── captures/                       # HCI snoop pcaps + experiment logs (gitignored — PII)
│                                   #   3 key captures also backed up to ~/.suzuki-re-backups/
├── frida-scripts/
│   └── ride_capture.js             # JS hooks for TX/RX BLE calls + nav-state flags + signal/weather updates. Pair with HCI snoop log; cross-reference wire bytes ↔ Java-side semantic events during a ride.
├── tools/
│   ├── setup-laptop.sh             # M0: pacman/yay/pip install script (idempotent)
│   ├── gatt_walk.py                # Connect to bike, enumerate all services/chars (used in M1.6)
│   ├── passive_listen.py           # Subscribe to notify, no writes (proved bike is response-driven)
│   ├── provoke_and_listen.py       # Subscribe + identity + heartbeat loop (gets bike to stream)
│   ├── send_custom.py              # Replay captured a531 / send modified text
│   ├── phase_b_experiment.py       # First Phase B trial (replay + custom name)
│   ├── phase_b_with_network.py     # Second trial — send "network YES" heartbeat (didn't work)
│   ├── bike_full_diag.py           # Comprehensive batched experiment (all forge attempts)
│   └── forge_network.py            # Latest: forge cellular signal bytes (UNTESTED, may need fix per M1 findings)
├── tests/                          # (Empty — pytest scaffolding planned for M5)
└── docs/
    ├── ble-primer.md               # (TBD — to be written when M5 begins)
    ├── captures/                   # Capture-session annotations
    └── superpowers/
        ├── specs/2026-05-23-phase1-protocol-understanding-design.md
        └── plans/2026-05-23-phase1-protocol-understanding.md
```

## Hardware / setup state

- **Bike**: Suzuki Gixxer SF 150 (2023). BLE MAC: see `LOCAL_NOTES.md`. Cluster shows nav arrows ONLY when paired phone has real cellular signal.
- **Spare phone**: Redmi K20 Pro running LineageOS Android 16, KSU root, frida-server 17.9.10 installed, Suzuki Connect app installed + paired with bike. HCI snoop log enabled. SIM card now installed (after initial work was done with WiFi-only).
- **Laptop**: Arch Linux. Bluetooth must be unblocked manually (`rfkill unblock bluetooth`) before each BLE session — it's soft-blocked at boot. adb root works after enabling "Rooted debugging" in LineageOS dev options.
- **Tooling installed**: JADX (AUR), wireshark-qt, android-tools, Python venv at `.venv/` with bleak / frida-tools / pytest / pyshark / cryptography / mitmproxy.

## Quick-start for next session

```bash
# Activate Python venv
source ~/coding/projects/suzuki-connect-re/.venv/bin/activate

# If you need bike-on session: unblock laptop Bluetooth first
sudo rfkill unblock bluetooth   # ← only this needs sudo; Arjun should run it
bluetoothctl power on

# Verify bike visible (key on)
python tools/gatt_walk.py --scan
# Should see the bike's MAC + name "SBM110202788"

# Re-pull HCI snoop log after a session (requires adb root, already enabled)
adb pull /data/misc/bluetooth/logs/btsnoop_hci.log captures/<name>.pcap

# Inspect a capture
tshark -r captures/<name>.pcap -Y 'btatt' -T fields -e frame.time_relative -e btatt.opcode -e btatt.value
```

## Immediate next-session priorities

1. **Trace `C.I` / `A0.H1` through A0.D() template fields** to identify the exact byte position carrying the signal-status digit. Then build a corrected forge tool that sets this byte to `"1"` (good signal). This should defeat "Searching for network" and let us send real arrow bytes.
2. **Test the existing `forge_network.py`** built last session, as a baseline (expected to fail per M1 findings — but confirms the failure mode).
3. **Look for the second a533 construction path** — `f.java` produces messages that don't match our captures, so there's another path that does. Likely in `A0.java` or a related file.
4. **mitmproxy + Frida SSL pinning bypass** on Suzuki Connect to settle the fuel/odo/trip data source question.
5. **Capture an actual ride** to map turn-arrow message variations (only needed if we can't forge them).

## Git workflow

This repo lives independently of `~/my-life`. Commits happen per milestone or per significant discovery — not weekly. Personal Git identity (use `personal` alias). All commits authored as `Arjun KR <arjunkrishnaraj123@gmail.com>`. **No "Co-Authored-By" lines.**

## Communication / collaboration style

See `CLAUDE.md` for the hard rules. Briefly:
- **No assumptions**: verify before stating, mark hypotheses as hypotheses.
- **Document the journey**: when a prior claim is contradicted, add an entry to `DISCOVERIES.md` with what was assumed, what was actually true, and what evidence corrected it. Don't silently rewrite the spec.
- **Per Arjun's general preferences** (see global `~/.claude/CLAUDE.md`): direct, no sugarcoating, no emojis unless asked, no "Co-Authored-By" in commits.
