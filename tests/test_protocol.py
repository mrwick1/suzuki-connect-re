"""
Round-trip tests for tools/protocol.py against real captured frames.

Frames here are pulled verbatim from the M0 pcaps:
  captures/m0-pairing-and-first-nav-20260523-1712.pcap
  captures/m0-with-2-nav-20260523-1719.pcap

via:
  tshark -r <pcap> -Y "btatt.opcode == 0x12 or btatt.opcode == 0x52" \
         -T fields -e btatt.value

(0x12 = ATT Write Request, 0x52 = Write Command, 0x1b = Handle Value Notify).

Each captured frame is documented with the expected decode at the top of
the test, so failures are easy to diagnose.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tools"))

import pytest

from protocol import (  # noqa: E402 — sys.path insert above
    CallFrame,
    FRAME_LEN,
    HEADER,
    HeartbeatFrame,
    IdentityFrame,
    MissedCallFrame,
    NavFrame,
    SmsFrame,
    TERMINATOR,
    TelemetryFrame,
    checksum,
    decode,
    is_well_formed,
)


# ---------------------------------------------------------------------------
# Generic frame integrity
# ---------------------------------------------------------------------------


def _h(s: str) -> bytes:
    return bytes.fromhex(s)


SAMPLE_A531_DEGRADED = _h("a5312eff303038304d30353137504dffffff30352e364b3031ffffff4c7f")
SAMPLE_A531_REAL = _h("a53108ff303132304d30353238504dffffff30352e314b3131ffffff1f7f")
SAMPLE_A533 = _h("a5333359323134003035303135344e4effffffffff010001ffffffff1a7f")
SAMPLE_A536 = _h("a53641524a554e000000000000000000000000000000ffffffffff46f77f")
SAMPLE_A537 = _h("a5373030303031363732393034393131303030393834394e3439ffff267f")


def test_frame_constants():
    assert HEADER == 0xA5
    assert TERMINATOR == 0x7F
    assert FRAME_LEN == 30


def test_checksum_known_a531():
    # sum(bytes[1:28]) mod 256 for the degraded a531 sample = 0x4c
    assert checksum(SAMPLE_A531_DEGRADED) == 0x4C


def test_checksum_known_a533():
    assert checksum(SAMPLE_A533) == 0x1A


def test_checksum_known_a536():
    assert checksum(SAMPLE_A536) == 0xF7


def test_is_well_formed_passes_for_real_frames():
    for f in [SAMPLE_A531_DEGRADED, SAMPLE_A531_REAL, SAMPLE_A533, SAMPLE_A536, SAMPLE_A537]:
        assert is_well_formed(f), f"frame failed integrity check: {f.hex()}"


def test_is_well_formed_rejects_bad_header():
    bad = bytearray(SAMPLE_A531_REAL)
    bad[0] = 0x00
    assert not is_well_formed(bytes(bad))


def test_is_well_formed_rejects_bad_terminator():
    bad = bytearray(SAMPLE_A531_REAL)
    bad[29] = 0x00
    assert not is_well_formed(bytes(bad))


def test_is_well_formed_rejects_bad_checksum():
    bad = bytearray(SAMPLE_A531_REAL)
    bad[28] ^= 0x01
    assert not is_well_formed(bytes(bad))


def test_is_well_formed_rejects_wrong_length():
    assert not is_well_formed(SAMPLE_A531_REAL[:29])
    assert not is_well_formed(SAMPLE_A531_REAL + b"\x00")


# ---------------------------------------------------------------------------
# a531 NAV
# ---------------------------------------------------------------------------


def test_a531_degraded_decode():
    """
    a5 31 2e ff 30 30 38 30 4d 30 35 31 37 50 4d ff ff ff 30 35 2e 36 4b 30 31 ff ff ff 4c 7f
       a531 .  pad p0=0080 M  n0=0517PM         pad q0=05.6 K  s 1  pad        chk end
    """
    f = NavFrame.decode(SAMPLE_A531_DEGRADED)
    assert f.maneuver_id == 0x2E  # degraded-mode placeholder
    assert f.dist_next == "0080"
    assert f.dist_next_unit == "M"
    assert f.eta == "0517PM"
    assert f.dist_total == "05.6"
    assert f.dist_total_unit == "K"
    assert f.status == "0"  # no signal
    assert f.continue_flag == "1"


def test_a531_real_maneuver_decode():
    """Real maneuver — byte 2 = 0x08 means Mappls maneuver ID 8."""
    f = NavFrame.decode(SAMPLE_A531_REAL)
    assert f.maneuver_id == 0x08
    assert f.dist_next == "0120"
    assert f.eta == "0528PM"
    assert f.dist_total == "05.1"
    assert f.dist_total_unit == "K"
    assert f.status == "1"  # signal good
    assert f.continue_flag == "1"


def test_a531_dispatch():
    f = decode(SAMPLE_A531_REAL)
    assert isinstance(f, NavFrame)


def test_a531_roundtrip_degraded():
    f = NavFrame.decode(SAMPLE_A531_DEGRADED)
    re = f.encode()
    assert re == SAMPLE_A531_DEGRADED


def test_a531_roundtrip_real():
    f = NavFrame.decode(SAMPLE_A531_REAL)
    re = f.encode()
    assert re == SAMPLE_A531_REAL


# ---------------------------------------------------------------------------
# a533 HEARTBEAT
# ---------------------------------------------------------------------------


def test_a533_decode():
    """
    a5 33 33 59 32 31 34 00 30 35 30 31 35 34 4e 4e ff ff ff ff ff 01 00 01 ff ff ff ff 1a 7f
       a533 3 Y 2 1 4 0  0 5 0 1 5 4 N N ff×5             wx tF 1  ff×4       chk end

    Decoded:
      - battery_bucket='3', charging='Y'   → phone at 75-100% on charger
      - speed_str='214'                    → stale prefs value (engine off in M0)
      - signal_status='0'                  → no cell signal during capture
      - time_hhmmss='050154'               → 5:01:54 PM
      - sms_pending='N', call_pending='N'  → no pending events
      - weather=1                          → "sunny" (default-ish)
      - temp_f_plus_115=0                  → temperature field unset (weather data not yet pulled)
      - tail_const=1                       → constant
    """
    f = HeartbeatFrame.decode(SAMPLE_A533)
    assert f.battery_bucket == "3"
    assert f.charging == "Y"
    assert f.speed_str == "214"
    assert f.signal_status == "0"
    assert f.time_hhmmss == "050154"
    assert f.sms_pending == "N"
    assert f.call_pending == "N"
    assert f.weather == 1
    assert f.temp_f_plus_115 == 0
    assert f.tail_const == 1
    # temp_celsius property returns None when temp field is unset
    assert f.temp_celsius is None


def test_a533_temp_decode_roundtrip():
    """Test the temperature decode: 27°C should encode to ceil(80.6)+115 = 196."""
    import math
    fahrenheit = math.ceil((9 * 27) / 5 + 32)  # 81
    f = HeartbeatFrame(
        battery_bucket="3",
        charging="Y",
        speed_str="",
        signal_status="0",
        time_hhmmss="120000",
        sms_pending="N",
        call_pending="N",
        weather=1,
        temp_f_plus_115=fahrenheit + 115,
    )
    assert f.temp_f_plus_115 == 196
    # round-trip
    out = f.encode()
    decoded = HeartbeatFrame.decode(out)
    assert decoded.temp_f_plus_115 == 196
    # Celsius decode: 196 → F=81 → C=(81-32)*5/9 ≈ 27.22
    assert decoded.temp_celsius is not None
    assert abs(decoded.temp_celsius - 27.22) < 0.1


def test_a533_roundtrip():
    f = HeartbeatFrame.decode(SAMPLE_A533)
    re = f.encode()
    assert re == SAMPLE_A533


def test_a533_dispatch():
    f = decode(SAMPLE_A533)
    assert isinstance(f, HeartbeatFrame)


def test_a533_encode_speed_zero_uses_ff_padding():
    """When speed_str is empty, encoder should emit 0xFF×3 (matches source override)."""
    f = HeartbeatFrame(
        battery_bucket="3",
        charging="Y",
        speed_str="",
        signal_status="0",
        time_hhmmss="050154",
        sms_pending="N",
        call_pending="N",
    )
    out = f.encode()
    assert out[4:7] == b"\xff\xff\xff"


# ---------------------------------------------------------------------------
# a536 IDENTITY
# ---------------------------------------------------------------------------


def test_a536_decode():
    """
    a5 36 41 52 4a 55 4e 00*15 ff*5 46 f7 7f
       a536 A R J U N  NUL padding   FF×5 F  chk end
    """
    f = IdentityFrame.decode(SAMPLE_A536)
    assert f.name == "ARJUN"
    assert f.is_fresh is True  # byte 27 = 0x46 = 'F'


def test_a536_roundtrip():
    f = IdentityFrame.decode(SAMPLE_A536)
    re = f.encode()
    assert re == SAMPLE_A536


def test_a536_dispatch():
    f = decode(SAMPLE_A536)
    assert isinstance(f, IdentityFrame)


def test_a536_reconnect_flag_round_trips():
    f = IdentityFrame(name="ARJUN", is_fresh=False)
    out = f.encode()
    decoded = IdentityFrame.decode(out)
    assert decoded.name == "ARJUN"
    assert decoded.is_fresh is False
    assert out[27] == 0x52  # 'R'


# ---------------------------------------------------------------------------
# a537 TELEMETRY (bike -> phone notify)
# ---------------------------------------------------------------------------


def test_a537_decode_engine_off():
    """
    Real captured a537 from M0 (engine off, no nav active):
      a5 37 30 30 30 30 31 36 37 32 39 30 34 39 31 31 30 30 30 39 38 34 39 4e 34 39 ff ff 26 7f
      ^  ^  ^----^  ^----------^   ^----------^   ^----------^   ^   ^   ^------^
      hdr 7 speed  odometer       trip A         trip B         ?   fuel fuel-econ

    - speed = "000" = 0 km/h (engine off)
    - odometer = "016729" → 16729 km
    - trip A bytes = "049110" → 04911.0 km
    - trip B bytes = "009849" → 00984.9 km
    - byte 23 = 0x4e ('N')
    - byte 24 = '4' → 4 fuel bars
    - bytes 25-27 = 0x39 0xff 0xff (NOT the all-FF sentinel, so decoder
      will compute a value — likely garbage since engine is off)
    """
    f = TelemetryFrame.decode(SAMPLE_A537)
    assert f.speed_kmh == 0
    assert f.odometer_km == 16729
    assert f.trip_a_km == 4911.0
    assert f.trip_b_km == 984.9
    assert f.byte_23 == 0x4E
    assert f.fuel_bars == 4
    # Not a full-FF sentinel — decoder produces a (garbage) value
    assert f.fuel_econ_kml is not None


def test_a537_decode_full_sentinel_fuel_econ():
    """All-0xFF fuel-econ bytes → None (engine off, no data)."""
    raw = bytearray(SAMPLE_A537)
    raw[25:28] = b"\xff\xff\xff"
    raw[28] = checksum(bytes(raw))
    f = TelemetryFrame.decode(bytes(raw))
    assert f.fuel_econ_kml is None


def test_a537_dispatch():
    f = decode(SAMPLE_A537)
    assert isinstance(f, TelemetryFrame)


def test_a537_roundtrip_basic_construct():
    """Synthesize a clean a537 (e.g. for a future telemetry-emitting tool)."""
    f = TelemetryFrame(
        speed_kmh=42,
        odometer_km=16729,
        trip_a_km=123.4,
        trip_b_km=56.7,
        fuel_bars=4,
        fuel_econ_kml=50.5,
    )
    out = f.encode()
    assert len(out) == FRAME_LEN
    assert is_well_formed(out)
    decoded = TelemetryFrame.decode(out)
    assert decoded.speed_kmh == 42
    assert decoded.odometer_km == 16729
    assert abs(decoded.trip_a_km - 123.4) < 0.001
    assert abs(decoded.trip_b_km - 56.7) < 0.001
    assert decoded.fuel_bars == 4
    # Fuel econ may have small precision loss due to 11-bit fractional
    assert abs((decoded.fuel_econ_kml or 0) - 50.5) < 0.01


# ---------------------------------------------------------------------------
# Synthesized a532 / a534 / a535 — round-trip only (no captured fixtures yet)
# ---------------------------------------------------------------------------


def test_a532_roundtrip_cellular():
    f = CallFrame(number="+919876543210", is_whatsapp=False, state=ord("1"))
    out = f.encode()
    assert is_well_formed(out)
    assert out[22] == 0x4E  # 'N' cellular
    decoded = CallFrame.decode(out)
    assert decoded.number == "+919876543210"
    assert decoded.is_whatsapp is False
    assert decoded.state == ord("1")


def test_a532_roundtrip_whatsapp():
    f = CallFrame(number="+919876543210", is_whatsapp=True, state=ord("2"))
    out = f.encode()
    assert is_well_formed(out)
    assert out[22] == 0x57  # 'W' WhatsApp
    decoded = CallFrame.decode(out)
    assert decoded.is_whatsapp is True
    assert decoded.state == ord("2")


def test_a534_roundtrip_cellular():
    f = MissedCallFrame(name="MOM", missed_count=2, is_whatsapp=False)
    out = f.encode()
    assert is_well_formed(out)
    assert out[24] == 0x4E
    # The source template prefixes name with literal "Y1" — verify our encoder does too
    assert out[4:6] == b"Y1"
    decoded = MissedCallFrame.decode(out)
    assert decoded.name == "MOM"
    assert decoded.missed_count == 2
    assert decoded.is_whatsapp is False


def test_a534_roundtrip_whatsapp():
    f = MissedCallFrame(name="Boss", missed_count=5, is_whatsapp=True)
    out = f.encode()
    assert is_well_formed(out)
    assert out[24] == 0x57
    decoded = MissedCallFrame.decode(out)
    assert decoded.name == "Boss"
    assert decoded.missed_count == 5
    assert decoded.is_whatsapp is True


def test_a535_roundtrip_silenced():
    f = SmsFrame(sender="John", message_count=3, silenced=True)
    out = f.encode()
    assert is_well_formed(out)
    assert out[3] == 0x4E  # 'N' silenced
    assert out[4] == 3
    decoded = SmsFrame.decode(out)
    assert decoded.sender == "John"
    assert decoded.message_count == 3
    assert decoded.silenced is True


def test_a535_roundtrip_not_silenced():
    f = SmsFrame(sender="Alice", message_count=1, silenced=False, type_byte=0x57)
    out = f.encode()
    assert is_well_formed(out)
    assert out[3] == 0x59  # 'Y' not silenced
    decoded = SmsFrame.decode(out)
    assert decoded.silenced is False
    assert decoded.type_byte == 0x57


# ---------------------------------------------------------------------------
# Dispatcher errors
# ---------------------------------------------------------------------------


def test_decode_rejects_wrong_length():
    with pytest.raises(ValueError):
        decode(SAMPLE_A531_REAL[:29])


def test_decode_rejects_unknown_type():
    bad = bytearray(SAMPLE_A531_REAL)
    bad[1] = 0x39  # '9' — not a known type
    bad[28] = checksum(bytes(bad))
    with pytest.raises(ValueError):
        decode(bytes(bad))


# Make this file runnable directly (without pytest), for quick sanity check
if __name__ == "__main__":
    g = globals().copy()
    failed = 0
    passed = 0
    for name, fn in sorted(g.items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                passed += 1
                print(f"  ok  {name}")
            except Exception as e:
                failed += 1
                print(f"FAIL  {name}: {type(e).__name__}: {e}")
    print(f"\n{passed} passed, {failed} failed")
    sys.exit(1 if failed else 0)
