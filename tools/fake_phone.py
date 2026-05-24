#!/usr/bin/env python3
"""Fake-phone test: does the bike accept writes from a non-Suzuki client? See DISCOVERIES.md 2026-05-24."""
import argparse
import asyncio
import os
import re
import sys
from datetime import datetime, timezone

from bleak import BleakClient, BleakScanner

import protocol

SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"
WRITE_CHAR_UUID = "0000fff1-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR_UUID = "0000fff2-0000-1000-8000-00805f9b34fb"

# Bike's BLE local name is serial-derived (see LOCAL_NOTES.md). Default match
# pattern covers the Gixxer SF 150 family naming ("SBM..." cluster names).
DEFAULT_NAME_PATTERN = r"^SBM[A-Z0-9]+$"


def now_str() -> str:
    return datetime.now(timezone.utc).astimezone().strftime("%H:%M:%S.%f")[:-3]


def log(msg: str) -> None:
    print(f"[{now_str()}] {msg}", flush=True)


def section(title: str) -> None:
    print(f"\n=== {title} ===", flush=True)


async def find_by_name(pattern: str, timeout: float = 8.0) -> str:
    log(f"Scanning {timeout:.0f}s for devices matching /{pattern}/...")
    devices = await BleakScanner.discover(timeout=timeout, return_adv=True)
    matches = []
    log(f"Discovered {len(devices)} devices:")
    for addr, (d, adv) in devices.items():
        name = d.name or adv.local_name or ""
        log(f"  {addr}  RSSI={adv.rssi}  name={name!r}")
        if name and re.search(pattern, name):
            matches.append((addr, name))
    if not matches:
        raise SystemExit(f"No device matched /{pattern}/")
    if len(matches) > 1:
        raise SystemExit(f"Multiple matches for /{pattern}/: {matches} — pass --mac")
    addr, name = matches[0]
    log(f"Selected {addr} (name={name!r})")
    return addr


def verify_gatt(client: BleakClient) -> None:
    log(f"Connected. MTU={client.mtu_size}")
    found_service = False
    found_write = False
    found_notify = False
    for service in client.services:
        log(f"Service {service.uuid}  ({service.description})")
        if service.uuid.lower() == SERVICE_UUID:
            found_service = True
        for char in service.characteristics:
            props = ",".join(char.properties)
            log(f"  Char {char.uuid}  [{props}]")
            if char.uuid.lower() == WRITE_CHAR_UUID and (
                "write" in char.properties or "write-without-response" in char.properties
            ):
                found_write = True
            if char.uuid.lower() == NOTIFY_CHAR_UUID and "notify" in char.properties:
                found_notify = True
    if not (found_service and found_write and found_notify):
        raise SystemExit(
            f"GATT mismatch — service={found_service} write={found_write} notify={found_notify}; "
            f"this is not a Suzuki Connect cluster"
        )
    log("GATT structure matches Suzuki Connect (0xFFF0 / 0xFFF1 write / 0xFFF2 notify).")


class NotifyCollector:
    def __init__(self) -> None:
        self.total = 0
        self.a537_count = 0
        self.by_type: dict[int, int] = {}

    def on_notify(self, _sender, data: bytearray) -> None:
        self.total += 1
        raw = bytes(data)
        type_byte = raw[1] if len(raw) >= 2 else None
        if type_byte is not None:
            self.by_type[type_byte] = self.by_type.get(type_byte, 0) + 1
            if type_byte == protocol.FrameType.TELEMETRY:
                self.a537_count += 1
        log(f"NOTIFY len={len(raw)} hex={raw.hex()}")
        try:
            decoded = protocol.decode(raw)
            log(f"  decoded: {decoded!r}")
        except Exception as exc:
            log(f"  decode failed: {type(exc).__name__}: {exc}")


