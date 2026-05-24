"""
Sweep every BLE write + notify in a pcap through protocol.decode() and
report any frames that fail to decode (bad checksum, unknown type, etc.).

Also round-trips writeable frames (a531, a533, a536) and warns if any
captured frame doesn't reproduce byte-identically after decode -> encode.

Usage:
    python tools/validate_pcap.py captures/<file>.pcap
"""

import subprocess
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from protocol import (  # noqa: E402
    FRAME_LEN,
    FrameType,
    HeartbeatFrame,
    IdentityFrame,
    NavFrame,
    decode,
    is_well_formed,
)

ROUND_TRIPPABLE = {
    FrameType.NAV: NavFrame,
    FrameType.HEARTBEAT: HeartbeatFrame,
    FrameType.IDENTITY: IdentityFrame,
}


def extract_frames(pcap: str) -> list[bytes]:
    """Pull every ATT Write Request/Command + Handle Value Notification."""
    out = subprocess.check_output(
        [
            "tshark",
            "-r",
            pcap,
            "-Y",
            "btatt.opcode == 0x12 or btatt.opcode == 0x52 or btatt.opcode == 0x1b",
            "-T",
            "fields",
            "-e",
            "btatt.value",
        ],
        stderr=subprocess.DEVNULL,
    ).decode("ascii")
    frames = []
    for line in out.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            frame = bytes.fromhex(line)
        except ValueError:
            continue
        if len(frame) == FRAME_LEN:
            frames.append(frame)
    return frames


def validate(pcap: str) -> int:
    print(f"=== {pcap} ===")
    frames = extract_frames(pcap)
    print(f"Extracted {len(frames)} 30-byte BLE payloads")

    type_counts: Counter = Counter()
    integrity_failures = []
    decode_failures = []
    roundtrip_failures = []
    decoded_samples = {}

    for frame in frames:
        type_counts[frame[1]] += 1

        if not is_well_formed(frame):
            integrity_failures.append(frame)
            continue

        try:
            obj = decode(frame)
        except Exception as e:
            decode_failures.append((frame, e))
            continue

        # Remember one representative decoded frame per type
        if frame[1] not in decoded_samples:
            decoded_samples[frame[1]] = obj

        # Round-trip check (writeable types only)
        try:
            type_enum = FrameType(frame[1])
        except ValueError:
            continue
        if type_enum in ROUND_TRIPPABLE:
            re = obj.encode()
            if re != frame:
                roundtrip_failures.append((frame, re))

    print("\nType-byte distribution:")
    for tb, ct in sorted(type_counts.items()):
        try:
            name = FrameType(tb).name
        except ValueError:
            name = "?"
        print(f"  0x{tb:02x} {name:11s}  {ct:>5}")

    print(f"\nIntegrity failures (bad header/terminator/checksum): {len(integrity_failures)}")
    for f in integrity_failures[:3]:
        print(f"  {f.hex()}")

    print(f"\nDecode failures: {len(decode_failures)}")
    for f, e in decode_failures[:3]:
        print(f"  {f.hex()}  -> {type(e).__name__}: {e}")

    print(f"\nRound-trip mismatches: {len(roundtrip_failures)}")
    for orig, re in roundtrip_failures[:3]:
        print(f"  orig: {orig.hex()}")
        print(f"  encd: {re.hex()}")
        diffs = [i for i, (a, b) in enumerate(zip(orig, re)) if a != b]
        print(f"  bytes differing: {diffs}")

    print("\nSample decoded frames:")
    for tb, obj in sorted(decoded_samples.items()):
        print(f"  {FrameType(tb).name:11s}: {obj!r}")

    bad = len(integrity_failures) + len(decode_failures) + len(roundtrip_failures)
    print(f"\n{'OK' if bad == 0 else 'FAIL'}: {bad} issue(s) across {len(frames)} frames\n")
    return bad


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    exit_code = 0
    for pcap in sys.argv[1:]:
        exit_code += validate(pcap)
    sys.exit(0 if exit_code == 0 else 1)
