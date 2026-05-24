"""
forge_display — Phase 3 Branch A: write arbitrary frames to the bike's cluster.

This is the "make the cluster show whatever I want" tool. Builds forged a531
(nav), a533 (heartbeat), or a536 (identity) frames via tools/protocol.py and
writes them to the bike's 0xFFF1 write characteristic in a continuous loop
matching the official app's ~2.7 Hz cadence (so the cluster doesn't revert to
idle between sends).

Built and unit-tested without the bike; just plug in to demo.

PREREQUISITES:
  - Bike powered on (BLE radio active)
  - Suzuki Connect app NOT currently connected to the bike (toggle phone
    Bluetooth OFF, or close the app fully). Otherwise our writes compete
    with the app's and the cluster sees alternating frames.
  - Bike's BLE MAC known (in LOCAL_NOTES.md) or its advertised name
    matches the SBM* default pattern.

USAGE:
  # Send an arrow + distance + ETA as if you were navigating:
  python tools/forge_display.py nav \\
      --arrow 8 --dist-next 0150 --eta '0830PM' --dist-total 0500 \\
      --duration 30

  # Shortcut: lay out 14 chars of text across the 3 visible text fields:
  python tools/forge_display.py text 'HELL' 'OARJUN' '!!!!' --duration 30

  # Forge an a533 heartbeat with custom phone-battery + weather + temp:
  python tools/forge_display.py heartbeat \\
      --battery '0' --charging Y --weather 7 --temp-c -10 --duration 30

  # Forge an a536 identity with a custom name (cluster shows on welcome):
  python tools/forge_display.py identity 'CUSTOM_USER'

  # All subcommands accept --mac / --name / --duration / --interval.

VERIFY (without bike): unit tests in tests/test_forge_display.py exercise
every CLI path and round-trip each forged frame through protocol.decode()
to confirm validity.
"""

import argparse
import asyncio
import os
import re
import sys
from datetime import datetime
from math import ceil
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from protocol import (  # noqa: E402
    HeartbeatFrame,
    IdentityFrame,
    NavFrame,
    decode,
    is_well_formed,
)

SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"
WRITE_CHAR_UUID = "0000fff1-0000-1000-8000-00805f9b34fb"
DEFAULT_NAME_PATTERN = r"^SBM[A-Z0-9]+$"
DEFAULT_INTERVAL = 0.4  # seconds; matches app's ~2.7 Hz cadence
DEFAULT_DURATION = 60.0


# ---------------------------------------------------------------------------
# Frame builders — pure functions, fully unit-testable.
# ---------------------------------------------------------------------------


def _now_hhmma() -> str:
    """Current wall-clock time as 6-char HHMMAA (e.g. '0830PM')."""
    return datetime.now().strftime("%I%M%p")  # already 6 chars


def build_nav(
    arrow: int,
    dist_next: str,
    dist_next_unit: str,
    eta: str,
    dist_total: str,
    dist_total_unit: str,
    status: str,
    continue_flag: str,
) -> bytes:
    """Construct + encode a forged a531 NAV frame. Pads/truncates strings."""
    frame = NavFrame(
        maneuver_id=arrow & 0xFF,
        dist_next=str(dist_next)[:4].ljust(4, "0"),
        dist_next_unit=(dist_next_unit or "M")[:1].upper(),
        eta=str(eta)[:6].ljust(6, "0"),
        dist_total=str(dist_total)[:4].ljust(4, "0"),
        dist_total_unit=(dist_total_unit or "M")[:1].upper(),
        status=(status or "1")[:1],
        continue_flag=(continue_flag or "1")[:1],
    )
    return frame.encode()


def build_text(slot1: str, slot2: str, slot3: str, arrow: int = 8) -> bytes:
    """Lay out up to 4+6+4 ASCII chars across the a531 dist_next/eta/dist_total
    text fields. Result is a frame the bike will render as if it were a nav step,
    but the 'distance' and 'ETA' areas show your text instead.
    """
    return build_nav(
        arrow=arrow,
        dist_next=slot1,
        dist_next_unit="M",
        eta=slot2,
        dist_total=slot3,
        dist_total_unit="M",
        status="1",
        continue_flag="1",
    )


