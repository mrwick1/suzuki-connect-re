# Suzuki Connect — Phase 1: Protocol Understanding (Design)

**Date**: 2026-05-23
**Author**: Arjun KR (with Claude)
**Scope**: Phase 1 of a multi-phase educational reverse-engineering project on Arjun's own 2023 Suzuki Gixxer SF 150 motorcycle.

## Background

The Suzuki Gixxer SF 150 (2023) ships with "Suzuki Connect," an in-cluster system that pairs with the Suzuki Ride Connect mobile app over Bluetooth Low Energy (BLE). The official app uses Mappls (MapmyIndia) for map data and routing. When the rider sets a destination in the app, the app pushes turn-by-turn navigation instructions to the bike's instrument cluster display over BLE.

This project explores the system end-to-end for educational purposes, on Arjun's own bike, using his own paired phone. The long-term goal is twofold: substitute Google Maps for Mappls, and explore how much custom information can be rendered to the cluster display.

The project is split into three phases. **This spec covers Phase 1 only.** Phase 2 and Phase 3 are sketched at the end so Phase 1 design choices do not paint future phases into corners. Phase 2 and Phase 3 will each get their own design + plan when their predecessor's exit gates pass.

## Goals (Phase 1)

Understand the Suzuki Connect BLE protocol well enough to:
1. Decode any captured navigation session into a human-readable transcript.
2. Construct a valid navigation message from scratch and have the bike's display accept and render it.
3. Document the protocol in a way another developer could implement an encoder from.
4. Know whether the cluster display is a fixed-icon engine or accepts arbitrary graphics (input to Phase 3 scoping).

## Non-Goals (Phase 1)

- No new mobile app built. Phase 2 territory.
- No HTTPS/cloud API reverse engineering beyond what BLE protocol comprehension requires. Phase 2 territory.
- No bike firmware modification, no physical bike opening, no bypassing of bike-side auth that locks third parties out. Phase 3+ territory if ever.
- No active fuzzing of the cluster display. Passive observation only in Phase 1.

## Workflow & Cadence

Interactive sessions between Arjun and Claude. Arjun runs captures and commands; Claude helps decode results and write tooling. The spec is the map; sessions hit milestones one at a time.

## Workspace & Tooling

### Project location
`~/coding/projects/suzuki-connect-re/`

### Directory layout
```
suzuki-connect-re/
├── README.md                # project state, current milestone, how to resume
├── NOTES.md                 # the living protocol spec — the actual deliverable of Phase 1
├── apk/                     # pulled APK + version notes (gitignored — copyrighted)
├── decompiled/              # JADX output (gitignored — derivative)
├── captures/                # HCI snoop logs + Wireshark sessions (gitignored — may contain VIN / BLE MAC / account ID)
├── frida-scripts/           # our hook scripts (tracked in git)
├── tools/                   # helper Python / shell scripts we write (tracked)
└── docs/                    # diagrams, photos of bike display, references, this spec (tracked)
```

### Laptop tooling (Arch Linux)
- `jadx` (AUR) — APK decompiler with GUI
- `apktool` (AUR) — resource extraction
- `android-tools` — adb
- `wireshark-qt` — BLE packet inspection
- `python-frida` + `frida-tools` — runtime hooking client
- `mitmproxy` — HTTPS interception (installed now, used in Phase 2)

### Spare Android device tooling
- LineageOS already installed
- KSU root already feasible — to be installed when Phase 1 begins
- `frida-server` binary pushed to `/data/local/tmp` and run as root
- USB debugging enabled, cable-connected for adb
- Suzuki Connect app installed, logged into Arjun's account, paired with the bike once

