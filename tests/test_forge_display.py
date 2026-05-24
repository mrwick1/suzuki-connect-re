"""
Unit tests for tools/forge_display.py — the Phase 3 Branch A custom-display CLI.

These tests do NOT need the bike. They exercise the frame builders, the
text-shortcut layout, the dry-run CLI path, and round-trip every forged frame
through tools/protocol.py to confirm validity (header / checksum / terminator
all correct).
"""

import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tools"))

from forge_display import (  # noqa: E402
    build_heartbeat,
    build_identity,
    build_nav,
    build_text,
)
from protocol import (  # noqa: E402
    FRAME_LEN,
    HeartbeatFrame,
    IdentityFrame,
    NavFrame,
    decode,
    is_well_formed,
)


# ---------------------------------------------------------------------------
# build_nav: every forged a531 must be valid + decode to the inputs
# ---------------------------------------------------------------------------


def test_build_nav_basic():
    f = build_nav(8, "0150", "M", "0830PM", "0500", "M", "1", "1")
    assert len(f) == FRAME_LEN
    assert is_well_formed(f)
    d = NavFrame.decode(f)
    assert d.maneuver_id == 8
    assert d.dist_next == "0150"
    assert d.dist_next_unit == "M"
    assert d.eta == "0830PM"
    assert d.dist_total == "0500"
    assert d.dist_total_unit == "M"
    assert d.status == "1"
    assert d.continue_flag == "1"


def test_build_nav_pads_short_strings():
    """Inputs shorter than expected get '0'-right-padded."""
    f = build_nav(8, "12", "M", "abc", "5", "K", "1", "1")
    d = NavFrame.decode(f)
    assert d.dist_next == "1200"
    assert d.eta == "abc000"
    assert d.dist_total == "5000"


def test_build_nav_truncates_long_strings():
    f = build_nav(8, "12345", "M", "1234567", "9999", "M", "1", "1")
    d = NavFrame.decode(f)
    assert d.dist_next == "1234"
    assert d.eta == "123456"


def test_build_nav_arrow_out_of_range_truncated():
    """maneuver_id is masked to a single byte."""
    f = build_nav(0x108, "0000", "M", "000000", "0000", "M", "1", "1")
    d = NavFrame.decode(f)
    assert d.maneuver_id == 0x08


def test_build_nav_status_5_destination_reached():
    """Forging the bike into 'destination reached' display."""
    f = build_nav(9, "0000", "M", "0830PM", "0000", "M", "5", "1")
    d = NavFrame.decode(f)
    assert d.status == "5"
    assert d.dist_next == "0000"
    assert d.dist_total == "0000"


# ---------------------------------------------------------------------------
# build_text: 4+6+4 char layout convenience
# ---------------------------------------------------------------------------


def test_build_text_lays_out_three_slots():
    f = build_text("HELL", "OARJUN", "RIDE")
    assert is_well_formed(f)
    d = NavFrame.decode(f)
    assert d.dist_next == "HELL"
    assert d.eta == "OARJUN"
    assert d.dist_total == "RIDE"


def test_build_text_default_arrow():
    f = build_text("AAAA", "BBBBBB", "CCCC")
    d = NavFrame.decode(f)
    assert d.maneuver_id == 8


def test_build_text_overflow_truncates_silently():
    f = build_text("TOOLONG", "ALSOTOOLONG", "ANOTHER")
    d = NavFrame.decode(f)
    assert d.dist_next == "TOOL"
    assert d.eta == "ALSOTO"
    assert d.dist_total == "ANOT"


# ---------------------------------------------------------------------------
# build_heartbeat: a533 with custom env fields
# ---------------------------------------------------------------------------