def build_heartbeat(
    battery: str = "3",
    charging: str = "Y",
    speed: str = "000",
    signal: str = "3",
    sms_pending: str = "N",
    call_pending: str = "N",
    weather: int = 1,
    temp_c: float = 25.0,
) -> bytes:
    """Construct + encode an a533 heartbeat with the specified fields."""
    # Convert Celsius to byte-22 encoding: byte = (int)(115 + ceil(9C/5 + 32))
    temp_f_plus_115 = int(ceil((9.0 * temp_c) / 5.0 + 32.0)) + 115
    temp_f_plus_115 = max(0, min(255, temp_f_plus_115))
    frame = HeartbeatFrame(
        battery_bucket=battery[:1],
        charging=charging[:1],
        speed_str=str(speed)[:3],
        signal_status=signal[:1],
        time_hhmmss=datetime.now().strftime("%I%M%S"),
        sms_pending=sms_pending[:1],
        call_pending=call_pending[:1],
        weather=weather & 0xFF,
        temp_f_plus_115=temp_f_plus_115 & 0xFF,
        tail_const=0x01,
    )
    return frame.encode()


def build_identity(name: str, is_fresh: bool = False) -> bytes:
    """Construct + encode an a536 identity frame with the specified name."""
    return IdentityFrame(name=name, is_fresh=is_fresh).encode()


# ---------------------------------------------------------------------------
# BLE I/O — only this code path needs the bike.
# ---------------------------------------------------------------------------


async def _resolve_target(mac: str | None, name_pattern: str) -> str:
    """Return a BLE MAC. Prefer --mac CLI arg, then SUZUKI_BIKE_MAC env var,
    then scan by name pattern.
    """
    if mac:
        return mac
    env = os.environ.get("SUZUKI_BIKE_MAC")
    if env:
        return env
    from bleak import BleakScanner
    print(f"Scanning 8s for devices matching /{name_pattern}/ ...", flush=True)
    devices = await BleakScanner.discover(timeout=8.0, return_adv=True)
    matches = []
    for addr, (d, adv) in devices.items():
        nm = d.name or adv.local_name or ""
        if nm and re.search(name_pattern, nm):
            matches.append((addr, nm))
    if not matches:
        raise SystemExit(f"No device matched /{name_pattern}/")
    if len(matches) > 1:
        raise SystemExit(f"Multiple matches: {matches} — pass --mac")
    print(f"Selected {matches[0][0]} (name={matches[0][1]!r})", flush=True)
    return matches[0][0]