### Git policy
- `git init` in the project root at the start of Phase 1 (Milestone 0).
- `.gitignore` excludes `apk/`, `decompiled/`, `captures/` (copyright / PII).
- Notes, scripts, and docs committed as work progresses. New repo, new cadence (independent of the `my-life` weekly cadence).
- Personal Git identity (Arjun's `personal` alias).

### Why isolation matters
APKs and decompiled output carry copyright/licensing complications. BLE captures may contain the bike's VIN, Arjun's Suzuki account ID, and BLE MAC addresses. Keeping these out of Git lets findings be shared publicly later without leaking PII or breaking the law.

## BLE Primer (deferred to milestone delivery)

Arjun is new to BLE. Rather than front-load theory, a one-page cheat sheet (`docs/ble-primer.md`) will be written when Milestone 1 begins, covering exactly five concepts using real UUIDs from the Suzuki app:

1. **Central vs Peripheral** — Phone is Central, bike is Peripheral. Fixed.
2. **GATT** — The protocol layer on top of BLE that organizes operations.
3. **Service & Characteristic UUIDs** — How devices expose data; finding Suzuki's is the first concrete goal.
4. **Read / Write / Notify** — The three GATT operations. Write = phone → bike (the nav direction). Notify = bike → phone (the status direction).
5. **MTU** — Max bytes per BLE packet; matters when payloads are fragmented.

Out of scope for the primer: BLE Mesh, Beacons, Classic Bluetooth, BLE peripheral firmware, link-layer security internals.

Topics like pairing/bonding, ATT internals, and link-layer security are deferred — learned only when they bite us in a milestone.

## Milestones

Each milestone has a concrete deliverable that gates progress.

### M0 — Project scaffolding
- Create directory layout, `.gitignore`, README, empty NOTES.md.
- Install laptop tooling (one-shot script in `tools/setup-laptop.sh`).
- Install KSU on spare Android. Push `frida-server`. Verify `frida-ps -U` works.
- Install Suzuki Connect on spare device, log in, pair with bike.
- **Deliverable**: README's "Setup verified" checklist ticked. Sample `adb shell` and `frida-ps -U` outputs pasted in setup log.

### M1 — APK pull, decompile, identify BLE entry points
- `adb shell pm path com.suzuki.connect` (or actual package name once known) → `adb pull` the APK(s). Handle split APKs if present.
- JADX → open APK → search for `BluetoothGatt`, `BluetoothLeScanner`, `ScanCallback`, `UUID.fromString(`.
- Identify the class(es) that own BLE communication.
- **Deliverable**: A `NOTES.md` section listing: package name, version, BLE-owning class(es), every UUID found (service + characteristic), a one-line note per characteristic guessed from variable names.
- **Estimated**: 1–2 sessions. Faster if names survive obfuscation, slower if R8 stripped them.

### M2 — Capture a navigation session
- Enable HCI snoop log on the spare Android: Settings → Dev Options → Enable Bluetooth HCI snoop log → toggle Bluetooth off/on.
- With phone and bike together, set a ~5-minute destination in Suzuki Connect, simulate or perform the ride.
- `adb pull /sdcard/btsnoop_hci.log` (path may differ on LineageOS — confirm in M0 setup).
- Open in Wireshark, filter to bike's MAC.
- **Deliverable**: An annotated `.pcapng` in `captures/`, with timestamps of in-app actions. First-pass notes on which characteristic UUIDs see traffic, write vs notify direction, rough payload sizes.
- **Estimated**: 1–2 sessions.

### M3 — Frida hook the BLE writes/notifies
- `frida-server` running on phone as root. Verify `frida-ps -U` from laptop.
- Write a Frida JS script hooking `android.bluetooth.BluetoothGattCharacteristic.setValue([B)Z` and `BluetoothGattCallback.onCharacteristicChanged` → log every byte array with timestamp + JS-side stack trace.
- Re-run a navigation session with Frida attached.
- **Deliverable**: A Frida script in `frida-scripts/`; a log file showing every payload with timestamp + caller. We now have plaintext-from-app timestamped alongside raw-from-air from M2's pcap.
- **Estimated**: 1–2 sessions (first Frida script always slower).

### M4 — Find the encryption / framing layer (if it exists)
- Compare M2 raw bytes vs M3 hook bytes for the same wall-clock moments.
- **If they match**: no encryption between `setValue()` and the radio. Skip to M5.
- **If they differ**: walk back in JADX from `setValue()` to the cipher / key. Likely candidates: AES-CCM, AES-CBC with hardcoded IV, simple XOR-scrolling.
- **Deliverable**: Either a "no encryption" note in `NOTES.md`, or a documented cipher + key derivation in `NOTES.md` plus a standalone Python decoder that takes raw bytes and outputs plaintext.
- **Estimated**: 1–3 sessions. The wildcard milestone.

### M5 — Decode the navigation instruction protocol
- With plaintext payloads + user-action context, diff payloads across:
  - Different turn types (left, right, U-turn, slight left, roundabout exit N)
  - Different distances (10 m, 100 m, 1 km)
  - Different street name lengths
  - "Arrived" vs "off-route" vs "rerouting"
- Build the schema by induction.
- **Deliverable**: Documented schema in `NOTES.md` (message type byte(s), distance encoding, turn type enum, string encoding, checksum/CRC if any). Python encoder + decoder pair in `tools/`.
- **Estimated**: 2–3 sessions.

### M6 — Map the non-navigation display protocol (stretch)
- Trigger every UI feature in Suzuki Connect (start/end trip, settings changes, any action that changes the bike display).
- Capture + hook each. Document.
- Passive observation only — no fuzzing of unknown commands.
- **Deliverable**: A "known commands" section in `NOTES.md` covering everything beyond nav, with notes on whether the display is a fixed-icon engine or accepts richer data. This input scopes Phase 3.
- **Estimated**: 2–3 sessions.

**Total Phase 1 estimate**: 8–15 sessions of 1–2 hours each.

## Exit Gates (Phase 1 → Phase 2)

All four must pass.

### Gate 1: Decode any captured navigation session into human-readable text.
**Test**: Take a pcap captured by Arjun riding to a new destination he has not tested before. Run it through the decoder. Get output like:
```
[00:00.123] CONNECT bike=AA:BB:CC:DD:EE:FF
[00:01.456] TX nav.set_destination lat=12.9716 lon=77.5946 name="Cubbon Park"
[00:01.890] TX nav.instruction type=TURN_RIGHT distance=420m street="MG Road"
[00:15.222] TX nav.instruction type=STRAIGHT distance=1200m
[00:45.001] TX nav.instruction type=ARRIVED
```

### Gate 2: Construct a valid navigation message from scratch and the bike accepts it.
**Test**: Disconnect Suzuki Connect. Run a small Python script using `bleak` (or similar) that connects to the bike, completes any necessary auth, and writes a single "turn left in 300 m on Test Street" message. The bike's cluster display shows the instruction.

If bike-side auth blocks third-party clients, this gate requires a sub-milestone M4.5 documenting and implementing the auth handshake. If auth proves cryptographically infeasible to replicate from a third-party client, this gate may relax to a replay-based proof — at which point Phase 2 design would need to address the bonded-pairing workaround (e.g., sharing the bond via root).

### Gate 3: NOTES.md is implementable.
**Test**: Claude reads `NOTES.md` cold and writes the encoder without referring back to Arjun's code or captures. Any question Claude has to ask is a spec gap.

### Gate 4: We know whether the display is fixed-icon or arbitrary-graphics.
**Test**: M6's output. Even an outcome of "we observed N command types and cannot fully decode them" satisfies this gate, as long as the boundary between "we have categorized" and "we have not" is clear.

## Phase 2 & 3 Outlook (not designed here)

### Phase 2 — Google Maps replacement
The right shape, based on how Samsung Watch / Garmin / Pebble integrate with Google Maps:

- Minimal Android app with `BIND_NOTIFICATION_LISTENER_SERVICE` permission.
- User opens Google Maps normally on the phone, picks destination, hits Start.
- Our app subscribes to Google Maps' navigation notifications (turn icon drawable, distance string, street name, ETA).
- App parses notification, normalizes into structured form (turn type enum, distance int, street string).
- App feeds structured form into the encoder built in Phase 1 M5.
- App writes encoded bytes to the bike via the BLE protocol documented in Phase 1.

Why not the Google Directions API: free, no API key, the user keeps using Maps normally, and this is literally how every BLE-connected wearable in the world does turn-by-turn. Falls back to Directions API if notification parsing turns out unreliable.

What Phase 1 must enable for Phase 2 not to be rewritten:
- The M5 encoder takes structured input (turn type, distance, street name). The notification → structured step is fully Phase 2's job. **Phase 1 design is unaffected by this Phase 2 choice.**
- Gate 2 proves third-party writes work. If it fails on auth, Phase 1.5 may be required.

### Phase 3 — Custom display graphics
Scope depends on M6:

- **If display is fixed-icon engine**: Phase 3 is creative protocol abuse — combining known commands to surface non-nav content (clock, weather, music, etc.) within the constraints of the icon set + text fields.
- **If display accepts framebuffer/bitmap data**: Phase 3 is much larger — a tiny graphics engine for a low-color-depth embedded display.
- **Most likely**: hybrid. Phase 3 design happens when M6 data is in.

Active fuzzing of unknown display commands is deferred to a dedicated Phase 3 sub-phase with an explicit risk-acceptance step and a recovery plan (nearest Suzuki dealer for worst-case reflash).

## Risks & Open Questions

- **Obfuscation severity in the APK** — R8 may have renamed everything. Mitigation: M3 (Frida runtime hooks) bypasses naming entirely; we cross-reference back into static.
- **Encryption complexity** — If Suzuki uses certificate-pinned challenge-response keyed off the cloud account, breaking it from a third-party client may be infeasible. Mitigation: this is exactly what Gate 2 tests. If it fails, we have explicit fallback paths.
- **Bike-side authentication** — A bonded pairing may not be reproducible by a non-Suzuki app. Mitigation: documented in Gate 2; may require Phase 1.5 sub-milestone.
- **HCI snoop log path on LineageOS** — May differ from the standard `/sdcard/btsnoop_hci.log`. Verify in M0 setup.
- **App version drift** — A Suzuki Connect update mid-project may shift internals. Mitigation: record exact APK version in NOTES.md; pin to that version on the spare device (disable auto-update for this app).
- **Legal posture** — This is RE for personal interoperability on hardware Arjun owns, conducted in India. Findings may be shared publicly later as long as PII, copyright, and any anti-circumvention provisions are respected. Captures and APKs stay out of Git.

## Definition of Done

Phase 1 is complete when all four exit gates pass and `NOTES.md` is committed and reviewable.

Next step after Phase 1: design Phase 2 (notification-listener-based Google Maps app) in its own spec.
