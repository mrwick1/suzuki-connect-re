"""
Ride summary — one-shot post-ride report for a pcap.

Decodes every BLE write+notify in the file via tools/protocol.py, then
prints a structured summary covering:

- Frame-type distribution
- Capture window (start time, end time, duration)
- a531 NAV: maneuver-ID distribution + status-digit distribution
- a532/a534/a535: call + SMS event timeline
- a536 IDENTITY: reconnect events (long inter-frame gaps)
- a537 TELEMETRY: odometer delta, speed timeline, fuel bar values,
                  fuel-economy range (excluding 0xFFFFFF sentinels)
- a533 HEARTBEAT: battery/charging/cell-signal/weather/temperature
                  observed values

Usage:
    python tools/ride_summary.py captures/ride-<tag>.pcap
"""

import subprocess
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from protocol import (  # noqa: E402
    FRAME_LEN,
    FrameType,
    decode,
)


def extract_frames(pcap: str) -> list[tuple[float, bytes]]:
    """Pull (relative_time, bytes) for every 30-byte ATT write/notify."""
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
            "frame.time_relative",
            "-e",
            "btatt.value",
        ],
        stderr=subprocess.DEVNULL,
    ).decode()
    frames = []
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) != 2:
            continue
        try:
            t = float(parts[0])
            raw = bytes.fromhex(parts[1])
            if len(raw) == FRAME_LEN:
                frames.append((t, raw))
        except (ValueError, TypeError):
            continue
    return frames


def hms(seconds: float) -> str:
    """Format seconds as H:MM:SS."""
    h, rem = divmod(int(seconds), 3600)
    m, s = divmod(rem, 60)
    return f"{h}:{m:02d}:{s:02d}"


STATUS_MEANING = {
    "0": "no signal / airplane mode",
    "1": "normal nav",
    "2": "route recalculating",
    "3": "via-point reached",
    "4": "GPS lost",
    "5": "destination reached (sticky)",
    "6": "phone offline",
}


def section(title: str) -> None:
    print()
    print(f"-- {title} " + "-" * (78 - len(title)))


