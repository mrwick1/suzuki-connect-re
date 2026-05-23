# Suzuki Connect Phase 1: Protocol Understanding — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reverse-engineer the BLE protocol between the Suzuki Connect Android app and Arjun's 2023 Suzuki Gixxer SF 150, producing (a) a documented protocol spec in `NOTES.md`, (b) Python encoder/decoder tools, (c) a passive telemetry surface map of the bike's BLE GATT tree, and (d) proof-of-life that a third-party client can send valid navigation messages to the bike's cluster display.

**Architecture:** Three converging analysis fronts feed one living document (`NOTES.md`): (1) static analysis of the decompiled Suzuki Connect APK via JADX, (2) live BLE wire captures via Android HCI snoop log + Wireshark, (3) runtime hooks of the app's BLE write/notify methods via Frida on a rooted LineageOS spare phone. Findings cross-reference into encoder/decoder Python tools that get TDD'd against captured payload fixtures. Parallel passive enumeration of the bike's full GATT tree maps hidden (non-app-used) telemetry/diagnostic surface.

**Tech Stack:**
- Python 3.14 + `bleak` (BLE client) + `pytest` (encoder/decoder tests)
- Frida 16+ (JS runtime hooks via `frida-server` on phone)
- JADX (APK decompiler), apktool (resources), `android-tools` adb
- Wireshark with BLE dissector
- LineageOS + KernelSU (KSU) on spare Android
- Project repo: `~/coding/projects/suzuki-connect-re/`

---

## File Structure

```
suzuki-connect-re/
├── README.md                       # current state, how to resume work
├── NOTES.md                        # THE deliverable: living protocol spec
├── .gitignore
├── apk/                            # gitignored: copyrighted APK pulls
├── decompiled/                     # gitignored: JADX output
├── captures/                       # gitignored: pcaps + Frida logs (PII)
├── frida-scripts/
│   ├── hook-setvalue.js            # M3: minimal write hook
│   ├── hook-onchanged.js           # M3: minimal notify hook
│   └── hook-full.js                # M3: combined with stack traces
├── tools/
│   ├── setup-laptop.sh             # M0: one-shot dependency installer
│   ├── gatt_walk.py                # M1: bike GATT enumeration
│   ├── decrypt.py                  # M4: cipher reverser (if encrypted)
│   ├── encoder.py                  # M5: structured → bytes
│   ├── decoder.py                  # M5: bytes → structured
│   ├── transcript.py               # M5: pcap → human-readable log (Gate 1)
│   ├── send_test_nav.py            # M5: Gate 2 proof-of-life
│   └── telemetry_sub.py            # M7: subscribe to unused chars
├── tests/
│   ├── conftest.py
│   ├── test_encoder.py
│   ├── test_decoder.py
│   ├── test_round_trip.py
│   └── test_decrypt.py
└── docs/
    ├── ble-primer.md               # written in M1: 5-concept cheat sheet
    ├── superpowers/
    │   ├── specs/2026-05-23-phase1-protocol-understanding-design.md
    │   └── plans/2026-05-23-phase1-protocol-understanding.md  (this file)
```

**Discovery-output references:** Several later tasks use placeholders like `<BIKE_MAC>`, `<APP_PKG>`, `<NAV_SERVICE_UUID>`, `<NAV_WRITE_CHAR_UUID>`. These are not "TODO fill in" — they are values discovered in earlier milestones (M1) and written into `NOTES.md`. When a later task uses one, substitute the actual value from `NOTES.md`. Each placeholder is defined where it's first discovered.

---

## M0 — Project Scaffolding

### Task 0.1: Create project directory structure

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/{frida-scripts,tools,tests,tests/fixtures,docs}/`

- [ ] **Step 1: Create directories**

```bash
cd ~/coding/projects/suzuki-connect-re
mkdir -p frida-scripts tools tests/fixtures docs apk decompiled captures
```

- [ ] **Step 2: Verify**

```bash
ls -la
```

Expected: directories listed include `apk decompiled captures docs frida-scripts tests tools` plus existing `docs/superpowers/`.

### Task 0.2: Write `.gitignore`

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/.gitignore`

- [ ] **Step 1: Write the file**

```gitignore
# Copyrighted / derivative
apk/
decompiled/

# Capture data may contain VIN, BLE MAC, Suzuki account ID
captures/
tests/fixtures/

# Python
__pycache__/
*.pyc
.pytest_cache/
.venv/
venv/

# IDE
.vscode/
.idea/
*.swp

# OS
.DS_Store
```

- [ ] **Step 2: Verify ignores work**

```bash
cd ~/coding/projects/suzuki-connect-re
touch apk/test.txt && git status --short
```

Expected: `apk/test.txt` is NOT shown by git status. Remove the test file:

```bash
rm apk/test.txt
```

### Task 0.3: Write initial README.md

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/README.md`

- [ ] **Step 1: Write the README**

```markdown
# Suzuki Connect RE

Personal educational reverse-engineering of the Suzuki Connect BLE protocol on a 2023 Suzuki Gixxer SF 150. Goal: understand the protocol, build a Google-Maps-powered replacement app, explore the cluster display's full capabilities.

## Status

**Current phase:** Phase 1 — Protocol Understanding
**Current milestone:** M0 — Setup
**See:** `docs/superpowers/plans/2026-05-23-phase1-protocol-understanding.md` for the active plan.
**See:** `NOTES.md` for the living protocol spec (the actual deliverable).

## Layout

- `NOTES.md` — protocol spec, grown across milestones
- `frida-scripts/` — runtime hooks for the Suzuki Connect app
- `tools/` — Python helpers (GATT walker, encoder, decoder, etc.)
- `tests/` — pytest suite for encoder/decoder
- `docs/` — primer, design spec, implementation plan
- `apk/`, `decompiled/`, `captures/` — gitignored, may contain PII / copyrighted material

## Setup checklist

- [ ] Laptop tooling installed (`tools/setup-laptop.sh`)
- [ ] Spare Android with LineageOS + KSU root
- [ ] `frida-server` running on phone, `frida-ps -U` works from laptop
- [ ] Suzuki Connect app installed on spare phone, logged in, paired with bike
- [ ] HCI snoop log path on LineageOS verified
```

- [ ] **Step 2: Verify the file**

```bash
head -5 ~/coding/projects/suzuki-connect-re/README.md
```

Expected: shows the `# Suzuki Connect RE` heading.

### Task 0.4: Write initial NOTES.md skeleton

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/NOTES.md`

- [ ] **Step 1: Write the skeleton**

```markdown
# Suzuki Connect Protocol Notes

> Living spec for the BLE protocol between the Suzuki Connect Android app and the Suzuki Gixxer SF 150 (2023). Grown milestone-by-milestone.

## App identity (M1)

- Package name: (TBD M1.2)
- Version (versionName / versionCode): (TBD M1.2)
- APK SHA256: (TBD M1.1)
- Pulled on: (TBD M1.1)

## Bike identity

- Model: Suzuki Gixxer SF 150, 2023
- BLE MAC: (TBD M1.5)
- BLE local name (advertised): (TBD M1.5)

## BLE GATT tree (M1)

### Services

| UUID | Name (if standard) | App-used? | Notes |
|------|--------------------|-----------|-------|
| (TBD) | | | |

### Characteristics

| Service UUID | Char UUID | Properties | App-used? | Sample read | Inferred purpose |
|--------------|-----------|------------|-----------|-------------|------------------|
| (TBD) | | | | | |

## Navigation protocol (M5)

(TBD M5 — message schema goes here)

## Encryption / framing layer (M4)

(TBD M4 — cipher details, key derivation, framing bytes)

## Non-navigation commands (M6)

(TBD M6 — app-issued commands beyond nav)

## Telemetry & diagnostic surface (M7)

(TBD M7 — unused characteristics categorized)
```

- [ ] **Step 2: Verify**

```bash
wc -l ~/coding/projects/suzuki-connect-re/NOTES.md
```

Expected: ~45 lines.

### Task 0.5: Write `tools/setup-laptop.sh`

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/tools/setup-laptop.sh`

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
# Phase 1 laptop tooling installer. Idempotent.
set -euo pipefail

echo "==> Installing pacman packages"
sudo pacman -S --needed --noconfirm \
    android-tools \
    wireshark-qt \
    python python-pip

echo "==> Installing yay (AUR) packages"
yay -S --needed --noconfirm \
    jadx \
    apktool

echo "==> Installing Python packages in a project venv"
cd "$(dirname "$0")/.."
if [[ ! -d .venv ]]; then
    python -m venv .venv
fi
# shellcheck disable=SC1091
source .venv/bin/activate
pip install --upgrade pip
pip install bleak frida-tools mitmproxy pytest

echo
echo "==> Checking versions"
jadx --version
adb version | head -1
wireshark --version | head -1
.venv/bin/frida --version
.venv/bin/python -c "import bleak; print(f'bleak {bleak.__version__}')"