async def run(mac: str | None, name_pattern: str, duration: float, name_only: bool) -> int:
    section("STEP 1: Find the bike")
    if mac:
        log(f"Using MAC from --mac/env: {mac}")
    else:
        mac = await find_by_name(name_pattern)

    section("STEP 2: Connect")
    log(f"Connecting to {mac}...")
    try:
        async with BleakClient(mac) as client:
            section("STEP 3: GATT verify")
            verify_gatt(client)

            section("STEP 4: Subscribe to 0xFFF2 notify")
            collector = NotifyCollector()
            await client.start_notify(NOTIFY_CHAR_UUID, collector.on_notify)
            log("Subscribed.")

            section("STEP 5: 500ms settle wait")
            await asyncio.sleep(0.5)

            if name_only:
                section("STEP 6: SKIPPED (--name-only)")
                log("Not sending a536. Just listening.")
            else:
                section("STEP 6: Send forged a536 IDENTITY")
                frame = protocol.IdentityFrame(name="FAKE_PHONE", is_fresh=True).encode()
                log(f"a536 hex: {frame.hex()}")
                try:
                    await client.write_gatt_char(WRITE_CHAR_UUID, frame, response=False)
                    log("Write returned OK (write-without-response).")
                except Exception as exc:
                    log(f"Write FAILED: {type(exc).__name__}: {exc}")
                    section("VERDICT: FAIL")
                    log("Write was rejected — connection up but characteristic write denied.")
                    log("Likely cause: BLE bonding required, or characteristic permission gate.")
                    await client.stop_notify(NOTIFY_CHAR_UUID)
                    return 2

            section(f"STEP 7: Listen for {duration:.0f}s")
            try:
                await asyncio.sleep(duration)
            except KeyboardInterrupt:
                log("Interrupted by user.")

            await client.stop_notify(NOTIFY_CHAR_UUID)

            section("STEP 8: Verdict")
            log(f"Total notifies received: {collector.total}")
            for tb, n in sorted(collector.by_type.items()):
                log(f"  type 0x{tb:02x} ('{chr(tb) if 32 <= tb < 127 else '?'}'): {n}")
            log(f"a537 (telemetry) count: {collector.a537_count}")

            if name_only:
                if collector.total > 0:
                    log("INFO — got notifies WITHOUT sending a536. Bike may push unprompted, "
                        "or previous pairing state is cached on the cluster.")
                else:
                    log("INFO — silent without a536, as expected from the source-mapped handshake.")
                return 0

            if collector.a537_count > 0:
                log(f"PASS — bike accepts writes from fake phone "
                    f"(got {collector.a537_count} a537 frames after a536). "
                    f"Refutes the BLE-bonding-required hypothesis.")
                return 0
            else:
                log(f"AMBIGUOUS — connected and wrote, but no a537 came back in {duration:.0f}s. "
                    f"Possible causes: bonding required, bike ignition off, "
                    f"notify subscription didn't take, or cluster is gating on something we missed.")
                return 1
    except SystemExit:
        raise
    except Exception as exc:
        section("VERDICT: FAIL")
        log(f"Connection failed: {type(exc).__name__}: {exc}")
        return 2


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Pretend to be the Suzuki Connect app and test whether the bike "
                    "accepts writes from a non-paired client."
    )
    parser.add_argument("--mac", help="Bike BLE MAC (else env SUZUKI_BIKE_MAC, else name scan)")
    parser.add_argument(
        "--name",
        default=DEFAULT_NAME_PATTERN,
        help=f"Regex for BLE local name when scanning (default: {DEFAULT_NAME_PATTERN!r})",
    )
    parser.add_argument(
        "--duration", type=float, default=30.0,
        help="Seconds to listen for a537 after sending a536 (default: 30)",
    )
    parser.add_argument(
        "--name-only", action="store_true",
        help="Skip the a536 write — just connect, subscribe, and listen. Sanity check.",
    )
    args = parser.parse_args()

    mac = args.mac or os.environ.get("SUZUKI_BIKE_MAC")
    try:
        rc = asyncio.run(run(mac, args.name, args.duration, args.name_only))
    except KeyboardInterrupt:
        rc = 130
    sys.exit(rc)


if __name__ == "__main__":
    main()