def main(pcap: str) -> int:
    print(f"== {pcap}")
    frames = extract_frames(pcap)
    if not frames:
        print("No 30-byte BLE frames found in pcap.")
        return 1

    t0 = frames[0][0]
    t_end = frames[-1][0]
    duration = t_end - t0
    by_type: Counter = Counter()
    decoded: dict[int, list] = {}
    for t, raw in frames:
        by_type[raw[1]] += 1
        try:
            obj = decode(raw)
            decoded.setdefault(raw[1], []).append((t - t0, obj))
        except Exception:
            pass

    section("capture window")
    print(f"  frames: {len(frames)}")
    print(f"  start (relative): {t0:.1f}s")
    print(f"  end (relative):   {t_end:.1f}s")
    print(f"  duration:         {hms(duration)}  ({duration:.1f}s)")

    section("frame-type distribution")
    for tb, cnt in sorted(by_type.items()):
        try:
            name = FrameType(tb).name
        except ValueError:
            name = "?"
        pct = 100.0 * cnt / len(frames)
        print(f"  0x{tb:02x} {name:13s}  {cnt:>5}   {pct:5.1f}%")

    # ----- a531 NAV ---------------------------------------------------------
    nav = decoded.get(FrameType.NAV, [])
    if nav:
        section(f"a531 NAV  ({len(nav)} frames)")
        maneuvers: Counter = Counter()
        statuses: Counter = Counter()
        for _t, f in nav:
            maneuvers[f.maneuver_id] += 1
            statuses[f.status] += 1
        print("  maneuver IDs (top 12):")
        for mid, n in maneuvers.most_common(12):
            char = chr(mid) if 0x20 <= mid < 0x7F else "?"
            tag = " (degraded placeholder)" if mid == 0x2E else ""
            print(f"    0x{mid:02x} ({mid:>3}) '{char}'  : {n:>5}{tag}")
        print("  status digits:")
        for s in sorted(statuses):
            meaning = STATUS_MEANING.get(s, "?")
            pct = 100.0 * statuses[s] / len(nav)
            print(f"    {s!r}  ({pct:5.1f}%)  {statuses[s]:>5}  — {meaning}")

    # ----- a532 / a534 / a535 call + sms ------------------------------------
    events = []
    for tb in (FrameType.CALL, FrameType.MISSED_CALL, FrameType.SMS):
        for t, f in decoded.get(tb, []):
            events.append((t, type(f).__name__, f))
    if events:
        events.sort()
        section(f"phone events  (a532/a534/a535)  — {len(events)} frames, deduped by content:")
        seen_keys = set()
        for t, name, f in events:
            key = (name, repr(f).replace(", raw=", ""))
            if key in seen_keys:
                continue
            seen_keys.add(key)
            print(f"  t={hms(t)} {name}: {f!r}".replace(", raw=None", ""))

    # ----- a536 IDENTITY (reconnect timeline) -------------------------------
    ids = decoded.get(FrameType.IDENTITY, [])
    if ids:
        section(f"a536 IDENTITY  ({len(ids)} frames)")
        fresh = sum(1 for _, f in ids if f.is_fresh)
        reconn = len(ids) - fresh
        print(f"  fresh ('F'): {fresh}    reconnect ('R'): {reconn}")
        gaps = [
            (ids[i][0], ids[i][0] - ids[i - 1][0]) for i in range(1, len(ids))
        ]
        big = [(t, g) for t, g in gaps if g > 5.0]
        print(f"  inter-frame gaps > 5s (likely real reconnect events): {len(big)}")
        for t, g in big:
            print(f"    at t={hms(t)}  gap={g:.1f}s")
        print(f"  display names: {set(f.name for _, f in ids)}")

    # ----- a537 TELEMETRY ---------------------------------------------------
    tel = decoded.get(FrameType.TELEMETRY, [])
    if tel:
        section(f"a537 TELEMETRY  ({len(tel)} frames — bike → phone)")
        odo_vals = sorted({f.odometer_km for _, f in tel if f.odometer_km > 0})
        if odo_vals:
            print(f"  odometer:  {odo_vals[0]} km  →  {odo_vals[-1]} km   (Δ {odo_vals[-1] - odo_vals[0]} km)")
        speeds = sorted({f.speed_kmh for _, f in tel})
        print(f"  speed (km/h) values seen: min={speeds[0]}  max={speeds[-1]}  distinct={len(speeds)}")
        fuel = Counter(f.fuel_bars for _, f in tel)
        print(f"  fuel bars: {dict(fuel)}")
        fes = [f.fuel_econ_kml for _, f in tel if f.fuel_econ_kml is not None]
        if fes:
            print(f"  fuel-econ km/L (excl. 0xFFFFFF sentinel): n={len(fes)}  "
                  f"min={min(fes):.1f}  max={max(fes):.1f}  median={sorted(fes)[len(fes) // 2]:.1f}")
        trip_a_vals = sorted({round(f.trip_a_km, 1) for _, f in tel})
        trip_b_vals = sorted({round(f.trip_b_km, 1) for _, f in tel})
        print(f"  trip A: {trip_a_vals[0]} → {trip_a_vals[-1]} ({len(trip_a_vals)} distinct)")
        print(f"  trip B: {trip_b_vals[0]} → {trip_b_vals[-1]} ({len(trip_b_vals)} distinct)")

    # ----- a533 HEARTBEAT ---------------------------------------------------
    hb = decoded.get(FrameType.HEARTBEAT, [])
    if hb:
        section(f"a533 HEARTBEAT  ({len(hb)} frames — phone → bike)")
        batts = Counter((f.battery_bucket, f.charging) for _, f in hb)
        print(f"  phone battery (bucket, charging) distribution:")
        for (b, c), n in batts.most_common():
            print(f"    bucket='{b}' charging='{c}': {n}")
        sigs = Counter(f.signal_status for _, f in hb)
        print(f"  cell signal_status: {dict(sigs)}")
        sms = Counter(f.sms_pending for _, f in hb)
        calls = Counter(f.call_pending for _, f in hb)
        print(f"  SMS pending flag: {dict(sms)}    Call pending flag: {dict(calls)}")
        weathers = Counter(f.weather for _, f in hb)
        temps = sorted({f.temp_f_plus_115 for _, f in hb if f.temp_f_plus_115 > 0})
        print(f"  weather codes: {dict(weathers)}")
        if temps:
            print(f"  temperature byte (F+115 encoded): min={temps[0]} max={temps[-1]}  "
                  f"→ {temps[0] - 115}°F to {temps[-1] - 115}°F")
        else:
            print(f"  temperature byte: all unset (weather API never fired during capture)")

    print()
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