echo
echo "All laptop tooling installed."
echo "Activate venv with: source .venv/bin/activate"
```

- [ ] **Step 2: Make executable and run**

```bash
chmod +x ~/coding/projects/suzuki-connect-re/tools/setup-laptop.sh
~/coding/projects/suzuki-connect-re/tools/setup-laptop.sh
```

Expected: all installs succeed, version banner prints at the end. If any AUR package conflicts or pacman prompts, address and re-run.

### Task 0.6: Install KSU on spare phone (manual)

**Files:** None.

- [ ] **Step 1: Follow KSU install procedure for the spare phone's LineageOS build**

Reference: https://kernelsu.org/guide/installation.html — pick the section matching the LineageOS Android version on the spare phone.

Generally: flash KSU-patched boot.img via fastboot or install KSU manager APK and let it patch.

- [ ] **Step 2: Verify root**

On phone, install KSU Manager app. Open a terminal app (Termux works) and run:

```bash
su -c id
```

Expected: `uid=0(root) gid=0(root)` line.

### Task 0.7: Push and start `frida-server` on phone

**Files:** None (binary lives on phone).

- [ ] **Step 1: Download matching `frida-server` for phone arch**

On laptop:

```bash
adb shell getprop ro.product.cpu.abi
```

Expected: e.g. `arm64-v8a`.

Download the matching `frida-server` build for the version of frida-tools installed in the venv:

```bash
cd /tmp
source ~/coding/projects/suzuki-connect-re/.venv/bin/activate
FRIDA_VERSION=$(frida --version)
wget "https://github.com/frida/frida/releases/download/${FRIDA_VERSION}/frida-server-${FRIDA_VERSION}-android-arm64.xz"
unxz "frida-server-${FRIDA_VERSION}-android-arm64.xz"
```

(Adjust filename if phone arch is not arm64.)

- [ ] **Step 2: Push and chmod**

```bash
adb push "frida-server-${FRIDA_VERSION}-android-arm64" /data/local/tmp/frida-server
adb shell chmod 755 /data/local/tmp/frida-server
```

- [ ] **Step 3: Start as root**

```bash
adb shell "su -c '/data/local/tmp/frida-server &'"
```

- [ ] **Step 4: Verify from laptop**

```bash
frida-ps -U | head -20
```

Expected: list of running processes on phone, including system processes. If you get `unable to connect to remote frida-server`, recheck that `frida-server` is running and matches the laptop frida version.

### Task 0.8: Install Suzuki Connect, log in, pair with bike

**Files:** None.

- [ ] **Step 1: Install Suzuki Connect from Play Store** on the spare phone (with the spare phone's Google account).

- [ ] **Step 2: Disable auto-update for Suzuki Connect** (Play Store → app page → 3-dot menu → uncheck "Enable auto update"). This pins the version we'll RE.

- [ ] **Step 3: Log in with Arjun's Suzuki Connect credentials**, follow in-app pairing flow with the bike.

- [ ] **Step 4: Set a short test destination** in the app, confirm the bike's cluster display shows turn instructions. This is the "happy path" we'll be replicating throughout Phase 1.

### Task 0.9: Verify HCI snoop log path on LineageOS

**Files:**
- Modify: `~/coding/projects/suzuki-connect-re/NOTES.md` (add HCI path note)

- [ ] **Step 1: Enable HCI snoop log**

On spare phone: Settings → System → Developer options → "Enable Bluetooth HCI snoop log" → ON. Toggle Bluetooth off then on.

- [ ] **Step 2: Briefly use Bluetooth** (e.g., scan for devices in Settings → Bluetooth).

- [ ] **Step 3: Find the snoop log file** on the phone via adb:

```bash
adb shell "find /sdcard /data -name 'btsnoop*' 2>/dev/null"
adb shell "find / -name 'btsnoop*' 2>/dev/null" | head -10
```

Expected: at least one path returned. Common paths: `/sdcard/btsnoop_hci.log`, `/data/misc/bluetooth/logs/btsnoop_hci.log`, `/data/log/bt/btsnoop_hci.log`.

- [ ] **Step 4: Record the path in NOTES.md** under a new section:

```markdown
## Tooling notes

- HCI snoop log path on this LineageOS build: `<PATH FOUND IN STEP 3>`
- Spare phone arch: `arm64-v8a` (or as per Task 0.7 Step 1)
- frida-server version: `<FRIDA_VERSION>`
```

### Task 0.10: Initial commit

**Files:** All scaffolding so far.

- [ ] **Step 1: Stage and commit**

```bash
cd ~/coding/projects/suzuki-connect-re
git add -A
git -c commit.gpgsign=false commit -m "feat(M0): project scaffolding, README, NOTES skeleton, setup script"
git log --oneline
```

Expected: three commits in the log (initial spec, expanded spec, this scaffolding commit).

---

## M1 — APK Pull, Decompile, Full GATT Enumeration

### Task 1.1: Identify Suzuki Connect package name and pull APK

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/apk/` content

- [ ] **Step 1: Find the package name**

```bash
adb shell pm list packages | grep -i suzuki
```

Expected: one or more lines like `package:com.suzuki.connect` (or `com.suzukimotorcycle.connect`, `com.suzuki.ride` — exact name TBD). Pick the one that matches the Connect/Ride app installed in Task 0.8.

- [ ] **Step 2: Find APK path(s) on device**

```bash
ADB_PKG="<PKG_FROM_STEP_1>"
adb shell pm path "$ADB_PKG"
```

Expected: one or more `package:/data/app/...base.apk` lines. If multiple lines, the app uses split APKs.

- [ ] **Step 3: Pull all APKs**

```bash
cd ~/coding/projects/suzuki-connect-re/apk
for p in $(adb shell pm path "$ADB_PKG" | sed 's/^package://' | tr -d '\r'); do
    adb pull "$p" .
done
ls -la
```

Expected: `base.apk` (and possibly `split_*.apk` files).

- [ ] **Step 4: Record APK hash**

```bash
sha256sum apk/base.apk
```

- [ ] **Step 5: Update NOTES.md `App identity` section** with package name, APK pull date, and base.apk SHA256.

