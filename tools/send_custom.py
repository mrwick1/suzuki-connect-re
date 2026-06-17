#!/usr/bin/env python3
"""Send custom payloads to the Suzuki Gixxer's write characteristic (0xFFF1)
to test whether the cluster displays our content.

SAFETY:
- ONLY writes to 0xFFF1 (the known, app-used write characteristic).
- NEVER writes to any unknown characteristic.
- All payloads conform to the 30-byte fixed frame: 0xA5 ... 0x7F.
- Worst case: bike rejects the write or displays garbage. No risk of
  disabling ABS, cutting fuel, etc. (those would require writes to
  unknown characteristics, which this tool refuses to do).

Modes:
- `replay`: send a known-good captured `a531` payload byte-for-byte.
  Used as the first sanity check that the bike accepts writes from
  this laptop at all.
- `text-time`: send an `a531` with a custom 4-char time string
  (e.g., "9999"). Same checksum byte as a captured baseline (likely
  wrong but easy to test). If bike accepts: checksum is not strictly
  enforced. If bike rejects: we know we need to crack the checksum.
- `text-arbitrary`: send a fully custom ASCII payload in an `a531`
  frame. Optionally try multiple checksum algorithms.

Prerequisites:
- Bike key ON (BLE advertising)
- Suzuki Connect app closed on phone (only one Central at a time)
- Laptop Bluetooth ON

Usage:
    # Sanity check — send a known-good captured a531 verbatim
    python tools/send_custom.py --address AA:BB:CC:DD:EE:FF --mode replay

    # Modify the time field to "9999", keep original checksum
    python tools/send_custom.py --address ... --mode text-time --time 9999

    # Custom ASCII payload (must be <= 24 chars, fits in body)
    python tools/send_custom.py --address ... --mode text-arbitrary --text "HELLO BIKE"
"""
import argparse
import asyncio
import sys
import zlib

from bleak import BleakClient

WRITE_CHAR_UUID = "0000fff1-0000-1000-8000-00805f9b34fb"

# Known-good captured `a531` message from M0 capture at t=83.20s:
# `.1..0080M0517PM...05.6K01...L.` (30 bytes)
# Hex dump for replay:
KNOWN_GOOD_A531 = bytes.fromhex("a5312eff303038304d30353137504dffffff30352e364b3031ffffff4c7f")
assert len(KNOWN_GOOD_A531) == 30
assert KNOWN_GOOD_A531[0] == 0xA5
assert KNOWN_GOOD_A531[1] == 0x31  # type '1'
assert KNOWN_GOOD_A531[-1] == 0x7F


def build_text_time(time_str: str) -> bytes:
    """Replace the time field (positions 9-14 = `0517PM`) with `time_str+PM`.
    Reuses the original checksum byte at position 28 (almost certainly wrong,
    but the test is whether bike validates it strictly).
    """
    if len(time_str) != 4 or not time_str.isascii():
        raise ValueError("time_str must be 4 ASCII chars")
    payload = bytearray(KNOWN_GOOD_A531)
    # Original time bytes are at positions 9-14: "0517PM" (6 chars: 4 digits + PM)
    # Replace only the 4 digits, keep "PM"
    payload[9:13] = time_str.encode("ascii")
    return bytes(payload)


def build_text_arbitrary(text: str, checksum_strategy: str = "passthrough") -> bytes:
    """Build a custom-text `a531` frame.
    Body region (positions 2-27, 26 bytes) holds our text padded with 0xFF.
    """
    if not text.isascii():
        raise ValueError("text must be ASCII")
    if len(text) > 26:
        raise ValueError(f"text too long ({len(text)} > 26 bytes body)")

    body = bytearray(b"\xff" * 26)
    body[:len(text)] = text.encode("ascii")

    frame = bytearray(30)
    frame[0] = 0xA5
    frame[1] = 0x31  # type '1' (display refresh)
    frame[2:28] = body
    # Position 28 is checksum
    frame[28] = compute_checksum(bytes(frame[:28]), checksum_strategy)
    frame[29] = 0x7F
    return bytes(frame)


def compute_checksum(prefix: bytes, strategy: str) -> int:
    """Try various checksum strategies. None is known to be correct yet."""
    if strategy == "passthrough":
        # Reuse the byte at position 28 from KNOWN_GOOD_A531
        return KNOWN_GOOD_A531[28]
    if strategy == "sum8":
        return sum(prefix) & 0xFF
    if strategy == "xor8":
        v = 0
        for b in prefix:
            v ^= b
        return v
    if strategy == "crc8":
        # Polynomial 0x07
        crc = 0
        for b in prefix:
            crc ^= b
            for _ in range(8):
                crc = ((crc << 1) ^ 0x07) if (crc & 0x80) else (crc << 1)
                crc &= 0xFF
        return crc
    if strategy == "crc32-low":
        return zlib.crc32(prefix) & 0xFF
    raise ValueError(f"unknown strategy {strategy}")


def hex_pretty(b: bytes) -> str:
    return " ".join(f"{x:02x}" for x in b)


def ascii_view(b: bytes) -> str:
    return ''.join(chr(c) if 32 <= c <= 126 else '.' for c in b)


async def send_once(address: str, payload: bytes) -> None:
    assert len(payload) == 30, f"Payload must be 30 bytes, got {len(payload)}"
    assert payload[0] == 0xA5, f"Header must be 0xA5, got 0x{payload[0]:02x}"
    assert payload[-1] == 0x7F, f"Terminator must be 0x7F, got 0x{payload[-1]:02x}"

    print(f"Connecting to {address}...")
    async with BleakClient(address) as client:
        print(f"Connected. MTU={client.mtu_size}")
        print(f"Sending to {WRITE_CHAR_UUID}:")
        print(f"  hex:   {hex_pretty(payload)}")
        print(f"  ascii: '{ascii_view(payload)}'")
        try:
            await client.write_gatt_char(WRITE_CHAR_UUID, payload, response=True)
            print(f"  WRITE OK (bike acknowledged)")
        except Exception as e:
            print(f"  WRITE FAILED: {type(e).__name__}: {e}")
        print("Holding connection for 5 sec so you can observe cluster...")
        await asyncio.sleep(5)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--address", required=True)
    parser.add_argument("--mode", required=True, choices=["replay", "text-time", "text-arbitrary"])
    parser.add_argument("--time", help="4-char ASCII time for text-time mode (e.g., '9999')")
    parser.add_argument("--text", help="ASCII text for text-arbitrary mode (up to 26 chars)")
    parser.add_argument("--checksum", default="passthrough",
                        choices=["passthrough", "sum8", "xor8", "crc8", "crc32-low"],
                        help="Checksum strategy for text-arbitrary mode")
    parser.add_argument("--repeat", type=int, default=1, help="Send the payload N times")
    args = parser.parse_args()

    if args.mode == "replay":
        payload = KNOWN_GOOD_A531
    elif args.mode == "text-time":
        if not args.time:
            parser.error("--time required for text-time mode")
        payload = build_text_time(args.time)
    elif args.mode == "text-arbitrary":
        if not args.text:
            parser.error("--text required for text-arbitrary mode")
        payload = build_text_arbitrary(args.text, args.checksum)
    else:
        parser.error(f"unknown mode {args.mode}")

    for i in range(args.repeat):
        if args.repeat > 1:
            print(f"\n--- Send {i+1}/{args.repeat} ---")
        try:
            asyncio.run(send_once(args.address, payload))
        except KeyboardInterrupt:
            sys.exit(0)


if __name__ == "__main__":
    main()
