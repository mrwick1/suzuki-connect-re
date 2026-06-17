#!/usr/bin/env python3
"""forge_signal_v2.py — forge the a531 signal-status byte to defeat
"Searching for network" on the bike cluster.

Based on confirmed 2026-05-23 finding: byte position 23 of a531 carries the
H1 signal-status digit. Setting it to '1' (= good signal) should let the
bike's cluster render real nav content instead of "Searching for network".

This is v2 of forge_network.py — v1 targeted the WRONG bytes (a533 pos 21/22)
based on a hypothesis that was later walked back. v2 is informed by source-code
analysis of A0.D() and template-to-capture byte mapping.

Sequence (single connection, runs until Ctrl-C):
  1. ARJUN identity push (establishes session)
  2. Subscribe to notify
  3. Loop at ~2Hz:
     a. Send a forged a531 with:
        - bytes[2]  = MANEUVER_ID (real Mappls maneuver int, default 8 = a real arrow)
        - bytes[23] = '1' (signal good)
        - bytes[24] = '1' (secondary state)
        - bytes[4-7]   = "0080" (placeholder p0)
        - bytes[8]     = 'M'    (m0)
        - bytes[9-14]  = "0517PM" (n0, captured time)
        - bytes[18-21] = "0123" (q0, fake distance "12.3" — but encoded as "0123"
                                  to match captured format; could also try "01.5")
        - bytes[22]    = 'K'    (o0 = km)
        - checksum recomputed via sum(bytes[1:28]) mod 256
     b. (Optional) Also send a533 heartbeat to keep session alive

SAFETY: only writes to 0xFFF1 (known write char). All payloads 30 bytes.

Usage:
    python tools/forge_signal_v2.py --address AA:BB:CC:DD:EE:FF
    python tools/forge_signal_v2.py --address ... --maneuver 8 --duration 60
"""
import argparse
import asyncio
from datetime import datetime, timezone

from bleak import BleakClient

WRITE_CHAR_UUID  = "0000fff1-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR_UUID = "0000fff2-0000-1000-8000-00805f9b34fb"

# Captured ARJUN identity (a536, t=63.79 in M0 capture)
ARJUN_IDENTITY = bytes.fromhex("a53641524a554e000000000000000000000000000000ffffffffff46f77f")

# Captured a533 heartbeat (phase-2 form, more recent in M0 capture)
A533_HEARTBEAT = bytes.fromhex("a5333359323134323035313230344e4effffffffff05cb01ffffffff187f")


def csum(p: bytes) -> int:
    """sum(p[1:28]) mod 256 — confirmed checksum algorithm from SuzukiApplication.a()."""
    return sum(p[1:28]) & 0xFF


def build_a531_forged(maneuver_id: int,
                      time_4digits: str = "0517",
                      ampm: str = "PM",
                      distance_4chars: str = "01.5",
                      unit: str = "K",
                      signal_good: bool = True) -> bytes:
    """Build a forged a531 with the signal-status byte set to defeat 'Searching for network'.

    Args:
        maneuver_id: Mappls maneuver ID (small int, e.g. 8). Goes in bytes[2].
        time_4digits: 4-char ASCII like "0517" (HHMM). Goes in bytes[9-12].
        ampm: "AM" or "PM". Goes in bytes[13-14].
        distance_4chars: 4-char ASCII like "01.5" or "0348". Goes in bytes[18-21].
        unit: "K" (km) or "M" (meters). Goes in bytes[22].
        signal_good: True sends '1' at pos 23 (good); False sends '0'.
    """
    assert 0 <= maneuver_id <= 255
    assert len(time_4digits) == 4 and time_4digits.isascii()
    assert ampm in ("AM", "PM")
    assert len(distance_4chars) == 4 and distance_4chars.isascii()
    assert unit in ("K", "M")

    b = bytearray(30)
    b[0] = 0xA5                          # header
    b[1] = 0x31                          # type '1'
    b[2] = maneuver_id                   # maneuver byte (this is what was '.' when degraded)
    b[3] = 0xFF                          # padding
    # p0 = "0080" placeholder (4 bytes 4-7) — unknown semantic; matches captured format
    b[4:8] = b"0080"
    # m0 = "M" (byte 8) — unit/separator
    b[8] = ord("M")
    # n0 = time + AM/PM (bytes 9-14)
    b[9:13] = time_4digits.encode("ascii")
    b[13:15] = ampm.encode("ascii")
    # bytes 15-17 = 0xFF padding
    b[15:18] = b"\xff\xff\xff"
    # q0 = distance number (bytes 18-21)
    b[18:22] = distance_4chars.encode("ascii")
    # o0 = unit (byte 22)
    b[22] = ord(unit)
    # str (H1, signal status) at byte 23
    b[23] = ord("1") if signal_good else ord("0")
    # str2 (I1, secondary state) at byte 24
    b[24] = ord("1")
    # bytes 25-27 = 0xFF padding
    b[25:28] = b"\xff\xff\xff"
    # checksum
    b[28] = csum(bytes(b))
    # terminator
    b[29] = 0x7F

    assert len(b) == 30
    return bytes(b)