### Task 1.2: Decompile with JADX and record app version

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/decompiled/` content
- Modify: `~/coding/projects/suzuki-connect-re/NOTES.md`

- [ ] **Step 1: Decompile**

```bash
cd ~/coding/projects/suzuki-connect-re
jadx -d decompiled apk/base.apk 2>&1 | tail -5
```

Expected: completion message, possibly with warnings (normal). Output in `decompiled/`.

- [ ] **Step 2: Find app version from AndroidManifest.xml**

```bash
apktool d -f -o decompiled/apktool-out apk/base.apk 2>/dev/null
grep -E 'versionName|versionCode' decompiled/apktool-out/apktool.yml
```

Expected: `versionName: x.y.z`, `versionCode: NNN`.

- [ ] **Step 3: Update NOTES.md** with versionName and versionCode.

### Task 1.3: Find BLE entry points in decompiled source

**Files:** Read-only research.

- [ ] **Step 1: Open JADX-GUI**

```bash
jadx-gui ~/coding/projects/suzuki-connect-re/apk/base.apk &
```

Wait for indexing to complete (status bar at bottom).

- [ ] **Step 2: Text search for BLE primitives**

In JADX-GUI: Navigation → Text Search (Ctrl+Shift+F):
- Search 1: `BluetoothGatt`
- Search 2: `BluetoothLeScanner`
- Search 3: `UUID.fromString(`
- Search 4: `connectGatt`

For each, note which classes the results live in. Focus on results that are not in standard library or AndroidX namespaces — find the Suzuki-specific classes.

- [ ] **Step 3: List candidate BLE classes**

Make a mental shortlist (typically 1-3 classes own BLE in any given app, often named like `BluetoothService`, `BleManager`, `DeviceConnection`, or obfuscated `a.b.c`).

### Task 1.4: Catalog every UUID in the app

**Files:**
- Modify: `~/coding/projects/suzuki-connect-re/NOTES.md`

- [ ] **Step 1: grep for UUID literals across decompiled source**

```bash
cd ~/coding/projects/suzuki-connect-re
grep -r --include='*.java' -hE '"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"' decompiled/ \
  | tr -d '"' | sort -u > /tmp/app_uuids.txt
wc -l /tmp/app_uuids.txt
cat /tmp/app_uuids.txt
```

Expected: a list of UUIDs, including some standard BLE service UUIDs (battery `0000180f-...`, device info `0000180a-...`) and likely some custom UUIDs (Suzuki vendor space, often starting with non-zero high bits).

- [ ] **Step 2: Identify custom UUIDs**

Filter out standard Bluetooth SIG UUIDs (those starting with `0000xxxx-0000-1000-8000-00805f9b34fb`). The remaining are Suzuki's custom UUIDs and characteristic UUIDs.

- [ ] **Step 3: For each custom UUID, find its context in JADX-GUI**

In JADX-GUI text search, paste each custom UUID. The context (variable name, comment, surrounding code) hints at its purpose. Common Suzuki naming hints: `nav`, `route`, `command`, `cmd`, `status`, `notify`, `write`.

- [ ] **Step 4: Update NOTES.md `BLE GATT tree → Characteristics` table** with each UUID found, marked `app-used: yes`, with a one-line inferred purpose.

### Task 1.5: Write the bike-side GATT walker

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/tools/gatt_walk.py`

- [ ] **Step 1: Write the walker**

```python
#!/usr/bin/env python3
"""Connect to a BLE peripheral and enumerate every Service + Characteristic.

Usage:
    python gatt_walk.py --scan                       # find devices
    python gatt_walk.py --address AA:BB:CC:DD:EE:FF  # walk one device

Prints a tree and (for readable characteristics) the bytes of a single read.
NEVER WRITES.
"""
import argparse
import asyncio
import sys
from bleak import BleakScanner, BleakClient


async def scan(timeout: float = 8.0) -> None:
    print(f"Scanning for {timeout}s...")
    devices = await BleakScanner.discover(timeout=timeout)
    for d in devices:
        print(f"  {d.address}  RSSI={d.rssi}  name={d.name!r}")


async def walk(address: str) -> None:
    print(f"Connecting to {address}...")
    async with BleakClient(address) as client:
        print(f"Connected. MTU={client.mtu_size}")
        for service in client.services:
            print(f"\nService {service.uuid}  ({service.description})")
            for char in service.characteristics:
                props = ",".join(char.properties)
                print(f"  Char {char.uuid}  [{props}]  ({char.description})")
                if "read" in char.properties:
                    try:
                        data = await client.read_gatt_char(char.uuid)
                        print(f"    READ -> {data.hex()}  ({len(data)} bytes)")
                    except Exception as e:
                        print(f"    READ failed: {e}")
                for desc in char.descriptors:
                    print(f"    Descriptor {desc.uuid}  handle={desc.handle}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--scan", action="store_true")
    parser.add_argument("--address")
    parser.add_argument("--timeout", type=float, default=8.0)
    args = parser.parse_args()

    if args.scan:
        asyncio.run(scan(args.timeout))
    elif args.address:
        asyncio.run(walk(args.address))
    else:
        parser.print_help()
        sys.exit(1)


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Sanity check it parses**

```bash
cd ~/coding/projects/suzuki-connect-re
source .venv/bin/activate
python tools/gatt_walk.py --help
```

Expected: argparse help text.

### Task 1.6: Find the bike's BLE MAC and walk its GATT tree

**Files:**
- Modify: `~/coding/projects/suzuki-connect-re/NOTES.md`

- [ ] **Step 1: Power on bike (key ON, engine OFF is fine)** so its BLE radio advertises.

- [ ] **Step 2: Scan from laptop**

Ensure laptop Bluetooth is on. Make sure the spare phone is NOT currently connected to the bike (Suzuki Connect app closed, or BT off on phone) — only one Central can connect at a time.

```bash
cd ~/coding/projects/suzuki-connect-re
source .venv/bin/activate
python tools/gatt_walk.py --scan
```

Expected: list of nearby BLE devices including the bike (look for a name like `SUZUKI`, `GIXXER`, `SZK`, or similar; if no name, identify by RSSI proximity — move laptop close to bike and look for the strongest signal that appears only when bike is keyed on).

- [ ] **Step 3: Record bike MAC and advertised name in NOTES.md** `Bike identity` section. This MAC is referenced in later tasks as `<BIKE_MAC>`.

- [ ] **Step 4: Walk the GATT tree**

```bash
python tools/gatt_walk.py --address <BIKE_MAC> | tee captures/gatt-walk-$(date +%Y%m%d-%H%M).txt
```

Expected: A tree of services and characteristics, with sample read values for readable chars. If connection fails: bike may require pairing/bonding first (handled by the Suzuki Connect app initially). If bonding is required, see Step 5.

- [ ] **Step 5 (only if Step 4 fails with auth/pairing error):**

Use the spare phone's existing pairing. The walker via bleak should reuse system-level bond if the laptop is bonded too. Pair the laptop with the bike via `bluetoothctl`:

```bash
bluetoothctl
[bluetooth]# pairable on
[bluetooth]# scan on
# Wait for bike to appear
[bluetooth]# pair <BIKE_MAC>
[bluetooth]# trust <BIKE_MAC>
[bluetooth]# scan off
[bluetooth]# exit
```

Then retry Step 4.

### Task 1.7: Tag app-used vs unused, classify discoveries

**Files:**
- Modify: `~/coding/projects/suzuki-connect-re/NOTES.md`

- [ ] **Step 1: Cross-reference**

For each characteristic UUID listed in the GATT walk output (Task 1.6), check whether it appears in the `/tmp/app_uuids.txt` from Task 1.4. If yes, tag `app-used: yes`. If no, tag `app-used: no` (this is the M7 input set).

- [ ] **Step 2: Fill out the `Characteristics` table in NOTES.md** with all rows.

For unused characteristics, the `Inferred purpose` column starts with a guess based on:
  - The sample read value's shape (4 bytes constant = probably a serial/version; counter-like single byte = state; long ASCII string = name; etc.)
  - Standard BLE characteristic UUIDs that overlap (`00002a19-...` = Battery Level, `00002a29-...` = Manufacturer Name, etc. — see `decompiled/apktool-out/res/values/` if Bluetooth assigned numbers are referenced anywhere)

- [ ] **Step 3: Commit M1 progress**

```bash
cd ~/coding/projects/suzuki-connect-re
git add NOTES.md tools/gatt_walk.py README.md
git -c commit.gpgsign=false commit -m "feat(M1): GATT walker + initial protocol notes from APK + bike enumeration"
```

### Task 1.8: Write the BLE primer

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/docs/ble-primer.md`

- [ ] **Step 1: Write the primer**

Using the actual UUIDs and bike MAC discovered above, write a one-page reference covering the five concepts (Central/Peripheral, GATT, Services & Characteristics, Read/Write/Notify, MTU) with Suzuki-specific examples.

```markdown
# BLE Primer (Suzuki-flavored)

## 1. Central vs Peripheral

The phone (or laptop) is the **Central**: it scans, connects, and initiates GATT operations. The bike is the **Peripheral**: it advertises and accepts connections.

Practically: in our scripts, we (Central) are always the active party. The bike just listens.

## 2. GATT

Generic Attribute Profile. The protocol layer that organizes data into Services and Characteristics. Everything Suzuki Connect does over BLE is a GATT operation.

## 3. Services and Characteristics

A **Service** is a logical grouping (e.g., "navigation"). A **Characteristic** is a single data point or command channel inside a service.

Both are identified by 128-bit UUIDs. The Bluetooth SIG owns the range `0000xxxx-0000-1000-8000-00805f9b34fb`; Suzuki's custom protocol uses different UUIDs.

Bike example (from M1):
- Service `<NAV_SERVICE_UUID>` — navigation commands
- Characteristic `<NAV_WRITE_CHAR_UUID>` — phone writes here to send instructions
- Characteristic `<NAV_NOTIFY_CHAR_UUID>` — bike notifies here for status changes

## 4. Read / Write / Notify

- **Read**: Central pulls current value. Used for static info (model number, paired phone count).
- **Write**: Central pushes a value to the peripheral. This is how Suzuki Connect sends nav: write to `<NAV_WRITE_CHAR_UUID>`.
- **Notify**: Peripheral pushes value changes to the subscribed Central. Used for status updates (bike → phone).

Each characteristic declares which it supports in its "properties" flags.

## 5. MTU

Maximum Transmission Unit. Default 23 bytes per BLE packet (only 20 usable as payload). Longer messages get fragmented. After connecting, MTU can be negotiated up to 247 (older) or 512 (newer). The bike on this connection negotiates `<MTU>` (see GATT walk output).

If we ever see a nav message larger than the negotiated MTU, the app is fragmenting at the application layer (not L2CAP) and we need to find the reassembly logic.

## Recommended further reading (only when needed)

- Pairing/bonding: only if Task 1.6 hit auth issues.
- L2CAP, ATT internals: Wireshark dissects these for us.
- Link-layer encryption: rare in app-layer protocols; we'd suspect this only if Frida-hooked payloads match the wire payloads byte-for-byte but the bike rejects our writes.
```

- [ ] **Step 2: Commit**

```bash
git add docs/ble-primer.md
git -c commit.gpgsign=false commit -m "docs(M1): add BLE primer with Suzuki-specific examples"
```

---

## M2 — Capture a Real Navigation Session

### Task 2.1: Re-enable HCI snoop log and clear old logs

**Files:** None.

- [ ] **Step 1: Ensure HCI snoop log is enabled** (Settings → Dev Options → "Enable Bluetooth HCI snoop log").

- [ ] **Step 2: Toggle Bluetooth off then on** to start a fresh log file.

- [ ] **Step 3: Delete any old snoop log on phone**

```bash
adb shell "su -c 'rm -f <HCI_PATH_FROM_TASK_0.9>'"
```

(Replace `<HCI_PATH_FROM_TASK_0.9>` with the path recorded in NOTES.md.)

### Task 2.2: Capture a navigation session

**Files:** None on disk yet.

- [ ] **Step 1: Bring phone + bike together**, ignition on, bike's BT enabled.

- [ ] **Step 2: Open Suzuki Connect, ensure paired/connected to bike** (you should see the cluster show "connected" or equivalent).

- [ ] **Step 3: Note wall-clock time** (write down or record on phone): this is `t=0`.

- [ ] **Step 4: Set a 5-minute destination** in Suzuki Connect (somewhere local with multiple turns — a route through 3-4 streets is ideal). Tap "Start navigation".

- [ ] **Step 5: Walk through the route on foot or drive it** (whichever is safer for the test). At each turn instruction the bike shows, note the wall-clock time and what was shown. Aim for at least: one TURN_LEFT, one TURN_RIGHT, one STRAIGHT/CONTINUE, one ARRIVED.

- [ ] **Step 6: Stop navigation in the app** when done. Note end wall-clock time.

### Task 2.3: Pull the snoop log

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/captures/nav-session-1-YYYYMMDD-HHMM.pcap`

- [ ] **Step 1: Pull the log**

```bash
cd ~/coding/projects/suzuki-connect-re/captures
STAMP=$(date +%Y%m%d-%H%M)
adb shell "su -c 'cat <HCI_PATH>' " > "nav-session-1-${STAMP}.btsnoop"
ls -la "nav-session-1-${STAMP}.btsnoop"
```

Expected: a file of non-trivial size (typically several hundred KB to a few MB for a 5-minute session).

- [ ] **Step 2: Convert to .pcap if needed**

btsnoop format is readable by Wireshark directly. Rename if you prefer:

```bash
mv "nav-session-1-${STAMP}.btsnoop" "nav-session-1-${STAMP}.pcap"
```

### Task 2.4: First-pass analysis in Wireshark

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/captures/nav-session-1-YYYYMMDD-HHMM.notes.md`

- [ ] **Step 1: Open in Wireshark**

```bash
wireshark "captures/nav-session-1-${STAMP}.pcap" &
```

- [ ] **Step 2: Filter to bike MAC**

In Wireshark filter bar:

```
bluetooth.addr == <BIKE_MAC>
```

(Use the MAC recorded in NOTES.md.)

- [ ] **Step 3: Identify ATT writes from phone to bike**

Add to filter:

```
bluetooth.addr == <BIKE_MAC> && btatt.opcode == 0x52
```

(0x52 = Write Command, 0x12 = Write Request — try both.)

Each row is one write the app sent to the bike. Look at the `btatt.handle` column and `btatt.value` column.

- [ ] **Step 4: Identify ATT notifications from bike to phone**

```
bluetooth.addr == <BIKE_MAC> && btatt.opcode == 0x1b
```

(0x1b = Handle Value Notification.)

- [ ] **Step 5: Record findings in `captures/nav-session-1-...notes.md`**

```markdown
# Nav Session 1 — YYYY-MM-DD HH:MM

## Wall-clock log
- HH:MM:SS — Suzuki Connect opened, paired with bike
- HH:MM:SS — Destination set: <street name>
- HH:MM:SS — Bike shows: TURN_LEFT 200m
- HH:MM:SS — Bike shows: TURN_RIGHT 50m
- HH:MM:SS — Bike shows: ARRIVED
- HH:MM:SS — Navigation stopped

## Wireshark observations
- Writes (phone -> bike) observed on handle(s): <hex handles>, mapping to char UUIDs: <UUIDs>
- Notifications (bike -> phone) observed on handle(s): <hex handles>, mapping to char UUIDs: <UUIDs>
- Approximate write payload sizes: <e.g., 16-32 bytes typical, 8 bytes for short>
- Notable byte patterns at-a-glance: <e.g., first byte always 0xA5, last 2 bytes look like a checksum>
```

### Task 2.5: Commit M2 artifacts

**Files:**
- The .pcap is gitignored (in captures/) — only the notes file is committed if it lives outside captures/. Move the notes file to docs.

- [ ] **Step 1: Move notes out of gitignored directory**

```bash
mkdir -p docs/captures
mv captures/nav-session-1-${STAMP}.notes.md docs/captures/
```

- [ ] **Step 2: Commit**

```bash
git add docs/captures/
git -c commit.gpgsign=false commit -m "feat(M2): first navigation capture + first-pass wire analysis notes"
```

---

## M3 — Frida Hooks on BLE Writes / Notifies

### Task 3.1: Verify Frida is connectable

**Files:** None.

- [ ] **Step 1: Confirm `frida-server` is running on phone**

```bash
adb shell "ps -A | grep frida-server"
```

Expected: one or more lines showing the process. If empty, re-run Task 0.7 Step 3.

- [ ] **Step 2: List Suzuki app process from laptop**

```bash
source ~/coding/projects/suzuki-connect-re/.venv/bin/activate
frida-ps -U | grep -i suzuki
```

Expected: shows the Suzuki Connect app's process name and PID (only when the app is running on the phone — open it first).

### Task 3.2: Write the minimal write-hook script

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/frida-scripts/hook-setvalue.js`

- [ ] **Step 1: Write the script**

```javascript
/* hook-setvalue.js — log every BluetoothGattCharacteristic.setValue(byte[]) call.
 * Attach: frida -U -n <APP_PKG> -l frida-scripts/hook-setvalue.js --no-pause
 */

Java.perform(function () {
    var Char = Java.use("android.bluetooth.BluetoothGattCharacteristic");

    Char.setValue.overload("[B").implementation = function (bytes) {
        var hex = bytesToHex(bytes);
        var uuid = this.getUuid().toString();
        console.log("[setValue [B] uuid=" + uuid + " len=" + bytes.length + " bytes=" + hex);
        return this.setValue(bytes);
    };

    function bytesToHex(arr) {
        var s = "";
        for (var i = 0; i < arr.length; i++) {
            var b = arr[i] & 0xff;
            if (b < 16) s += "0";
            s += b.toString(16);
        }
        return s;
    }

    console.log("[+] hook-setvalue.js attached");
});
```

- [ ] **Step 2: Sanity check syntax**

```bash
node --check ~/coding/projects/suzuki-connect-re/frida-scripts/hook-setvalue.js
```

Expected: no output (parse OK). If `node` isn't installed, skip — Frida will report syntax errors at attach time.

### Task 3.3: Attach the hook and re-run a short nav session

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/captures/frida-log-1-YYYYMMDD-HHMM.txt`

- [ ] **Step 1: Open Suzuki Connect on phone** (so the process exists).

- [ ] **Step 2: Attach Frida from laptop**

```bash
cd ~/coding/projects/suzuki-connect-re
source .venv/bin/activate
STAMP=$(date +%Y%m%d-%H%M)
frida -U -n "<APP_PKG>" -l frida-scripts/hook-setvalue.js --no-pause 2>&1 | tee "captures/frida-log-1-${STAMP}.txt"
```

(Replace `<APP_PKG>` with the package name recorded in NOTES.md.)

Expected: `[+] hook-setvalue.js attached`. Then nothing until the app does something.

- [ ] **Step 3: While Frida is attached, set a short destination in Suzuki Connect** and start navigation. Watch the laptop terminal — each write to the bike prints a `[setValue [B] uuid=... len=... bytes=...` line.

- [ ] **Step 4: Stop navigation. Ctrl-C the frida attachment.** Log is saved.

### Task 3.4: Extend hook to include notifications and stack traces

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/frida-scripts/hook-full.js`

- [ ] **Step 1: Write the extended hook**

```javascript
/* hook-full.js — log writes (setValue + writeCharacteristic) and notifications
 * (onCharacteristicChanged), each with a Java stack trace.
 * Attach: frida -U -n <APP_PKG> -l frida-scripts/hook-full.js --no-pause
 */

Java.perform(function () {
    var Char = Java.use("android.bluetooth.BluetoothGattCharacteristic");
    var Gatt = Java.use("android.bluetooth.BluetoothGatt");
    var Callback = Java.use("android.bluetooth.BluetoothGattCallback");
    var Throwable = Java.use("java.lang.Throwable");

    function ts() {
        return new Date().toISOString();
    }

    function hex(arr) {
        if (!arr) return "(null)";
        var s = "";
        for (var i = 0; i < arr.length; i++) {
            var b = arr[i] & 0xff;
            if (b < 16) s += "0";
            s += b.toString(16);
        }
        return s;
    }

    function trace(label) {
        var t = Throwable.$new();
        var frames = t.getStackTrace();
        console.log("  stack (" + label + "):");
        // skip top 2 frames (Throwable.<init>, our hook body)
        for (var i = 2; i < Math.min(frames.length, 10); i++) {
            console.log("    " + frames[i].toString());
        }
    }

    // Phone -> bike: setValue
    Char.setValue.overload("[B").implementation = function (bytes) {
        console.log("[" + ts() + "] WRITE.setValue uuid=" + this.getUuid().toString() +
                    " len=" + bytes.length + " bytes=" + hex(bytes));
        trace("setValue");
        return this.setValue(bytes);
    };

    // Phone -> bike: writeCharacteristic (the actual transport call)
    Gatt.writeCharacteristic.overload("android.bluetooth.BluetoothGattCharacteristic").implementation = function (ch) {
        var b = ch.getValue();
        console.log("[" + ts() + "] WRITE.writeCharacteristic uuid=" + ch.getUuid().toString() +
                    " bytes=" + hex(b));
        return this.writeCharacteristic(ch);
    };

    // Bike -> phone: onCharacteristicChanged (notification)
    Callback.onCharacteristicChanged.overload(
        "android.bluetooth.BluetoothGatt",
        "android.bluetooth.BluetoothGattCharacteristic"
    ).implementation = function (gatt, ch) {
        var b = ch.getValue();
        console.log("[" + ts() + "] NOTIFY uuid=" + ch.getUuid().toString() +
                    " bytes=" + hex(b));
        return this.onCharacteristicChanged(gatt, ch);
    };

    console.log("[+] hook-full.js attached");
});
```

- [ ] **Step 2: Test the extended hook**

Repeat Task 3.3 Steps 1-4 but with `hook-full.js`:

```bash
STAMP=$(date +%Y%m%d-%H%M)
frida -U -n "<APP_PKG>" -l frida-scripts/hook-full.js --no-pause 2>&1 | tee "captures/frida-log-full-${STAMP}.txt"
```

Run a navigation session in the app. Expected: WRITE and NOTIFY lines with stack traces showing which Suzuki app classes/methods called BLE.

- [ ] **Step 3: Identify the calling class chain**

From the stack traces, identify the highest-level Suzuki-namespaced class that calls into BLE (above all the framework noise). This class is the "BLE command builder" — likely contains the payload construction logic relevant to M4.

- [ ] **Step 4: Update NOTES.md** under a new section:

```markdown
## App-side BLE call chain (M3)

- Highest Suzuki-namespaced class that initiates writes: `<class.method>` (e.g., `com.suzuki.connect.ble.NavController.sendInstruction`)
- Calls into: BluetoothGattCharacteristic.setValue -> BluetoothGatt.writeCharacteristic
- Notify handler in Suzuki code: `<class.method>` (from onCharacteristicChanged stack)
```

### Task 3.5: Commit M3

**Files:**
- The frida log files are gitignored. Scripts and notes are tracked.

- [ ] **Step 1: Commit**

```bash
cd ~/coding/projects/suzuki-connect-re
git add frida-scripts/ NOTES.md
git -c commit.gpgsign=false commit -m "feat(M3): Frida hooks for BLE write/notify with stack traces"
```

---

## M4 — Encryption / Framing Layer

### Task 4.1: Time-align M2 pcap and M3 Frida log

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/docs/captures/m4-alignment-YYYYMMDD.md`

This task uses captures from M2 and M3, OR captures M2 + M3 simultaneously for clean alignment. The cleaner approach is to redo with simultaneous capture:

- [ ] **Step 1: Run M2's HCI snoop + M3's Frida hook concurrently**

In one terminal:

```bash
adb shell "su -c 'rm -f <HCI_PATH>'"
# enable HCI snoop fresh (toggle BT)
```

In a second terminal:

```bash
cd ~/coding/projects/suzuki-connect-re
source .venv/bin/activate
STAMP=$(date +%Y%m%d-%H%M)
frida -U -n "<APP_PKG>" -l frida-scripts/hook-full.js --no-pause 2>&1 | tee "captures/frida-aligned-${STAMP}.txt"
```

Now run a short navigation session.

After: pull the snoop log:

```bash
adb shell "su -c 'cat <HCI_PATH>'" > "captures/wire-aligned-${STAMP}.pcap"
```

- [ ] **Step 2: For one specific write, find the same payload in both logs**

Pick a `WRITE` line from the Frida log with a distinctive byte pattern (long, with a recognizable substring). Search Wireshark for the same bytes:

In Wireshark filter:

```
btatt.value contains <first 8-12 hex chars from Frida payload>
```

- [ ] **Step 3: Compare**

| Source | Bytes |
|--------|-------|
| Frida (app calls setValue with) | `<HEX>` |
| Wireshark (actually went over wire) | `<HEX>` |

Two possible outcomes:
- **Identical**: no encryption between app and radio. Skip to M5.
- **Different**: there is an encryption or framing layer between `setValue()` and the radio that we missed. Continue to Task 4.2.

- [ ] **Step 4: Record outcome in `docs/captures/m4-alignment-...md`**

### Task 4.2: (Only if encrypted) Find the cipher in JADX

**Files:** Read-only research.

- [ ] **Step 1: Walk back from setValue in the stack trace**

The Frida log's stack trace at WRITE time shows the call chain. Above the BluetoothGattCharacteristic.setValue line is the app's code path. Find the most-immediate Suzuki-namespaced caller.

- [ ] **Step 2: In JADX, open that caller method**

Look at the bytes argument: is it computed from another byte[] via:
- A call to `javax.crypto.Cipher.doFinal()` → AES (or DES, RC4 — read the cipher instance creation a few lines above)
- A loop with XOR? → trivial XOR cipher
- A call to a `Mac.doFinal()` → HMAC appended, not encryption
- A custom method like `obfuscate()`, `pack()`, `wrap()`? → custom byte-level transform

- [ ] **Step 3: Note the cipher and where it's instantiated**

Look at how the cipher was created: `Cipher.getInstance("AES/CBC/PKCS5Padding")` tells you mode + padding. Look at the `Cipher.init(...)` call to find the key and IV.

### Task 4.3: (Only if encrypted) Find the key derivation

**Files:** Read-only research.

- [ ] **Step 1: Trace the key**

The Cipher.init call takes a `SecretKey` and an `IvParameterSpec`. Find where the SecretKey object is constructed — usually a `SecretKeySpec(byte[] keyBytes, "AES")` somewhere.

- [ ] **Step 2: Trace `keyBytes`**

Where do these key bytes come from? Possibilities, from easy to hard:
- **Hardcoded byte array**: just read the literal. Done.
- **Derived from a static string**: usually `MessageDigest.getInstance("SHA-256")` or similar on a constant.
- **Derived from a bonding artifact**: bike serial, BLE MAC, account ID. Look for `getAddress()` calls or similar in the surrounding code.
- **Negotiated**: first few BLE writes in the session establish the key via a challenge-response. Find the initial handshake — usually preceded by a `connectGatt` and characterized by a back-and-forth of small payloads before normal traffic begins.

- [ ] **Step 3: Trace the IV similarly**

Often the IV is hardcoded, zero, or derived in the same way as the key.

- [ ] **Step 4: Use Frida to verify the key at runtime**

Add a hook on the cipher init to print the key bytes:

```javascript
Java.perform(function () {
    var SecretKeySpec = Java.use("javax.crypto.spec.SecretKeySpec");
    SecretKeySpec.$init.overload("[B", "java.lang.String").implementation = function (keyBytes, algo) {
        var hex = "";
        for (var i = 0; i < keyBytes.length; i++) {
            var b = keyBytes[i] & 0xff;
            if (b < 16) hex += "0";
            hex += b.toString(16);
        }
        console.log("[KEY] algo=" + algo + " bytes=" + hex);
        return this.$init(keyBytes, algo);
    };
});
```

Save as `frida-scripts/hook-keys.js` and run with the app. Expected: at least one `[KEY]` line during connection/session start.

- [ ] **Step 5: Update NOTES.md `Encryption / framing layer` section** with cipher, mode, key source, IV source, and (if key turned out to be static or derivable) the actual key.

### Task 4.4: (Only if encrypted) Write `tools/decrypt.py`

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/tools/decrypt.py`
- Create: `~/coding/projects/suzuki-connect-re/tests/test_decrypt.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_decrypt.py
"""Verify decrypt.py reproduces plaintext that matches the Frida-hooked
plaintext for the same wire ciphertext."""
import pytest
from tools.decrypt import decrypt

# One known (ciphertext_from_wire, plaintext_from_frida) pair captured
# during M4.1 alignment. Replace bytes with the actual aligned pair.
CIPHERTEXT_HEX = "<HEX FROM WIRESHARK>"
PLAINTEXT_HEX = "<HEX FROM FRIDA LOG, SAME WALL-CLOCK MOMENT>"


def test_decrypt_known_pair():
    ct = bytes.fromhex(CIPHERTEXT_HEX)
    expected_pt = bytes.fromhex(PLAINTEXT_HEX)
    assert decrypt(ct) == expected_pt
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd ~/coding/projects/suzuki-connect-re
source .venv/bin/activate
PYTHONPATH=. pytest tests/test_decrypt.py -v
```

Expected: FAIL with `ImportError: cannot import name 'decrypt' from 'tools.decrypt'` (because `tools/decrypt.py` doesn't exist yet).

- [ ] **Step 3: Write the implementation**

Template assuming AES-CBC. Adjust based on the cipher actually found in Task 4.2:

```python
# tools/decrypt.py
"""Decrypt Suzuki Connect BLE payloads.

Cipher details (per M4 findings in NOTES.md):
- Algorithm: AES/CBC/PKCS5Padding   <-- update if different
- Key source: (e.g., hardcoded, derived from MAC, etc.)
- IV source: (e.g., zero, hardcoded, per-message)
"""
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives import padding

KEY = bytes.fromhex("<HEX KEY DISCOVERED IN M4>")
IV = bytes.fromhex("<HEX IV DISCOVERED IN M4>")


def decrypt(ciphertext: bytes) -> bytes:
    """Decrypt one BLE payload. Returns plaintext bytes."""
    cipher = Cipher(algorithms.AES(KEY), modes.CBC(IV))
    decryptor = cipher.decryptor()
    padded = decryptor.update(ciphertext) + decryptor.finalize()
    unpadder = padding.PKCS7(128).unpadder()
    return unpadder.update(padded) + unpadder.finalize()


def encrypt(plaintext: bytes) -> bytes:
    """Encrypt one BLE payload. Inverse of decrypt(). Returns ciphertext."""
    padder = padding.PKCS7(128).padder()
    padded = padder.update(plaintext) + padder.finalize()
    cipher = Cipher(algorithms.AES(KEY), modes.CBC(IV))
    encryptor = cipher.encryptor()
    return encryptor.update(padded) + encryptor.finalize()
```

- [ ] **Step 4: Install crypto dependency**

```bash
pip install cryptography
pip freeze > /dev/null  # sanity
```

- [ ] **Step 5: Run test to verify it passes**

```bash
PYTHONPATH=. pytest tests/test_decrypt.py -v
```

Expected: PASS.

- [ ] **Step 6: Add 3-5 more (ciphertext, plaintext) pairs to the test** for resilience. Each test should reference a different wall-clock moment in the M4.1 aligned capture.

- [ ] **Step 7: Commit**

```bash
git add tools/decrypt.py tests/test_decrypt.py docs/captures/m4-alignment*.md NOTES.md
git -c commit.gpgsign=false commit -m "feat(M4): document encryption layer + Python decrypt/encrypt tools"
```

### Task 4.5: (If NOT encrypted) Document and skip

**Files:**
- Modify: `~/coding/projects/suzuki-connect-re/NOTES.md`

- [ ] **Step 1: Update NOTES.md `Encryption / framing layer` section**

```markdown
## Encryption / framing layer (M4)

No encryption observed between BluetoothGattCharacteristic.setValue() and the
BLE radio. The bytes passed into setValue() match exactly the bytes captured
in the HCI snoop log for the same wall-clock moment (see M4 alignment notes).

Framing layer: (any header/trailer bytes consistent across messages — note here)
```

- [ ] **Step 2: Commit**

```bash
git add NOTES.md
git -c commit.gpgsign=false commit -m "docs(M4): confirm no encryption layer; payloads are plaintext on wire"
```

---

## M5 — Decode the Navigation Instruction Protocol

### Task 5.1: Capture the diff matrix

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/captures/m5-matrix-*.{pcap,frida.txt}`

The goal is to capture payloads that vary in exactly ONE dimension at a time, to isolate which bytes encode what.

- [ ] **Step 1: Plan the matrix**

Write down a navigation matrix that covers, separately:
- Turn types: TURN_LEFT, TURN_RIGHT, U_TURN, SLIGHT_LEFT, SLIGHT_RIGHT, ROUNDABOUT (any exit), STRAIGHT, ARRIVED
- Distances: 10m, 100m, 500m, 1.0km, 5.0km (force the app to display each — set a destination that requires this initial distance to a turn)
- Street names: short (5 chars), medium (15 chars), long (40+ chars), with non-ASCII (Indian street names sometimes use Devanagari/Kannada)
- Special cases: rerouting (deviate from suggested route), off-route

- [ ] **Step 2: For each row in the matrix, capture a Frida log + HCI snoop** of the moment the app sends that specific instruction. This is tedious but necessary. Use very short navigation sessions for each.

Naming convention: `captures/m5-<dimension>-<value>-YYYYMMDD-HHMM.{pcap,frida.txt}`. E.g., `m5-turn-left-20260601-1430.frida.txt`.

- [ ] **Step 3: Decrypt each Frida log if M4 found encryption**, otherwise the Frida log is already plaintext. End result: for each matrix row, you have one plaintext payload (or a sequence) corresponding to the navigation instruction.

### Task 5.2: Isolate the message-type byte(s)

**Files:**
- Modify: `~/coding/projects/suzuki-connect-re/NOTES.md`

- [ ] **Step 1: Compare two plaintext payloads with everything constant except turn type**

E.g., `TURN_LEFT 100m` vs `TURN_RIGHT 100m` on the same street. The bytes that differ encode the turn type.

- [ ] **Step 2: Build the turn type table by repeating with each turn type**

```markdown
## Navigation message: turn_type field (M5)

Position: byte(s) <N> (zero-indexed)
Encoding: <e.g., single byte enum, or multi-byte>

| Turn type   | Value (hex) |
|-------------|-------------|
| STRAIGHT    | 0x01        |
| TURN_LEFT   | 0x02        |
| TURN_RIGHT  | 0x03        |
| ...         |             |
```

(Actual byte positions and values come from observation.)

### Task 5.3: Isolate the distance encoding

**Files:**
- Modify: `~/coding/projects/suzuki-connect-re/NOTES.md`

- [ ] **Step 1: Compare two plaintext payloads with same turn type, different distance**

E.g., `TURN_LEFT 100m` vs `TURN_LEFT 500m`. Differing bytes encode the distance.

- [ ] **Step 2: Determine encoding**

- Is it a single byte (max 255m)? Unlikely — needs more range.
- Two bytes little-endian uint16? Probably.
- Big-endian? Mappls is Indian, but the protocol may follow ARM little-endian convention.
- Units: meters? kilometers * 10? Force a 1.234km display and see what changes.

- [ ] **Step 3: Document in NOTES.md**

```markdown
## Navigation message: distance field (M5)

Position: byte(s) <N..M>
Encoding: <e.g., uint16 little-endian, unit=meters, max ~65km>
```

### Task 5.4: Isolate the street name encoding

**Files:**
- Modify: `~/coding/projects/suzuki-connect-re/NOTES.md`

- [ ] **Step 1: Compare payloads with same turn type and distance, different street names**

Likely structure: length-prefix byte (or two bytes), then UTF-8 bytes of the name. Or null-terminated. Or fixed-length padded.

- [ ] **Step 2: Check encoding**

Set a destination with a Devanagari-script street name (if local to your test area). If the bytes for that test show valid UTF-8 multi-byte sequences for Devanagari (`E0 A4 ...`), the encoding is UTF-8. If it's some lossy 8-bit Indian-locale encoding, document that.

- [ ] **Step 3: Document in NOTES.md** with byte positions, length-prefix encoding, character encoding.

### Task 5.5: Check for checksum / CRC

**Files:**
- Modify: `~/coding/projects/suzuki-connect-re/NOTES.md`

- [ ] **Step 1: Compare bytes of two payloads that differ in only one inner byte**

The last 1-4 bytes are the most likely checksum location.

- [ ] **Step 2: Hypothesis test**

For each candidate checksum algorithm (sum mod 256, CRC8, CRC16-CCITT, CRC16-MODBUS, CRC32), compute it over each payload's prefix (excluding the last N bytes) and check whether the computed value matches the last N bytes. Quick Python script:

```python
import zlib
for payload_hex in [...]:  # list of known good plaintext payloads
    b = bytes.fromhex(payload_hex)
    for tail_len in (1, 2, 4):
        body, tail = b[:-tail_len], b[-tail_len:]
        # CRC32
        crc32 = zlib.crc32(body) & 0xffffffff
        if tail_len == 4 and crc32.to_bytes(4, 'little') == tail:
            print("CRC32 LE matches")
        # add other algorithms similarly
```

- [ ] **Step 3: Document the checksum** (or absence of one) in NOTES.md.

### Task 5.6: Write the encoder + decoder (TDD)

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/tools/encoder.py`
- Create: `~/coding/projects/suzuki-connect-re/tools/decoder.py`
- Create: `~/coding/projects/suzuki-connect-re/tests/test_encoder.py`
- Create: `~/coding/projects/suzuki-connect-re/tests/test_decoder.py`
- Create: `~/coding/projects/suzuki-connect-re/tests/test_round_trip.py`
- Create: `~/coding/projects/suzuki-connect-re/tests/fixtures/known_payloads.py`

- [ ] **Step 1: Create the fixtures file with known (structured, bytes) pairs**

```python
# tests/fixtures/known_payloads.py
"""Known (structured, payload_bytes_hex) pairs collected in M5.

Each pair is a single navigation instruction captured via Frida (plaintext)
with the corresponding structured interpretation."""

from dataclasses import dataclass

@dataclass
class NavInstruction:
    turn_type: str           # e.g., "TURN_LEFT"
    distance_m: int          # meters
    street_name: str

# Add 5-10 pairs collected from the M5.1 matrix.
KNOWN_PAIRS = [
    (
        NavInstruction(turn_type="TURN_LEFT", distance_m=200, street_name="MG Road"),
        "<HEX PAYLOAD CAPTURED>",
    ),
    # ... more pairs
]
```

- [ ] **Step 2: Write the failing encoder test**

```python
# tests/test_encoder.py
import pytest
from tools.encoder import encode
from tests.fixtures.known_payloads import KNOWN_PAIRS


@pytest.mark.parametrize("structured,expected_hex", KNOWN_PAIRS)
def test_encode_matches_known_payload(structured, expected_hex):
    expected = bytes.fromhex(expected_hex)
    assert encode(structured) == expected
```

- [ ] **Step 3: Run test, verify it fails**

```bash
cd ~/coding/projects/suzuki-connect-re
source .venv/bin/activate
PYTHONPATH=. pytest tests/test_encoder.py -v
```

Expected: FAIL with ImportError on `tools.encoder`.

- [ ] **Step 4: Write the encoder**

Template based on the schema discovered in M5.2-M5.5. Adjust positions, encodings, and the checksum function to match what NOTES.md says.

```python
# tools/encoder.py
"""Encode structured navigation instructions to Suzuki BLE wire bytes.

Schema (per M5 findings in NOTES.md):
- Byte(s) for message header: <as discovered>
- Byte(s) for turn_type: <as discovered>
- Byte(s) for distance: <as discovered>
- Byte(s) for street_name: <as discovered>
- Trailing checksum: <as discovered>
"""
from tests.fixtures.known_payloads import NavInstruction

TURN_TYPE_TO_BYTE = {
    # Populate from M5.2 table. Example:
    "STRAIGHT":     0x01,
    "TURN_LEFT":    0x02,
    "TURN_RIGHT":   0x03,
    # ...
}


def encode(inst: NavInstruction) -> bytes:
    parts = []
    # Header byte(s)
    parts.append(bytes([0xA5]))  # placeholder; replace with actual header from M5
    # Turn type
    parts.append(bytes([TURN_TYPE_TO_BYTE[inst.turn_type]]))
    # Distance (uint16 little-endian, meters) — adjust per M5.3
    parts.append(inst.distance_m.to_bytes(2, "little"))
    # Street name (length-prefixed UTF-8) — adjust per M5.4
    name_bytes = inst.street_name.encode("utf-8")
    parts.append(bytes([len(name_bytes)]))
    parts.append(name_bytes)
    body = b"".join(parts)
    # Checksum (e.g., CRC16-CCITT) — adjust per M5.5
    cksum = _crc16_ccitt(body)
    return body + cksum.to_bytes(2, "little")


def _crc16_ccitt(data: bytes, init: int = 0xFFFF) -> int:
    crc = init
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) if (crc & 0x8000) else (crc << 1)
            crc &= 0xFFFF
    return crc
