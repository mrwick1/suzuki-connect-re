#!/usr/bin/env python3
"""Full bike diagnosis in ONE connection — batches all remaining bike-tied
experiments before parking. Each step prints a 'LOOK NOW' marker so the user
knows when to observe the cluster.

Steps (single connection, ~120 sec):
  1. Identity push (establish session)
  2. Network-YES heartbeat (x2) — does 'Searching for network' clear?
  3. Captured a531 display replay — does cluster show 5:17 PM / 5.6 km?
  4. a531 with modified time field — does cluster show our custom time?
  5. a531 with arbitrary text — does cluster render custom string?
  6. a536 with name 'Arch Linux' — does cluster show custom name?
  7. Maintenance loop (network-YES + a531 every 2s for 30s) — observe stability
  8. Unknown message type probe (a534) — does bike ack or reject?

All writes go to 0xFFF1. Checksum: sum(payload[1:28]) mod 256.
"""
import argparse
import asyncio
from datetime import datetime, timezone

from bleak import BleakClient

WRITE_CHAR_UUID = "0000fff1-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR_UUID = "0000fff2-0000-1000-8000-00805f9b34fb"

# Captured payloads from M0
ARJUN_IDENTITY = bytes.fromhex("a53641524a554e000000000000000000000000000000ffffffffff46f77f")
HEARTBEAT_YES_NETWORK = bytes.fromhex("a533335932313432303531323032594effffffffff05cb01ffffffff217f")
A531_REPLAY = bytes.fromhex("a5312eff303038304d30353137504dffffff30352e364b3031ffffff4c7f")


def csum(p: bytes) -> int:
    return sum(p[1:28]) & 0xFF


def fix_csum(p: bytes) -> bytes:
    """Recompute checksum at position 28 to make payload valid."""
    b = bytearray(p)
    b[28] = csum(bytes(b))
    return bytes(b)


def fmt(b: bytes) -> str:
    return b.hex() + "  '" + ''.join(chr(c) if 32 <= c <= 126 else '.' for c in b) + "'"


def build_a531_custom_time(time_4chars: str) -> bytes:
    """Modify the time field in the captured a531. Time is at positions 9-12 (4 chars)."""
    assert len(time_4chars) == 4 and time_4chars.isascii()
    b = bytearray(A531_REPLAY)
    b[9:13] = time_4chars.encode("ascii")
    return fix_csum(bytes(b))


def build_a531_custom_text(text: str) -> bytes:
    """Build a fully custom a531. Body region is positions 2-27 (26 bytes)."""
    assert text.isascii() and len(text) <= 26
    b = bytearray(30)
    b[0] = 0xA5
    b[1] = 0x31
    body = bytearray(b"\xff" * 26)
    body[:len(text)] = text.encode("ascii")
    b[2:28] = body
    b[29] = 0x7F
    return fix_csum(bytes(b))


def build_a536_custom_name(name: str) -> bytes:
    """Build a536 with custom name (max 20 chars)."""
    assert name.isascii() and len(name) <= 20
    b = bytearray(30)
    b[0] = 0xA5
    b[1] = 0x36
    name_bytes = name.encode("ascii")
    b[2:2 + len(name_bytes)] = name_bytes
    b[22:27] = b"\xff" * 5
    b[27] = 0x46  # 'F' flag
    b[29] = 0x7F
    return fix_csum(bytes(b))


def build_a534_probe() -> bytes:
    """Probe message with unknown type 0x34. Body is the captured a531's body."""
    b = bytearray(A531_REPLAY)
    b[1] = 0x34  # change type to unknown
    b[29] = 0x7F
    return fix_csum(bytes(b))


