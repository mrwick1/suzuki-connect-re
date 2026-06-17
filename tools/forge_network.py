#!/usr/bin/env python3
"""forge_network.py — attempt to defeat the bike's "Searching for network"
gating by forging the cellular-signal bytes identified in the 2026-05-23
WITH-SIM vs NO-SIM differential analysis (see DISCOVERIES.md).

Hypothesis under test (UNVERIFIED — from DISCOVERIES.md 2026-05-23):
- a533 heartbeat with pos 21 = 0x02 (cellular type) AND pos 22 = 0xc9
  (signal strength, -55 dBm) signals "phone has strong cellular".
- a536 identity with pos 27 = 'R' (Registered) signals "registered to network"
  rather than 'F' (Fresh / unregistered).

If correct, the bike should clear the "Searching for network" overlay and
start rendering whatever a531 display content we push.

Behaviour:
- Sends the ARJUN identity (pos 27 patched to 'R', checksum recomputed) ONCE
  at start.
- Then loops at ~1Hz (until Ctrl-C or --duration expires):
    * a533 heartbeat with pos 21=0x02, pos 22=0xc9, pos 14='N' (unchanged —
      pos 14 is NOT the network flag; the YN hypothesis from earlier was
      walked back in DISCOVERIES.md).
    * Session counter at positions 11-13 incremented every loop iteration
      (3 ASCII digits cycling 000-999) — matches the legitimate phone's
      counter behaviour and may prevent the disconnect we saw in
      provoke_and_listen.py with a static counter.
    * Every 3rd iteration also sends the captured a531 display content, so
      the cluster has something to render if the gating clears.

Safety constraints (enforced in code):
- ONLY writes to characteristic 0xFFF1.
- All payloads asserted to be exactly 30 bytes with 0xA5 @ pos 0 and 0x7F
  @ pos 29.
- Checksums recomputed via sum(payload[1:28]) mod 256 — verified algorithm.
- No fuzzing / no "try variations" — only the strict known shapes documented
  above.

Usage:
    python tools/forge_network.py
    python tools/forge_network.py --duration 60 --output /tmp/forge.log
"""
import argparse
import asyncio
import sys
from datetime import datetime, timezone

from bleak import BleakClient

WRITE_CHAR_UUID = "0000fff1-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR_UUID = "0000fff2-0000-1000-8000-00805f9b34fb"

DEFAULT_ADDRESS = "AA:BB:CC:DD:EE:FF"

# ---------------------------------------------------------------------------
# Captured payloads (verbatim from M0 / WITH-SIM analysis)
# ---------------------------------------------------------------------------

# a536 identity push — "ARJUN". Pos 27 = 0x46 ('F') = Fresh/unregistered.
# We'll patch pos 27 -> 0x52 ('R') = Registered, and recompute the checksum.
ARJUN_IDENTITY = bytes.fromhex(
    "a53641524a554e000000000000000000000000000000ffffffffff46f77f"
)

# a533 heartbeat — LATER form ("33Y2142051204NN..."). Pos 21=0x05, pos 22=0xcb
# in the capture. We'll patch pos 21->0x02 and pos 22->0xc9 to forge a strong
# cellular signal, and increment pos 11-13 (ASCII counter) every loop.
HEARTBEAT_LATER_NO_NETWORK = bytes.fromhex(
    "a5333359323134323035313230344e4effffffffff05cb01ffffffff187f"
)

# a531 captured display ("0080M0517PM ... 05.6K01") — sent verbatim every 3rd
# loop iteration so the cluster has content to render if gating clears.
A531_REPLAY = bytes.fromhex(
    "a5312eff303038304d30353137504dffffff30352e364b3031ffffff4c7f"
)

for _p in (ARJUN_IDENTITY, HEARTBEAT_LATER_NO_NETWORK, A531_REPLAY):
    assert len(_p) == 30, f"payload not 30 bytes: {_p.hex()}"
    assert _p[0] == 0xA5, f"payload missing 0xA5 header: {_p.hex()}"
    assert _p[29] == 0x7F, f"payload missing 0x7F trailer: {_p.hex()}"


# ---------------------------------------------------------------------------
# Helpers (match bike_full_diag.py style)
# ---------------------------------------------------------------------------

def csum(p: bytes) -> int:
    """Checksum algorithm: sum(payload[1:28]) mod 256."""
    return sum(p[1:28]) & 0xFF


def fix_csum(p: bytes) -> bytes:
    """Return payload with checksum at pos 28 recomputed."""
    b = bytearray(p)
    b[28] = csum(bytes(b))
    out = bytes(b)
    # Defence in depth — the frame shape must survive every mutation we do.
    assert len(out) == 30
    assert out[0] == 0xA5
    assert out[29] == 0x7F
    return out


def fmt(b: bytes) -> str:
    """Hex + printable-ASCII preview, matching bike_full_diag.py."""
    return b.hex() + "  '" + "".join(chr(c) if 32 <= c <= 126 else "." for c in b) + "'"


def build_registered_identity() -> bytes:
    """ARJUN identity with pos 27 patched from 'F' (0x46) to 'R' (0x52)."""
    b = bytearray(ARJUN_IDENTITY)
    assert b[27] == 0x46, f"expected 'F' at pos 27, got 0x{b[27]:02x}"
    b[27] = 0x52  # 'R' = Registered
    return fix_csum(bytes(b))