```

- [ ] **Step 5: Run test, verify it passes**

```bash
PYTHONPATH=. pytest tests/test_encoder.py -v
```

Expected: PASS on all parametrized cases. If a case fails, the schema in NOTES.md (or this encoder) is wrong — debug by comparing produced bytes to expected bytes, isolate the differing byte position, revisit the relevant M5.X task.

- [ ] **Step 6: Write the decoder test**

```python
# tests/test_decoder.py
import pytest
from tools.decoder import decode
from tests.fixtures.known_payloads import KNOWN_PAIRS


@pytest.mark.parametrize("structured,payload_hex", KNOWN_PAIRS)
def test_decode_matches_known_structured(structured, payload_hex):
    payload = bytes.fromhex(payload_hex)
    assert decode(payload) == structured
```

- [ ] **Step 7: Run, verify failing, then implement decoder**

```python
# tools/decoder.py
"""Decode Suzuki BLE wire bytes to structured navigation instructions."""
from tests.fixtures.known_payloads import NavInstruction
from tools.encoder import TURN_TYPE_TO_BYTE, _crc16_ccitt

BYTE_TO_TURN_TYPE = {v: k for k, v in TURN_TYPE_TO_BYTE.items()}


class ChecksumError(ValueError):
    pass


def decode(payload: bytes) -> NavInstruction:
    body, cksum_bytes = payload[:-2], payload[-2:]
    cksum = int.from_bytes(cksum_bytes, "little")
    if cksum != _crc16_ccitt(body):
        raise ChecksumError(f"checksum mismatch: payload={payload.hex()}")

    # Adjust offsets to match schema in NOTES.md.
    header = body[0]
    if header != 0xA5:
        raise ValueError(f"unexpected header byte 0x{header:02x}")
    turn_type_byte = body[1]
    distance = int.from_bytes(body[2:4], "little")
    name_len = body[4]
    street_name = body[5:5 + name_len].decode("utf-8")

    return NavInstruction(
        turn_type=BYTE_TO_TURN_TYPE[turn_type_byte],
        distance_m=distance,
        street_name=street_name,
    )