async def run(address: str) -> None:
    t0 = datetime.now(timezone.utc).astimezone()
    notify_count = [0]
    last_notify = [None]

    def stamp() -> str:
        return f"[+{(datetime.now(timezone.utc).astimezone() - t0).total_seconds():6.2f}s]"

    def on_notify(_s, data: bytearray) -> None:
        notify_count[0] += 1
        last_notify[0] = bytes(data)
        print(f"{stamp()} NOTIFY: {fmt(bytes(data))}")

    async def send(label: str, payload: bytes, look_msg: str | None = None, wait: float = 4.0) -> None:
        print(f"\n{stamp()} >>> {label}")
        print(f"          {fmt(payload)}")
        try:
            await client.write_gatt_char(WRITE_CHAR_UUID, payload, response=True)
            print(f"{stamp()}          WRITE OK")
        except Exception as e:
            print(f"{stamp()}          WRITE FAILED: {type(e).__name__}: {e}")
            return
        if look_msg:
            print(f"{stamp()}          *** LOOK AT CLUSTER NOW: {look_msg} ***")
        await asyncio.sleep(wait)

    print(f"Connecting to {address}...")
    async with BleakClient(address) as client:
        print(f"{stamp()} Connected. MTU={client.mtu_size}")
        await client.start_notify(NOTIFY_CHAR_UUID, on_notify)
        print(f"{stamp()} Subscribed to notify")

        # 1
        await send("STEP 1: ARJUN identity (session setup)", ARJUN_IDENTITY, None, 2)

        # 2: network YES x2
        await send("STEP 2a: a533 heartbeat NETWORK=YES (1st)", HEARTBEAT_YES_NETWORK,
                   "'Searching for network' should disappear", 3)
        await send("STEP 2b: a533 heartbeat NETWORK=YES (2nd)", HEARTBEAT_YES_NETWORK,
                   "(network status should be solidly YES now)", 3)

        # 3
        await send("STEP 3: a531 display REPLAY (time=05:17 PM, dist=5.6km)", A531_REPLAY,
                   "should show 05:17 PM and 5.6 km", 5)

        # 4
        custom_time = build_a531_custom_time("9999")
        await send("STEP 4: a531 with MODIFIED TIME = '9999'", custom_time,
                   "time field should now read 99:99 PM", 5)

        # 5
        custom_text = build_a531_custom_text("HELLO BIKE")
        await send("STEP 5: a531 with ARBITRARY TEXT 'HELLO BIKE'", custom_text,
                   "display area should show 'HELLO BIKE' or garbled chars", 5)

        # 6
        custom_name = build_a536_custom_name("Arch Linux")
        await send("STEP 6: a536 with NAME = 'Arch Linux'", custom_name,
                   "name display should show 'Arch Linux'", 5)

        # 7: maintenance loop - YES heartbeat + a531 every 2s for 20s
        print(f"\n{stamp()} >>> STEP 7: MAINTENANCE LOOP for 20s (YES heartbeat + a531 every 2s)")
        print(f"{stamp()}          *** TRIGGER THINGS NOW: throttle / brake / horn / indicator — anything ***")
        loop_end = asyncio.get_event_loop().time() + 20.0
        loop_seq = 0
        while asyncio.get_event_loop().time() < loop_end:
            try:
                await client.write_gatt_char(WRITE_CHAR_UUID, HEARTBEAT_YES_NETWORK, response=True)
                await asyncio.sleep(0.3)
                await client.write_gatt_char(WRITE_CHAR_UUID, A531_REPLAY, response=True)
                loop_seq += 1
            except Exception as e:
                print(f"{stamp()}          LOOP write failed: {type(e).__name__}: {e}")
                break
            await asyncio.sleep(1.5)
        print(f"{stamp()}          Loop done ({loop_seq} cycles)")

        # 8
        probe = build_a534_probe()
        await send("STEP 8: PROBE unknown msg type a534 (does bike accept?)", probe,
                   "(observe — error response or silent accept?)", 4)

        await client.stop_notify(NOTIFY_CHAR_UUID)
        print(f"\n{stamp()} === DONE. Sent {loop_seq+8} writes total, received {notify_count[0]} notifications ===")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--address", required=True)
    args = parser.parse_args()
    asyncio.run(run(args.address))


if __name__ == "__main__":
    main()
