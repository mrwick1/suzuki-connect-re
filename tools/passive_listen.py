#!/usr/bin/env python3
"""Passive BLE listener for the Suzuki Gixxer's notify channel (0xFFF2).

Connects to the bike from the laptop as the only Central. Subscribes to
the bike's notify characteristic and logs every notification with:
- Timestamp (wall clock + relative)
- Raw hex
- ASCII decode (printable bytes shown, non-printable as '.')
- Parsed fields: header byte, type byte, body, last-byte-before-7F, terminator

Anomalies (anything not matching the expected `0xA5 <type> ... <X> 0x7F` 30-byte
pattern) are flagged.

NEVER writes to any characteristic. Pure passive.

Prerequisites:
- Bike key ON (BLE advertising)
- Suzuki Connect app must be closed/disconnected on the phone (only one
  Central can be connected at a time)
- Laptop Bluetooth powered ON

Usage:
    python tools/passive_listen.py --address AA:BB:CC:DD:EE:FF
    python tools/passive_listen.py --address AA:BB:CC:DD:EE:FF --output captures/listen-$(date +%Y%m%d-%H%M).txt
"""
import argparse
import asyncio
import sys
from datetime import datetime, timezone

from bleak import BleakClient

NOTIFY_CHAR_UUID = "0000fff2-0000-1000-8000-00805f9b34fb"


def fmt_msg(data: bytearray, t0: datetime) -> str:
    now = datetime.now(timezone.utc).astimezone()
    rel = (now - t0).total_seconds()
    raw_hex = data.hex()
    ascii_view = ''.join(chr(b) if 32 <= b <= 126 else '.' for b in data)

    # Parse the expected frame structure
    anomalies = []
    parsed = "(unrecognized structure)"
    if len(data) == 30:
        if data[0] != 0xA5:
            anomalies.append(f"unexpected header 0x{data[0]:02x} (expected 0xA5)")
        if data[-1] != 0x7F:
            anomalies.append(f"unexpected terminator 0x{data[-1]:02x} (expected 0x7F)")
        type_byte = data[1]
        type_char = chr(type_byte) if 32 <= type_byte <= 126 else '?'
        body_ascii = ''.join(chr(b) if 32 <= b <= 126 else '.' for b in data[2:28])
        last_pre_csum = data[27]
        cksum = data[28]
        parsed = (
            f"type=0x{type_byte:02x}('{type_char}') "
            f"body=[{body_ascii}] "
            f"pos27=0x{last_pre_csum:02x} "
            f"cksum=0x{cksum:02x}"
        )
    elif len(data) != 30:
        anomalies.append(f"unexpected length {len(data)} (typical is 30)")

    flag = ""
    if anomalies:
        flag = " !! ANOMALY: " + "; ".join(anomalies)

    return (
        f"[{now.strftime('%H:%M:%S.%f')[:-3]} +{rel:7.3f}s] "
        f"len={len(data):2d} "
        f"hex={raw_hex}\n"
        f"    ascii='{ascii_view}'\n"
        f"    {parsed}{flag}"
    )


class Counter:
    def __init__(self) -> None:
        self.count = 0


async def listen(address: str, output_path: str | None) -> None:
    t0 = datetime.now(timezone.utc).astimezone()
    counter = Counter()

    out_file = open(output_path, "w") if output_path else None

    def write(line: str) -> None:
        print(line, flush=True)
        if out_file:
            out_file.write(line + "\n")
            out_file.flush()

    def on_notify(_sender, data: bytearray) -> None:
        counter.count += 1
        write(fmt_msg(data, t0))

    write(f"=== Suzuki BLE Passive Listener ===")
    write(f"Start: {t0.isoformat()}")
    write(f"Target: {address}")
    write(f"Subscribing to notify char {NOTIFY_CHAR_UUID}")
    write(f"")

    try:
        async with BleakClient(address) as client:
            write(f"Connected. MTU={client.mtu_size}")
            await client.start_notify(NOTIFY_CHAR_UUID, on_notify)
            write(f"Subscribed. Listening... (Ctrl-C to stop)")
            write(f"")
            try:
                while True:
                    await asyncio.sleep(60)
            except KeyboardInterrupt:
                pass
            await client.stop_notify(NOTIFY_CHAR_UUID)
            write(f"")
            write(f"=== Stopped after {counter.count} notifications ===")
    finally:
        if out_file:
            out_file.close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--address", required=True, help="Bike BLE MAC address")
    parser.add_argument("--output", help="Optional log file (otherwise stdout only)")
    args = parser.parse_args()
    try:
        asyncio.run(listen(args.address, args.output))
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