```

```bash
PYTHONPATH=. pytest tests/test_decoder.py -v
```

Expected: PASS.

- [ ] **Step 8: Write the round-trip test**

```python
# tests/test_round_trip.py
import pytest
from tools.encoder import encode
from tools.decoder import decode
from tests.fixtures.known_payloads import KNOWN_PAIRS, NavInstruction


@pytest.mark.parametrize("structured,_hex", KNOWN_PAIRS)
def test_round_trip(structured, _hex):
    assert decode(encode(structured)) == structured


def test_round_trip_synthetic():
    cases = [
        NavInstruction("TURN_LEFT", 50, "X"),
        NavInstruction("TURN_RIGHT", 12345, "Some Long Street Name Here"),
        NavInstruction("ARRIVED", 0, ""),
    ]
    for c in cases:
        assert decode(encode(c)) == c
```

```bash
PYTHONPATH=. pytest tests/ -v
```

Expected: ALL tests PASS.

- [ ] **Step 9: Commit**

```bash
git add tools/encoder.py tools/decoder.py tests/test_encoder.py tests/test_decoder.py tests/test_round_trip.py
git -c commit.gpgsign=false commit -m "feat(M5): nav instruction encoder + decoder + round-trip tests"
```

### Task 5.7: Write `tools/transcript.py` for Gate 1

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/tools/transcript.py`