def build_forged_heartbeat(counter: int) -> bytes:
    """a533 heartbeat with:
      - pos 11-13: 3 ASCII digits of `counter % 1000` (000-999)
      - pos 14: 'N' (unchanged — pos 14 is NOT the network flag)
      - pos 21: 0x02 (cellular type)
      - pos 22: 0xc9 (strong signal, ~-55 dBm)
    Checksum recomputed.
    """
    b = bytearray(HEARTBEAT_LATER_NO_NETWORK)
    counter_str = f"{counter % 1000:03d}"
    b[11:14] = counter_str.encode("ascii")
    assert b[14] == 0x4E, f"pos 14 should already be 'N', got 0x{b[14]:02x}"
    b[21] = 0x02
    b[22] = 0xC9
    return fix_csum(bytes(b))


# ---------------------------------------------------------------------------
# Main loop
# ---------------------------------------------------------------------------

async def run(address: str, duration: float | None, output_path: str | None) -> None:
    t0 = datetime.now(timezone.utc).astimezone()
    out_file = open(output_path, "w") if output_path else None
    notify_count = [0]
    sent_count = [0]

    def log(line: str) -> None:
        print(line, flush=True)
        if out_file:
            out_file.write(line + "\n")
            out_file.flush()

    def stamp() -> str:
        now = datetime.now(timezone.utc).astimezone()
        rel = (now - t0).total_seconds()
        return f"[{now.strftime('%H:%M:%S.%f')[:-3]} +{rel:7.2f}s]"

    def on_notify(_sender, data: bytearray) -> None:
        notify_count[0] += 1
        log(f"{stamp()} NOTIFY  {fmt(bytes(data))}")

    async def send(label: str, payload: bytes) -> bool:
        # Hard safety gate — never write anything that isn't a well-formed frame.
        assert len(payload) == 30
        assert payload[0] == 0xA5
        assert payload[29] == 0x7F
        log(f"{stamp()} WRITE   {label}")
        log(f"{stamp()}         {fmt(payload)}")
        try:
            await client.write_gatt_char(WRITE_CHAR_UUID, payload, response=True)
            sent_count[0] += 1
            return True
        except Exception as e:  # noqa: BLE001
            log(f"{stamp()}         WRITE FAILED: {type(e).__name__}: {e}")
            return False

    log("=== forge_network.py ===")
    log(f"Start:    {t0.isoformat()}")
    log(f"Target:   {address}")
    log(f"Duration: {'until Ctrl-C' if duration is None else f'{duration}s'}")
    log(f"Output:   {output_path or '(stdout only)'}")
    log("")

    try:
        async with BleakClient(address) as client:
            log(f"{stamp()} Connected. MTU={client.mtu_size}")

            await client.start_notify(NOTIFY_CHAR_UUID, on_notify)
            log(f"{stamp()} Subscribed to {NOTIFY_CHAR_UUID}")
            log("")

            # Send registered-identity ONCE
            identity = build_registered_identity()
            ok = await send("ARJUN identity (pos 27='R' Registered)", identity)
            if not ok:
                log(f"{stamp()} Identity write failed; aborting.")
                return
            await asyncio.sleep(1.0)

            # Loop: forged heartbeat at ~1Hz, a531 every 3rd iter
            log("")
            log(f"{stamp()} Entering 1Hz forge loop "
                f"(a533 pos21=0x02 pos22=0xc9, a531 every 3rd iter)")
            log("")

            loop_start = asyncio.get_event_loop().time()
            i = 0
            while True:
                if duration is not None and (
                    asyncio.get_event_loop().time() - loop_start >= duration
                ):
                    break

                hb = build_forged_heartbeat(i)
                await send(
                    f"a533 forged heartbeat #{i} (counter={i % 1000:03d}, "
                    f"pos21=0x02 pos22=0xc9)",
                    hb,
                )

                if i % 3 == 2:
                    # Small spacing before the second write in the same tick
                    await asyncio.sleep(0.25)
                    await send("a531 captured display replay", A531_REPLAY)

                i += 1
                await asyncio.sleep(1.0)

            await client.stop_notify(NOTIFY_CHAR_UUID)
            log("")
            log(f"{stamp()} === DONE. writes={sent_count[0]}, notifications={notify_count[0]} ===")
    finally:
        if out_file:
            out_file.close()


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Forge cellular-signal bytes to test network-gating bypass on Suzuki Gixxer SF 150."
    )
    parser.add_argument(
        "--address",
        default=DEFAULT_ADDRESS,
        help=f"Bike BLE MAC (default: {DEFAULT_ADDRESS})",
    )
    parser.add_argument(
        "--duration",
        type=float,
        default=None,
        help="Run for N seconds, then stop. Default: run until Ctrl-C.",
    )
    parser.add_argument(
        "--output",
        default=None,
        help="Mirror stdout to this log file.",
    )
    args = parser.parse_args()

    try:
        asyncio.run(run(args.address, args.duration, args.output))
    except KeyboardInterrupt:
        print("\n(interrupted by user)", flush=True)
        sys.exit(0)


if __name__ == "__main__":
    main()
