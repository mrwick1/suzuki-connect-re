#!/usr/bin/env python3
"""Phase B experiment: replay write + custom name to bike.

Sequence (single connection):
1. Connect, subscribe to notify (to keep bike happy).
2. Send captured `a536` ARJUN identity (establishes session — bike requires
   this before accepting other writes, per our 2026-05-23 experiment).
3. Send captured `a531` display refresh verbatim (replay). Cluster should
   briefly show whatever the captured payload encoded.
4. Wait 6 sec for visual observation.
5. Send custom `a536` with name "Arch Linux" instead of "ARJUN". Cluster
   should briefly show the new name.
6. Wait 6 sec for observation.
7. Disconnect.

Uses CONFIRMED checksum algorithm: sum(payload[1:28]) mod 256.

SAFETY: only writes to 0xFFF1 (known, app-used write char). All payloads
are 30 bytes with valid header/terminator.
"""
import argparse
import asyncio
from datetime import datetime, timezone

from bleak import BleakClient

WRITE_CHAR_UUID = "0000fff1-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR_UUID = "0000fff2-0000-1000-8000-00805f9b34fb"

# Captured ARJUN identity (M0 t=63.79s)
ARJUN_IDENTITY = bytes.fromhex("a53641524a554e000000000000000000000000000000ffffffffff46f77f")

# Captured a531 display refresh (M0 t=83.20s):
# `.1..0080M0517PM...05.6K01...L.` — time=0517PM, distance=05.6K
A531_REPLAY = bytes.fromhex("a5312eff303038304d30353137504dffffff30352e364b3031ffffff4c7f")


def compute_checksum(payload_28: bytes) -> int:
    """sum(payload[1:28]) mod 256 — confirmed 2026-05-23."""
    return sum(payload_28[1:28]) & 0xFF


def build_name_message(name: str) -> bytes:
    """Build an `a536` message with custom name.

    Frame layout (from ARJUN sample analysis):
    - pos 0: 0xA5
    - pos 1: 0x36 ('6')
    - pos 2-21: name bytes, null-padded (20-byte field)
    - pos 22-26: 0xFF padding (5 bytes)
    - pos 27: 'F' flag byte (0x46) — using same as captured baseline
    - pos 28: checksum
    - pos 29: 0x7F
    """
    name_bytes = name.encode("ascii")
    if len(name_bytes) > 20:
        raise ValueError(f"name too long ({len(name_bytes)} > 20 chars)")

    frame = bytearray(30)
    frame[0] = 0xA5
    frame[1] = 0x36
    frame[2:2 + len(name_bytes)] = name_bytes
    # positions 2+len(name_bytes) through 21 stay as 0x00 (null padding)
    frame[22:27] = b"\xff" * 5
    frame[27] = 0x46  # 'F' flag (matches ARJUN baseline)
    frame[28] = compute_checksum(bytes(frame))
    frame[29] = 0x7F
    return bytes(frame)


def fmt(b: bytes) -> str:
    return " ".join(f"{x:02x}" for x in b) + "   '" + ''.join(chr(c) if 32 <= c <= 126 else '.' for c in b) + "'"


async def run(address: str) -> None:
    t0 = datetime.now(timezone.utc).astimezone()

    def stamp() -> str:
        return f"[+{(datetime.now(timezone.utc).astimezone() - t0).total_seconds():6.2f}s]"

    def on_notify(_s, data: bytearray) -> None:
        print(f"{stamp()} NOTIFY: {fmt(bytes(data))}")

    print(f"Connecting to {address}...")
    async with BleakClient(address) as client:
        print(f"{stamp()} Connected. MTU={client.mtu_size}")

        # Subscribe (so bike will heartbeat back to us — keeps session alive)
        await client.start_notify(NOTIFY_CHAR_UUID, on_notify)
        print(f"{stamp()} Subscribed")

        # Step 1: identity push (required for bike to start engaging)
        print(f"\n{stamp()} >>> STEP 1: Send ARJUN identity (establish session)")
        print(f"          payload: {fmt(ARJUN_IDENTITY)}")
        try:
            await client.write_gatt_char(WRITE_CHAR_UUID, ARJUN_IDENTITY, response=True)
            print(f"{stamp()}          WRITE OK")
        except Exception as e:
            print(f"{stamp()}          WRITE FAILED: {type(e).__name__}: {e}")
            return
        await asyncio.sleep(2)

        # Step 2: replay captured a531 display
        print(f"\n{stamp()} >>> STEP 2: Replay captured a531 display (time=0517PM, dist=05.6K)")
        print(f"          payload: {fmt(A531_REPLAY)}")
        try:
            await client.write_gatt_char(WRITE_CHAR_UUID, A531_REPLAY, response=True)
            print(f"{stamp()}          WRITE OK")
        except Exception as e:
            print(f"{stamp()}          WRITE FAILED: {type(e).__name__}: {e}")
        print(f"{stamp()}          *** LOOK AT CLUSTER NOW — should show 05:17 PM / 5.6 km ***")
        await asyncio.sleep(6)

        # Step 3: custom name
        custom_name = "Arch Linux"
        name_msg = build_name_message(custom_name)
        print(f"\n{stamp()} >>> STEP 3: Send custom name a536 with '{custom_name}'")
        print(f"          payload: {fmt(name_msg)}")
        try:
            await client.write_gatt_char(WRITE_CHAR_UUID, name_msg, response=True)
            print(f"{stamp()}          WRITE OK")
        except Exception as e:
            print(f"{stamp()}          WRITE FAILED: {type(e).__name__}: {e}")
        print(f"{stamp()}          *** LOOK AT CLUSTER NOW — should show '{custom_name}' ***")
        await asyncio.sleep(6)

        # Stay connected briefly so we can see any final notifications
        await asyncio.sleep(2)
        await client.stop_notify(NOTIFY_CHAR_UUID)
        print(f"\n{stamp()} Done.")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--address", required=True)
    args = parser.parse_args()
    asyncio.run(run(args.address))


if __name__ == "__main__":
    main()