- [ ] **Step 1: Write the transcript tool**

```python
#!/usr/bin/env python3
"""Read a pcap of a Suzuki nav session and emit a human-readable transcript.

Usage: python tools/transcript.py <captures/nav-session.pcap>

Approach:
- Read the pcap via pyshark (Wireshark Python wrapper)
- Filter to ATT writes on the navigation characteristic UUID
- For each, decrypt (if M4 found encryption) then decode (M5 encoder/decoder)
- Print timestamped lines
"""
import argparse
import sys

import pyshark

from tools.decoder import decode
try:
    from tools.decrypt import decrypt
    DECRYPT = decrypt
except ImportError:
    DECRYPT = lambda b: b  # no encryption layer

NAV_WRITE_HANDLE_HEX = "<HEX HANDLE FROM M2>"  # from Wireshark, e.g., "0x002a"


def transcript(pcap_path: str) -> None:
    cap = pyshark.FileCapture(pcap_path, display_filter="btatt.opcode == 0x52")
    for pkt in cap:
        try:
            handle = pkt.btatt.handle
            value_hex = pkt.btatt.value.replace(":", "")
            ts = pkt.sniff_time.isoformat()
        except AttributeError:
            continue
        if handle.lower() != NAV_WRITE_HANDLE_HEX.lower():
            continue
        try:
            plaintext = DECRYPT(bytes.fromhex(value_hex))
            inst = decode(plaintext)
            print(f"[{ts}] TX nav.instruction type={inst.turn_type} distance={inst.distance_m}m street={inst.street_name!r}")
        except Exception as e:
            print(f"[{ts}] TX ??? raw={value_hex} (decode error: {e})")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("pcap")
    args = parser.parse_args()
    transcript(args.pcap)


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Install pyshark**

```bash
pip install pyshark
```

- [ ] **Step 3: Run on the M2 capture to verify Gate 1**

```bash
cd ~/coding/projects/suzuki-connect-re
source .venv/bin/activate
PYTHONPATH=. python tools/transcript.py captures/nav-session-1-*.pcap
```

Expected: lines like
```
[2026-06-15T10:02:13.456000] TX nav.instruction type=TURN_LEFT distance=420 street='MG Road'
```

Matching the wall-clock log from Task 2.4 Step 5.

- [ ] **Step 4: Commit**

```bash
git add tools/transcript.py
git -c commit.gpgsign=false commit -m "feat(M5): pcap-to-transcript tool (satisfies Gate 1)"
```

---

## M6 — Non-Navigation Display Protocol

### Task 6.1: Enumerate non-nav app actions

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/docs/m6-action-matrix.md`