async def _send_loop(mac: str, frame_factory, interval: float, duration: float) -> int:
    """Connect to `mac`, write the frame produced by `frame_factory()` to 0xFFF1
    every `interval` seconds for `duration` seconds total. Returns the number of
    successful writes.
    """
    from bleak import BleakClient
    sent = 0
    print(f"Connecting to {mac} ...", flush=True)
    async with BleakClient(mac) as client:
        # Sanity-check the GATT structure
        svc_uuids = {s.uuid.lower() for s in client.services}
        if SERVICE_UUID not in svc_uuids:
            raise SystemExit(f"Bike doesn't expose {SERVICE_UUID}; got {svc_uuids}")
        print(f"Connected. Writing every {interval:.2f}s for {duration:.1f}s ...", flush=True)
        deadline = asyncio.get_event_loop().time() + duration
        while asyncio.get_event_loop().time() < deadline:
            payload = frame_factory()
            await client.write_gatt_char(WRITE_CHAR_UUID, payload, response=False)
            sent += 1
            if sent <= 3 or sent % 25 == 0:
                print(f"  [{sent}] tx: {payload.hex()}", flush=True)
            await asyncio.sleep(interval)
    print(f"\nDone. {sent} writes.", flush=True)
    return sent


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def _add_common(p: argparse.ArgumentParser) -> None:
    p.add_argument("--mac", help="Bike BLE MAC (else env SUZUKI_BIKE_MAC, else name scan).")
    p.add_argument("--name", default=DEFAULT_NAME_PATTERN,
                   help=f"Name regex for scan fallback (default: {DEFAULT_NAME_PATTERN!r}).")
    p.add_argument("--interval", type=float, default=DEFAULT_INTERVAL,
                   help=f"Seconds between sends (default: {DEFAULT_INTERVAL}).")
    p.add_argument("--duration", type=float, default=DEFAULT_DURATION,
                   help=f"Total seconds to send (default: {DEFAULT_DURATION}).")
    p.add_argument("--dry-run", action="store_true",
                   help="Build + decode the frame and print it; don't connect to the bike.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n", 1)[0])
    sub = parser.add_subparsers(dest="cmd", required=True)

    # nav -----------------------------------------------------------------
    p = sub.add_parser("nav", help="Forge an a531 nav frame.")
    p.add_argument("--arrow", type=int, default=8, help="Maneuver ID 0-255 (= cluster arrow icon).")
    p.add_argument("--dist-next", default="0150", help="4 chars; distance to next maneuver.")
    p.add_argument("--dist-next-unit", default="M", help="'M' or 'K'.")
    p.add_argument("--eta", default=None, help="6 chars; default is current wall-clock HHMMAA.")
    p.add_argument("--dist-total", default="0500", help="4 chars; total distance to go.")
    p.add_argument("--dist-total-unit", default="M", help="'M' or 'K'.")
    p.add_argument("--status", default="1", help="1 char; '1'=normal, '5'=arrived, etc.")
    p.add_argument("--continue-flag", default="1", help="1 char; '1' to keep nav alive.")
    _add_common(p)

    # text shortcut --------------------------------------------------------
    p = sub.add_parser("text", help="Shortcut: 4+6+4 chars of text across dist/eta/dist fields.")
    p.add_argument("slot1", help="4 chars in dist_next position.")
    p.add_argument("slot2", help="6 chars in eta position.")
    p.add_argument("slot3", help="4 chars in dist_total position.")
    p.add_argument("--arrow", type=int, default=8)
    _add_common(p)

    # heartbeat ------------------------------------------------------------
    p = sub.add_parser("heartbeat", help="Forge an a533 heartbeat frame.")
    p.add_argument("--battery", default="3", help="'0'/'1'/'2'/'3' battery bucket.")
    p.add_argument("--charging", default="Y", help="'Y' or 'N'.")
    p.add_argument("--speed", default="000", help="3-digit speed string (bike ignores it).")
    p.add_argument("--signal", default="3", help="cell signal digit '0'-'3'.")
    p.add_argument("--sms-pending", default="N", help="'N' or 'Y'.")
    p.add_argument("--call-pending", default="N", help="'N' or 'Y'.")
    p.add_argument("--weather", type=int, default=1, help="0-11 weather code (see protocol.py).")
    p.add_argument("--temp-c", type=float, default=25.0, help="Outdoor temp in Celsius.")
    _add_common(p)

    # identity -------------------------------------------------------------
    p = sub.add_parser("identity", help="Forge an a536 identity frame.")
    p.add_argument("name", help="Up to 20 chars; cluster shows on welcome banner.")
    p.add_argument("--fresh", action="store_true", help="Set 'F' byte 27 (default 'R').")
    _add_common(p)

    args = parser.parse_args()

    # Build the factory for the chosen frame type
    if args.cmd == "nav":
        eta = args.eta or _now_hhmma()
        def factory():
            return build_nav(args.arrow, args.dist_next, args.dist_next_unit, eta,
                             args.dist_total, args.dist_total_unit,
                             args.status, args.continue_flag)
    elif args.cmd == "text":
        def factory():
            return build_text(args.slot1, args.slot2, args.slot3, arrow=args.arrow)
    elif args.cmd == "heartbeat":
        def factory():
            return build_heartbeat(args.battery, args.charging, args.speed, args.signal,
                                   args.sms_pending, args.call_pending,
                                   args.weather, args.temp_c)
    elif args.cmd == "identity":
        def factory():
            return build_identity(args.name, is_fresh=args.fresh)
    else:
        parser.error(f"unknown cmd: {args.cmd}")

    # Always show a preview of the first frame
    sample = factory()
    print(f"Frame ({len(sample)} bytes): {sample.hex()}")
    assert is_well_formed(sample), "internal bug: built malformed frame"
    print(f"Decoded: {decode(sample)!r}".replace(", raw=None", ""))

    if args.dry_run:
        return 0

    mac = asyncio.run(_resolve_target(args.mac, args.name))
    asyncio.run(_send_loop(mac, factory, args.interval, args.duration))
    return 0


if __name__ == "__main__":
    sys.exit(main())