def fmt(b: bytes) -> str:
    return b.hex() + "  '" + ''.join(chr(c) if 32 <= c <= 126 else '.' for c in b) + "'"


async def run(address: str, maneuver: int, duration: float | None) -> None:
    t0 = datetime.now(timezone.utc).astimezone()
    notify_count = [0]

    def stamp() -> str:
        return f"[+{(datetime.now(timezone.utc).astimezone() - t0).total_seconds():6.2f}s]"

    def on_notify(_s, data: bytearray) -> None:
        notify_count[0] += 1
        print(f"{stamp()} NOTIFY: {fmt(bytes(data))}")

    print(f"Connecting to {address}...")
    async with BleakClient(address) as client:
        print(f"{stamp()} Connected. MTU={client.mtu_size}")
        await client.start_notify(NOTIFY_CHAR_UUID, on_notify)
        print(f"{stamp()} Subscribed to notify")

        # Step 1: identity (required for bike to engage)
        print(f"\n{stamp()} >>> Send ARJUN identity")
        await client.write_gatt_char(WRITE_CHAR_UUID, ARJUN_IDENTITY, response=True)
        print(f"{stamp()}     WRITE OK: {fmt(ARJUN_IDENTITY)}")
        await asyncio.sleep(1)

        # Step 2: send one forged a531 with SIGNAL GOOD + a real maneuver ID
        forged = build_a531_forged(maneuver_id=maneuver, signal_good=True)
        print(f"\n{stamp()} >>> Send FORGED a531: maneuver={maneuver}, signal='1' (good)")
        print(f"{stamp()}     payload: {fmt(forged)}")
        await client.write_gatt_char(WRITE_CHAR_UUID, forged, response=True)
        print(f"{stamp()}     WRITE OK")
        print(f"{stamp()}     *** LOOK AT CLUSTER NOW: 'Searching for network' should clear; arrow icon {maneuver} should show ***")
        await asyncio.sleep(3)

        # Step 3: maintenance loop — alternate forged a531 + a533 heartbeat
        print(f"\n{stamp()} >>> MAINTENANCE LOOP (forged a531 + a533 heartbeat at ~2Hz)")
        end = (asyncio.get_event_loop().time() + duration) if duration else None
        i = 0
        while True:
            try:
                # forged a531 every iteration
                await client.write_gatt_char(WRITE_CHAR_UUID, forged, response=True)
                # heartbeat every other iteration
                if i % 2 == 0:
                    await client.write_gatt_char(WRITE_CHAR_UUID, A533_HEARTBEAT, response=True)
                i += 1
                if i % 10 == 0:
                    print(f"{stamp()}     loop iter={i}, notifications received={notify_count[0]}")
            except Exception as e:
                print(f"{stamp()}     loop write FAILED: {type(e).__name__}: {e}")
                break
            if end is not None and asyncio.get_event_loop().time() >= end:
                break
            await asyncio.sleep(0.5)

        await client.stop_notify(NOTIFY_CHAR_UUID)
        print(f"\n{stamp()} === Done. {i} loop iterations, {notify_count[0]} notifications. ===")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--address", required=True, help="Bike BLE MAC address")
    parser.add_argument("--maneuver", type=int, default=8,
                        help="Mappls maneuver ID to forge in bytes[2] (default 8 — a known real value from WITH-SIM capture)")
    parser.add_argument("--duration", type=float, default=None,
                        help="Loop duration in sec (default: run until Ctrl-C)")
    args = parser.parse_args()
    try:
        asyncio.run(run(args.address, args.maneuver, args.duration))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