- [ ] **Step 1: List every interaction in Suzuki Connect that changes anything on the bike display**

Examples to check:
- Starting/ending a "trip" in the app
- Connecting/disconnecting the BLE session
- Phone notification mirror (call/SMS) — if app supports
- Settings: unit changes (km/mi), time format, brightness
- Receiving a call while connected
- Sending location/POI without starting full navigation
- App backgrounded vs foregrounded

- [ ] **Step 2: Tabulate**

```markdown
# M6 Action Matrix

| Action | Expected bike display change | Captured? |
|--------|------------------------------|-----------|
| Connect to bike | "Connected" icon | |
| Start trip | Trip recording indicator | |
| End trip | Indicator off | |
| ... | | |
```

### Task 6.2: Capture each non-nav action

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/captures/m6-<action>-*.{pcap,frida.txt}`

- [ ] **Step 1: For each action in the matrix**, do one short capture (HCI snoop + Frida) where the ONLY thing happening is that action. Keep the session short and clean.

- [ ] **Step 2: Decrypt and decode each captured payload** with the M5 tools. If a payload fails to decode with the nav schema, that confirms it's a different message type — record raw bytes.

### Task 6.3: Classify the non-nav messages

**Files:**
- Modify: `~/coding/projects/suzuki-connect-re/NOTES.md`

- [ ] **Step 1: Group by header byte / message type**

Probably the first byte (or two) of the payload distinguishes message type. Group captures accordingly.

- [ ] **Step 2: For each group, infer the schema**

Diff payloads within a group across actions, find what varies. Document.

- [ ] **Step 3: Update NOTES.md `Non-navigation commands` section** with a table of message types, their schemas, and what app action triggers each.

### Task 6.4: Determine display engine type

**Files:**
- Modify: `~/coding/projects/suzuki-connect-re/NOTES.md`

- [ ] **Step 1: Examine the full set of M5 + M6 messages collectively**

Two questions:
- Are there commands that include bitmap or framebuffer-shaped data (large blobs of "image-y" bytes)? If yes: display can accept arbitrary graphics.
- Are all visual outputs (icons, indicators) selected by small enum bytes? If yes: display is a fixed-icon engine.

- [ ] **Step 2: Write the verdict in NOTES.md**

```markdown
## Display engine type (M6 — informs Phase 3 Branch A)

Verdict: <FIXED_ICON | FRAMEBUFFER | HYBRID>

Evidence:
- ...
- ...

Implications for Phase 3 Branch A:
- ...
```

- [ ] **Step 3: Commit M6**

```bash
git add NOTES.md docs/m6-action-matrix.md
git -c commit.gpgsign=false commit -m "feat(M6): non-nav command catalog + display engine verdict"
```

---

## M7 — Passive Telemetry & Diagnostic Surface

### Task 7.1: Write the telemetry subscriber

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/tools/telemetry_sub.py`

- [ ] **Step 1: Write the script**

```python
#!/usr/bin/env python3
"""Subscribe to every NOTIFY-capable characteristic on the bike that is
NOT app-used (per M1). Log every notification with timestamp + bike state
annotation (entered manually).

NEVER WRITES. Passive only.

Usage:
    python tools/telemetry_sub.py --address <BIKE_MAC> --unused-chars-file <FILE>

Where <FILE> is a text file with one UUID per line (the M1 `unused`
characteristics that have the `notify` property).
"""
import argparse
import asyncio
import sys
from datetime import datetime

from bleak import BleakClient


def make_handler(uuid: str):
    def _handler(sender, data: bytearray) -> None:
        print(f"[{datetime.now().isoformat()}] NOTIFY {uuid} len={len(data)} bytes={data.hex()}")
    return _handler


async def subscribe_all(address: str, uuids: list[str]) -> None:
    async with BleakClient(address) as client:
        print(f"Connected to {address}. MTU={client.mtu_size}")
        for uuid in uuids:
            try:
                await client.start_notify(uuid, make_handler(uuid))
                print(f"  subscribed {uuid}")
            except Exception as e:
                print(f"  FAILED {uuid}: {e}")
        print("\nSubscribed. Ctrl-C to stop.\n")
        try:
            while True:
                await asyncio.sleep(60)
        except KeyboardInterrupt:
            pass


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--address", required=True)
    parser.add_argument("--unused-chars-file", required=True)
    args = parser.parse_args()

    with open(args.unused_chars_file) as f:
        uuids = [line.strip() for line in f if line.strip() and not line.startswith("#")]
    print(f"Subscribing to {len(uuids)} characteristics...")
    asyncio.run(subscribe_all(args.address, uuids))


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Create the UUID list file from M1**

```bash
cd ~/coding/projects/suzuki-connect-re
# Edit by hand from NOTES.md — list every unused characteristic UUID with 'notify' in its properties
cat > tools/unused_notify_chars.txt <<'EOF'
# UUIDs of unused notify-capable characteristics, one per line.
# Populated from NOTES.md `BLE GATT tree` after M1.
EOF
# Add lines manually based on NOTES.md
```

- [ ] **Step 3: Sanity check**

```bash
source .venv/bin/activate
python tools/telemetry_sub.py --help
```

Expected: argparse help.

### Task 7.2: Run the stimulation matrix

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/captures/m7-telemetry-*.txt`

This must be done with **Suzuki Connect app NOT connected to the bike** (because only one Central at a time can connect, and we want our subscriber to be the active one). Disconnect or close the app first.

- [ ] **Step 1: Connect laptop to bike (Suzuki Connect closed/disconnected on phone)**

```bash
cd ~/coding/projects/suzuki-connect-re
source .venv/bin/activate
STAMP=$(date +%Y%m%d-%H%M)
python tools/telemetry_sub.py --address <BIKE_MAC> --unused-chars-file tools/unused_notify_chars.txt 2>&1 | tee captures/m7-telemetry-${STAMP}.txt
```

Expected: "subscribed <uuid>" lines for each one, then "Subscribed. Ctrl-C to stop."

- [ ] **Step 2: Stimulate the bike across these states, noting wall-clock time per state**

