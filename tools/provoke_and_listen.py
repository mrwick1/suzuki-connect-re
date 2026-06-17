#!/usr/bin/env python3
"""Provoke + listen: send phone-like heartbeats to the bike's write channel
and listen for notifications it pushes back.

Hypothesis being tested: the bike doesn't push notifies autonomously. It
streams them only after the Central writes something first (in the M0 capture,
phone wrote first, then bike notified). Passive listen alone produced zero
notifications in 30+ sec.

Strategy:
1. Connect to bike, negotiate MTU.
2. Subscribe to notify char 0xFFF2 (enable CCCD).
3. Send captured user-identity message (`a536` ARJUN) once.
4. Loop: send captured heartbeat (`a533`) every 1 second.
5. Log every notification received.

SAFETY: only writes the EXACT bytes captured from the M0 session (no
modifications, no fuzzing). These are byte-identical to what the legitimate
Suzuki Connect app sent — same checksum, same payload — so we're impersonating
a legitimate Central's keepalive, not forging new content.

Usage:
    python tools/provoke_and_listen.py --address AA:BB:CC:DD:EE:FF --duration 60
"""
import argparse
import asyncio
import sys
from datetime import datetime, timezone

from bleak import BleakClient

WRITE_CHAR_UUID = "0000fff1-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR_UUID = "0000fff2-0000-1000-8000-00805f9b34fb"

# Captured from M0 session at t=63.79s (first ARJUN identity push)
IDENTITY_MSG = bytes.fromhex("a53641524a554e000000000000000000000000000000ffffffffff46f77f")

# Captured from M0 session at t=65.79s (first phone heartbeat)
HEARTBEAT_MSG = bytes.fromhex("a5333359323134003035303135344e4effffffffff010001ffffffff1a7f")

assert len(IDENTITY_MSG) == 30
assert len(HEARTBEAT_MSG) == 30


def fmt_notify(data: bytearray, t0: datetime) -> str:
    now = datetime.now(timezone.utc).astimezone()
    rel = (now - t0).total_seconds()
    raw_hex = data.hex()
    ascii_view = ''.join(chr(b) if 32 <= b <= 126 else '.' for b in data)
    return (
        f"[{now.strftime('%H:%M:%S.%f')[:-3]} +{rel:6.2f}s] NOTIFY "
        f"len={len(data):2d} hex={raw_hex} ascii='{ascii_view}'"
    )


async def run(address: str, duration: float, output_path: str | None) -> None:
    t0 = datetime.now(timezone.utc).astimezone()
    out_file = open(output_path, "w") if output_path else None
    notify_count = [0]

    def write(line: str) -> None:
        print(line, flush=True)
        if out_file:
            out_file.write(line + "\n")
            out_file.flush()

    def on_notify(_sender, data: bytearray) -> None:
        notify_count[0] += 1
        write(fmt_notify(data, t0))

    write(f"=== Provoke + Listen ===")
    write(f"Start: {t0.isoformat()}")
    write(f"Target: {address}")
    write(f"Duration: {duration}s")
    write(f"")

    try:
        async with BleakClient(address) as client:
            write(f"Connected. Initial MTU={client.mtu_size}")

            # Try to negotiate larger MTU (default 23 is too small for 30-byte payloads)
            try:
                new_mtu = await client._acquire_mtu()
                write(f"MTU after negotiation: {client.mtu_size} (returned {new_mtu})")
            except Exception as e:
                write(f"MTU negotiation failed (continuing with default): {type(e).__name__}: {e}")

            await client.start_notify(NOTIFY_CHAR_UUID, on_notify)
            write(f"Subscribed to notify {NOTIFY_CHAR_UUID}")

            # Send identity once
            try:
                await client.write_gatt_char(WRITE_CHAR_UUID, IDENTITY_MSG, response=True)
                write(f"[+{(datetime.now(timezone.utc).astimezone() - t0).total_seconds():.2f}s] SENT identity ({IDENTITY_MSG.hex()})")
            except Exception as e:
                write(f"Identity write FAILED: {type(e).__name__}: {e}")
                return

            # Loop heartbeats
            write(f"Sending heartbeat every 1s for {duration}s...")
            write(f"")
            end_time = asyncio.get_event_loop().time() + duration
            sent = 0
            while asyncio.get_event_loop().time() < end_time:
                try:
                    await client.write_gatt_char(WRITE_CHAR_UUID, HEARTBEAT_MSG, response=True)
                    sent += 1
                    if sent % 5 == 0:
                        write(f"[+{(datetime.now(timezone.utc).astimezone() - t0).total_seconds():.2f}s] sent {sent} heartbeats so far, received {notify_count[0]} notifications")
                except Exception as e:
                    write(f"Heartbeat write FAILED: {type(e).__name__}: {e}")
                    break
                await asyncio.sleep(1.0)

            await client.stop_notify(NOTIFY_CHAR_UUID)
            write(f"")
            write(f"=== Done. Sent {sent} heartbeats, received {notify_count[0]} notifications ===")
    finally:
        if out_file:
            out_file.close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--address", required=True)
    parser.add_argument("--duration", type=float, default=30.0)
    parser.add_argument("--output")
    args = parser.parse_args()
    try:
        asyncio.run(run(args.address, args.duration, args.output))
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