def test_build_heartbeat_round_trip():
    f = build_heartbeat(
        battery="2", charging="N", speed="045", signal="2",
        sms_pending="Y", call_pending="N", weather=6, temp_c=25.0,
    )
    assert is_well_formed(f)
    d = HeartbeatFrame.decode(f)
    assert d.battery_bucket == "2"
    assert d.charging == "N"
    assert d.signal_status == "2"
    assert d.sms_pending == "Y"
    assert d.call_pending == "N"
    assert d.weather == 6
    # temp 25°C -> Fahrenheit ceil(45+32) = 77 -> byte = 77+115 = 192
    assert d.temp_f_plus_115 == 192


def test_build_heartbeat_extreme_temp_clamped():
    """Sub-freezing temp encodes correctly thanks to +115 offset."""
    f = build_heartbeat(temp_c=-40.0)
    d = HeartbeatFrame.decode(f)
    # -40°C = -40°F -> byte = -40 + 115 = 75
    assert d.temp_f_plus_115 == 75


def test_build_heartbeat_weather_codes_in_range():
    """All 12 documented weather codes (0-11) encode without error."""
    for w in range(12):
        f = build_heartbeat(weather=w)
        d = HeartbeatFrame.decode(f)
        assert d.weather == w


# ---------------------------------------------------------------------------
# build_identity: a536 with custom display name
# ---------------------------------------------------------------------------


def test_build_identity_default_not_fresh():
    f = build_identity("CUSTOM_USER")
    d = IdentityFrame.decode(f)
    assert d.name == "CUSTOM_USER"
    assert d.is_fresh is False


def test_build_identity_fresh_flag():
    f = build_identity("ARJUN", is_fresh=True)
    d = IdentityFrame.decode(f)
    assert d.is_fresh is True


def test_build_identity_truncates_long_names():
    f = build_identity("X" * 50)
    d = IdentityFrame.decode(f)
    assert len(d.name) == 20  # protocol max
    assert d.name == "X" * 20


# ---------------------------------------------------------------------------
# Decode-dispatch sanity: forged frames are recognised by protocol.decode
# ---------------------------------------------------------------------------


def test_decode_dispatch_recognizes_all_forged_types():
    assert isinstance(decode(build_nav(8, "0150", "M", "0830PM", "0500", "M", "1", "1")),
                      NavFrame)
    assert isinstance(decode(build_heartbeat()), HeartbeatFrame)
    assert isinstance(decode(build_identity("X")), IdentityFrame)


# ---------------------------------------------------------------------------
# CLI smoke tests (dry-run, no bike)
# ---------------------------------------------------------------------------


REPO = Path(__file__).resolve().parent.parent
PY = sys.executable
CLI = [PY, str(REPO / "tools" / "forge_display.py")]


def _run(*argv) -> subprocess.CompletedProcess:
    """Invoke the CLI with --dry-run and return the completed process."""
    return subprocess.run(CLI + list(argv) + ["--dry-run"],
                          capture_output=True, text=True, cwd=str(REPO))


def test_cli_nav_dryrun():
    r = _run("nav", "--arrow", "1", "--dist-next", "0100", "--eta", "1234PM")
    assert r.returncode == 0, r.stderr
    assert "Frame (30 bytes):" in r.stdout
    assert "NavFrame" in r.stdout


def test_cli_text_dryrun():
    r = _run("text", "HELL", "OARJUN", "RIDE")
    assert r.returncode == 0, r.stderr
    assert "HELL" in r.stdout
    assert "OARJUN" in r.stdout


def test_cli_heartbeat_dryrun():
    r = _run("heartbeat", "--battery", "0", "--charging", "N",
             "--weather", "11", "--temp-c", "-5")
    assert r.returncode == 0, r.stderr
    assert "HeartbeatFrame" in r.stdout


def test_cli_identity_dryrun():
    r = _run("identity", "FAKE_USER")
    assert r.returncode == 0, r.stderr
    assert "IdentityFrame" in r.stdout
    assert "FAKE_USER" in r.stdout


def test_cli_help_lists_all_subcommands():
    r = subprocess.run(CLI + ["--help"], capture_output=True, text=True, cwd=str(REPO))
    assert r.returncode == 0
    for sub in ("nav", "text", "heartbeat", "identity"):
        assert sub in r.stdout