Bike state matrix (target 30-60 seconds per state):
- **A**: Ignition ON, engine OFF (idle radio, basic telemetry only)
- **B**: Engine running, in neutral, idle
- **C**: Engine running, throttle blip to 4-5k RPM (in neutral — safe)
- **D**: Riding at slow speed (10-20 km/h) in 1st gear (if possible/safe — could be done by rolling on a side stand alternative, or doing this on a closed area)
- **E**: Hard braking
- **F**: Triggering a known fault: side-stand down while engine running and in gear

Skip any state that requires unsafe conditions for testing.

- [ ] **Step 3: Ctrl-C to stop subscriber. The log file in `captures/` now has all notifications across all states.**

### Task 7.3: Correlate streams to bike state

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/docs/m7-correlation.md`

- [ ] **Step 1: For each subscribed UUID, extract its notification stream from the log**

```bash
grep "NOTIFY <UUID> " captures/m7-telemetry-*.txt > /tmp/uuid-stream.txt
```

- [ ] **Step 2: For each stream, check correlation against state matrix wall-clock times**

For each characteristic:
- Did the value change between states A and B (engine off vs on)? → likely engine-related
- Did the value spike during state C (throttle blip)? → RPM or throttle position
- Did the value change with state D (motion)? → speed or distance counter
- Did it spike during E (braking)? → brake sensor or G-force
- Did it surge during F (fault triggered)? → diagnostic code

- [ ] **Step 3: Document hypothesis per characteristic**

```markdown
# M7 Correlation Results

## UUID 0000aaaa-...
- Property: notify
- Value during A: 0x00 0x00 (constant)
- Value during B: 0x01 0x52 0x00 (one byte varying)
- Value during C: 0x01 0xff 0x00 (second byte ramps to 0xff during throttle blip)
- Hypothesis: byte 1 = engine state (0 off, 1 on), byte 2 = throttle position (0-255)

## UUID 0000bbbb-...
- ...
```

### Task 7.4: Update NOTES.md with the telemetry surface

**Files:**
- Modify: `~/coding/projects/suzuki-connect-re/NOTES.md`

- [ ] **Step 1: Fill the `Telemetry & diagnostic surface` section**

```markdown
## Telemetry & diagnostic surface (M7)

| UUID | Properties | Update freq | Category | Inferred unit | Hypothesis |
|------|-----------|-------------|----------|---------------|-----------|
| 0000aaaa-... | notify | ~5 Hz when engine on | engine | byte | engine_state + throttle_position |
| 0000bbbb-... | notify | per gear change | drivetrain | byte | gear_position (1-6, 0=N) |
| ... | | | | | |

### Unknowns
- UUID 0000xxxx-... — notifies sporadically with 8-byte payloads, no correlation found across any test state

### Phase 3 Branch B feasibility note
<Brief assessment: is there enough rich data to build a useful dashboard?>
```

- [ ] **Step 2: Commit M7**

```bash
cd ~/coding/projects/suzuki-connect-re
git add tools/telemetry_sub.py tools/unused_notify_chars.txt docs/m7-correlation.md NOTES.md
git -c commit.gpgsign=false commit -m "feat(M7): passive telemetry subscriber + bike state correlation map"
```

---

## Gates — Phase 1 Exit Criteria

### Gate 1: Decode any captured navigation session

**Files:** None new. Reuse `tools/transcript.py`.

- [ ] **Step 1: Capture a NEW navigation session** (different from M2's capture, different destination, ideally a longer route with rerouting if possible). Save as `captures/gate1-unseen-YYYYMMDD-HHMM.pcap`.

- [ ] **Step 2: Run transcript.py against it**

```bash
cd ~/coding/projects/suzuki-connect-re
source .venv/bin/activate
PYTHONPATH=. python tools/transcript.py captures/gate1-unseen-*.pcap > /tmp/gate1-transcript.txt
cat /tmp/gate1-transcript.txt
```

- [ ] **Step 3: Verify the transcript matches the actual ride**

Read through the output. Each line should make sense given what the bike displayed during the ride. Specifically check:
- At least one of each turn type observed
- Distance values decrease as the bike approaches turns
- ARRIVED appears at the right wall-clock time
- No `???` lines (would indicate a decode failure on a real-world payload)

Pass criterion: transcript is intelligible and matches reality.

### Gate 2: Send a valid nav message from a third-party client

**Files:**
- Create: `~/coding/projects/suzuki-connect-re/tools/send_test_nav.py`

- [ ] **Step 1: Write the test sender**

```python
#!/usr/bin/env python3
"""Connect to the bike from a third-party client and send a single
navigation instruction. Proof-of-life for Gate 2.

Usage: python tools/send_test_nav.py --address <BIKE_MAC>
"""
import argparse
import asyncio

from bleak import BleakClient

from tools.encoder import encode
from tests.fixtures.known_payloads import NavInstruction
try:
    from tools.decrypt import encrypt
    POST_PROCESS = encrypt
except ImportError:
    POST_PROCESS = lambda b: b  # no encryption layer

NAV_WRITE_CHAR_UUID = "<NAV_WRITE_CHAR_UUID FROM NOTES.MD>"


async def send(address: str) -> None:
    inst = NavInstruction(turn_type="TURN_LEFT", distance_m=300, street_name="Test St")
    payload = POST_PROCESS(encode(inst))
    print(f"Sending: {inst}")
    print(f"Bytes:   {payload.hex()}")

    async with BleakClient(address) as client:
        print(f"Connected to {address}. MTU={client.mtu_size}")
        await client.write_gatt_char(NAV_WRITE_CHAR_UUID, payload, response=False)
        print("Written. Look at the bike's cluster display.")
        # Hold the connection a bit so the bike processes the write
        await asyncio.sleep(5)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--address", required=True)
    args = parser.parse_args()
    asyncio.run(send(args.address))


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Ensure Suzuki Connect app is NOT connected** to the bike (close the app or disable BT on the phone).

- [ ] **Step 3: Run the sender**

```bash
cd ~/coding/projects/suzuki-connect-re
source .venv/bin/activate
PYTHONPATH=. python tools/send_test_nav.py --address <BIKE_MAC>
```

- [ ] **Step 4: Verify the bike's cluster display shows "Turn Left 300m Test St"** (or whatever the bike's actual rendering of that instruction looks like).

Pass criterion: bike's cluster renders an intelligible turn instruction matching what we sent.

If FAIL with a connection error: bike requires authentication beyond simple bonding. Spawn sub-milestone M4.5 to RE the auth handshake (separate task list — capture the handshake via HCI snoop during a clean app re-connection, find the auth code in JADX, replay).

If FAIL with the bike not displaying anything: write went through but payload was rejected. Likely cause: missing framing byte, wrong checksum algorithm, wrong target characteristic. Re-check M5 schema; verify the write actually reached the bike by capturing simultaneously with HCI snoop.

- [ ] **Step 5: Commit**

```bash
git add tools/send_test_nav.py
git -c commit.gpgsign=false commit -m "feat(Gate2): third-party nav message sender + proof-of-life"
```

### Gate 3: NOTES.md is implementable cold

**Files:** None new.

- [ ] **Step 1: In a clean conversation (different Claude session)**, hand a future agent ONLY:
  - `NOTES.md`
  - `docs/ble-primer.md`
  - The spec at `docs/superpowers/specs/2026-05-23-phase1-protocol-understanding-design.md`

NOT given: any tool source, captures, or this plan.

- [ ] **Step 2: Ask the future agent to write an encoder for nav instructions from those documents alone.**

- [ ] **Step 3: Compare the agent's output to `tools/encoder.py`.**

Pass criterion: output is structurally equivalent (handles all turn types, distance encoding, street name, checksum). If the agent needs to ask any clarifying question, that question identifies a gap — fill the gap in NOTES.md and re-run the test.

### Gate 4: Display engine type known

**Files:** None new.

- [ ] **Step 1: Read NOTES.md `Display engine type` section** (filled in Task 6.4).

Pass criterion: the verdict is one of `FIXED_ICON`, `FRAMEBUFFER`, `HYBRID`, with evidence and Phase 3 Branch A implications stated.

### Gate 5: Full BLE surface enumerated and categorized

**Files:** None new.

- [ ] **Step 1: Read NOTES.md `BLE GATT tree → Characteristics` table** (filled in M1) and `Telemetry & diagnostic surface` section (filled in M7).

Pass criterion: every characteristic the bike advertises is in the table with an `app-used` tag. Every `app-used: no` characteristic that supports notify has been subscribed in M7 and has either a hypothesis or is explicitly listed in the `Unknowns` subsection.

### Final commit

- [ ] **Step 1: Update README "Status" section** to reflect Phase 1 complete.

- [ ] **Step 2: Final Phase 1 commit**

```bash
cd ~/coding/projects/suzuki-connect-re
git add README.md
git -c commit.gpgsign=false commit -m "chore: mark Phase 1 complete (all 5 gates passed)"
git log --oneline
git tag phase-1-complete
```

---

## Notes on Execution

- **Sessions are interactive**: many tasks need Arjun at the bike (the captures and gate tests) and many need Arjun at the laptop. Plan sessions around physical access.
- **Order matters within a milestone, less between milestones**: M0 must complete first; M1 before M2/M3 (need UUIDs and MAC); M4 must run after M2+M3 to compare; M5 needs M4 output. M6 and M7 can run in parallel (different threads of activity, different captures).
- **`captures/` is the working area, `docs/` is the deliverable**. Anything that survives to Phase 2 lives in `NOTES.md`, `tools/`, `frida-scripts/`, or `docs/`. Captures are scratch.
- **Don't rush M5**: a flaky encoder means everything in Phase 2 is flaky. Add more (structured, bytes) fixture pairs whenever in doubt. Aim for 15+ pairs across diverse inputs.
- **Safety rule**: writes to unknown characteristics are FORBIDDEN in Phase 1 (per spec Non-Goals). This plan has zero write operations to characteristics that aren't `<NAV_WRITE_CHAR_UUID>` (a known, app-used write target).
