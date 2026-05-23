#!/usr/bin/env python3
"""Phase B with network-status: send the captured 'network YES' heartbeat.

Discovered 2026-05-23: in the M0 capture, `a533` heartbeat messages had position
14 = 'N' (no network) for most of the session, but for 2 messages (~1 sec) at
t=674-675s, position 14 = 'Y' (network YES). This Y/N flag almost certainly
controls whether the bike's cluster shows nav content or "Searching for network".

Sequence:
1. Connect, subscribe to notify (to keep bike engaged).
2. Send captured ARJUN identity.
3. Send the captured "network YES" a533 heartbeat (pos 14 = 'Y').
4. Wait, observe cluster.
5. Send captured a531 display (time/distance).
6. Wait, observe cluster.
7. (Optional) repeat YES heartbeat to maintain state.
"""
import argparse
import asyncio
from datetime import datetime, timezone

from bleak import BleakClient

WRITE_CHAR_UUID = "0000fff1-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR_UUID = "0000fff2-0000-1000-8000-00805f9b34fb"

ARJUN_IDENTITY = bytes.fromhex("a53641524a554e000000000000000000000000000000ffffffffff46f77f")

# Captured a533 with pos 14 = 'Y' (network YES), from t=674.29s in M0 capture
HEARTBEAT_YN_YES = bytes.fromhex("a533335932313432303531323032594effffffffff05cb01ffffffff217f")

# Captured a533 with pos 14 = 'N' (no network), phase 2 form, for comparison
HEARTBEAT_NN_NO = bytes.fromhex("a5333359323134323035313230344e4effffffffff05cb01ffffffff187f")

# Captured a531 display refresh from M0 t=83.20s: time=0517PM, distance=05.6K
A531_REPLAY = bytes.fromhex("a5312eff303038304d30353137504dffffff30352e364b3031ffffff4c7f")


def fmt(b: bytes) -> str:
    return b.hex() + "   '" + ''.join(chr(c) if 32 <= c <= 126 else '.' for c in b) + "'"


async def run(address: str) -> None:
    t0 = datetime.now(timezone.utc).astimezone()

    def stamp() -> str:
        return f"[+{(datetime.now(timezone.utc).astimezone() - t0).total_seconds():6.2f}s]"

    def on_notify(_s, data: bytearray) -> None:
        print(f"{stamp()} NOTIFY: {fmt(bytes(data))}")

    print(f"Connecting to {address}...")
    async with BleakClient(address) as client:
        print(f"{stamp()} Connected. MTU={client.mtu_size}")

        await client.start_notify(NOTIFY_CHAR_UUID, on_notify)
        print(f"{stamp()} Subscribed")

        # Step 1: identity
        print(f"\n{stamp()} >>> STEP 1: ARJUN identity (establish session)")
        await client.write_gatt_char(WRITE_CHAR_UUID, ARJUN_IDENTITY, response=True)
        print(f"{stamp()}          WRITE OK: {fmt(ARJUN_IDENTITY)}")
        await asyncio.sleep(1.5)

        # Step 2: network-YES heartbeat
        print(f"\n{stamp()} >>> STEP 2: a533 heartbeat with NETWORK = 'Y' (yes)")
        print(f"          payload: {fmt(HEARTBEAT_YN_YES)}")
        await client.write_gatt_char(WRITE_CHAR_UUID, HEARTBEAT_YN_YES, response=True)
        print(f"{stamp()}          WRITE OK")
        print(f"{stamp()}          *** LOOK AT CLUSTER — 'Searching for network' should disappear ***")
        await asyncio.sleep(4)

        # Step 3: another network-YES (some state may need repeated assertion)
        print(f"\n{stamp()} >>> STEP 3: send YES heartbeat again to reinforce")
        await client.write_gatt_char(WRITE_CHAR_UUID, HEARTBEAT_YN_YES, response=True)
        print(f"{stamp()}          WRITE OK")
        await asyncio.sleep(2)

        # Step 4: a531 display
        print(f"\n{stamp()} >>> STEP 4: a531 display content (time=0517PM, dist=05.6K)")
        print(f"          payload: {fmt(A531_REPLAY)}")
        await client.write_gatt_char(WRITE_CHAR_UUID, A531_REPLAY, response=True)
        print(f"{stamp()}          WRITE OK")
        print(f"{stamp()}          *** LOOK AT CLUSTER — does nav area show content / arrow? ***")
        await asyncio.sleep(6)

        # Step 5: more YES heartbeat + a531 to see if it persists
        print(f"\n{stamp()} >>> STEP 5: another YES heartbeat then a531")
        await client.write_gatt_char(WRITE_CHAR_UUID, HEARTBEAT_YN_YES, response=True)
        await asyncio.sleep(0.5)
        await client.write_gatt_char(WRITE_CHAR_UUID, A531_REPLAY, response=True)
        print(f"{stamp()}          WRITE OK both")
        await asyncio.sleep(5)

        await client.stop_notify(NOTIFY_CHAR_UUID)
        print(f"\n{stamp()} Done.")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--address", required=True)
    args = parser.parse_args()
    asyncio.run(run(args.address))


if __name__ == "__main__":
    main()
