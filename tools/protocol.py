"""
Suzuki Connect BLE protocol — encoder/decoder for the 7 frame types.

All frames are 30 bytes:
  byte[0]    = 0xA5 (header)
  byte[1]    = type byte ('1'-'7' ASCII = 0x31-0x37)
  byte[2:28] = body (varies by type)
  byte[28]   = checksum = sum(bytes[1:28]) mod 256
  byte[29]   = 0x7F (end marker)

Phone -> Bike (writes on 0xFFF1):
  a531 NAV         turn arrow + distance + ETA + status (continuous during nav)
  a532 CALL        incoming-call notification (caller's phone number)
  a533 HEARTBEAT   phone battery + speed + time + SMS/call flags (~1 Hz)
  a534 MISSED_CALL contact name + missed-count (event-driven)
  a535 SMS         SMS/WhatsApp notification (event-driven)
  a536 IDENTITY    user's display name + fresh-vs-reconnect flag (on pair)

Bike -> Phone (notify on 0xFFF2):
  a537 TELEMETRY   speed + odo + trip A + trip B + fuel + fuel economy (~5s)

See DISCOVERIES.md (2026-05-23 / 2026-05-24 entries) for source-level
derivation of each field. The fuel-economy decode for a537 uses the
default petrol formula (Gixxer SF 150 / most Suzuki bikes). Access-TFT /
Burgman-TFT / e-ACCESS variants use different formulas — not handled here.
"""

from dataclasses import dataclass, field
from enum import IntEnum
from typing import Optional

FRAME_LEN = 30
HEADER = 0xA5
TERMINATOR = 0x7F


class FrameType(IntEnum):
    NAV = 0x31
    CALL = 0x32
    HEARTBEAT = 0x33
    MISSED_CALL = 0x34
    SMS = 0x35
    IDENTITY = 0x36
    TELEMETRY = 0x37


def checksum(frame: bytes) -> int:
    """sum(bytes[1:28]) mod 256."""
    return sum(frame[1:28]) % 256


def is_well_formed(frame: bytes) -> bool:
    """Length 30, correct header/terminator, valid checksum."""
    return (
        len(frame) == FRAME_LEN
        and frame[0] == HEADER
        and frame[FRAME_LEN - 1] == TERMINATOR
        and frame[28] == checksum(frame)
    )


def _finalize(buf: bytearray) -> bytes:
    """Fill in header / checksum / terminator on a 30-byte buffer."""
    assert len(buf) == FRAME_LEN
    buf[0] = HEADER
    buf[FRAME_LEN - 1] = TERMINATOR
    buf[28] = checksum(bytes(buf))
    return bytes(buf)


def _ascii(value, width: int) -> bytes:
    """Right-justify integer or string, zero-pad to width, ASCII-encode."""
    if isinstance(value, int):
        s = str(value).rjust(width, "0")
    else:
        s = str(value).rjust(width, "0")
    if len(s) > width:
        s = s[-width:]
    return s.encode("ascii")


# ---------------------------------------------------------------------------
# a537 TELEMETRY (bike -> phone)
# ---------------------------------------------------------------------------


@dataclass
class TelemetryFrame:
    """a537 — bike -> phone telemetry, sent ~every 5 seconds."""

    speed_kmh: int                  # bytes 2-4
    odometer_km: int                # bytes 5-10
    trip_a_km: float                # bytes 11-16, decoded as XXXXX.X
    trip_b_km: float                # bytes 17-22, same format
    fuel_bars: Optional[int]        # byte 24, '1'-'6' for petrol; None otherwise
    fuel_econ_kml: Optional[float]  # bytes 25-27, 13.11 fixed-point /10; None if 0xFFFFFF sentinel
    byte_23: int = 0x4E             # observed 'N' in captures; semantic unconfirmed
    raw: Optional[bytes] = field(default=None, repr=False)

    @classmethod
    def decode(cls, frame: bytes) -> "TelemetryFrame":
        if not is_well_formed(frame):
            raise ValueError(f"a537 frame malformed: {bytes(frame).hex()}")
        if frame[1] != FrameType.TELEMETRY:
            raise ValueError(f"not an a537 frame (type=0x{frame[1]:02x})")

        speed = int(frame[2:5].decode("ascii", errors="replace"))

        odo_str = frame[5:11].decode("ascii", errors="replace")
        odo = int(odo_str) if odo_str.isdigit() else 0

        def _decode_trip(raw: bytes) -> float:
            s = raw.decode("ascii", errors="replace")
            if not (len(s) == 6 and s.isdigit()):
                return 0.0
            return float(s[:5] + "." + s[5:])

        trip_a = _decode_trip(frame[11:17])
        trip_b = _decode_trip(frame[17:23])

        b24 = frame[24]
        fuel_bars = (b24 - 0x30) if 0x31 <= b24 <= 0x36 else None

        b25, b26, b27 = frame[25], frame[26], frame[27]
        if b25 == 0xFF and b26 == 0xFF and b27 == 0xFF:
            fuel_econ = None
        else:
            v24 = (b25 << 16) | (b26 << 8) | b27
            top_13 = (v24 >> 11) & 0x1FFF
            bot_11 = v24 & 0x7FF
            fuel_econ = (top_13 + bot_11 / 2048.0) / 10.0

        return cls(
            speed_kmh=speed,
            odometer_km=odo,
            trip_a_km=trip_a,
            trip_b_km=trip_b,
            fuel_bars=fuel_bars,
            fuel_econ_kml=fuel_econ,
            byte_23=frame[23],
            raw=bytes(frame),
        )

    def encode(self) -> bytes:
        buf = bytearray(FRAME_LEN)
        buf[1] = FrameType.TELEMETRY

        buf[2:5] = _ascii(self.speed_kmh, 3)
        buf[5:11] = _ascii(self.odometer_km, 6)

        # Trip A/B: round to 1 decimal, format as XXXXX.X then strip the dot
        def _trip_bytes(t: float) -> bytes:
            t = max(0.0, min(99999.9, t))
            s = f"{t:07.1f}".replace(".", "")  # "00001.5" → "000015"
            return s[-6:].encode("ascii")

        buf[11:17] = _trip_bytes(self.trip_a_km)
        buf[17:23] = _trip_bytes(self.trip_b_km)
        buf[23] = self.byte_23

        if self.fuel_bars is None:
            buf[24] = 0x00
        else:
            buf[24] = 0x30 + max(0, min(6, self.fuel_bars))

        if self.fuel_econ_kml is None:
            buf[25:28] = b"\xff\xff\xff"
        else:
            scaled = max(0.0, min(8191.0 + 2047 / 2048.0, self.fuel_econ_kml * 10.0))
            top_13 = int(scaled)
            bot_11 = int(round((scaled - top_13) * 2048)) & 0x7FF
            v24 = (top_13 << 11) | bot_11
            buf[25] = (v24 >> 16) & 0xFF
            buf[26] = (v24 >> 8) & 0xFF
            buf[27] = v24 & 0xFF

        return _finalize(buf)


# ---------------------------------------------------------------------------
# a531 NAV (phone -> bike)
# ---------------------------------------------------------------------------


@dataclass
class NavFrame:
    """a531 — phone -> bike, navigation update.

    Drives the cluster's turn-by-turn display. When `status` is in
    {'0','2','4','6'} ("degraded"), the bike will not render `maneuver_id`
    even if you set one — the app's A0.D() overrides the byte to 0x2e ('.')
    in that case. For forging, set status='1' to get a real arrow displayed.
    """

    maneuver_id: int        # byte 2: Mappls maneuver code (or 0x2e in degraded mode)
    dist_next: str          # bytes 4-7: 4-char distance to next maneuver
    dist_next_unit: str     # byte 8: 'K' or 'M'
    eta: str                # bytes 9-14: 6-char ETA (HHMMAA 12h or HHMM00 24h)
    dist_total: str         # bytes 18-21: 4-char total distance-to-go
    dist_total_unit: str    # byte 22: 'K' or 'M'
    status: str             # byte 23: nav status digit '0'-'6'
    continue_flag: str      # byte 24: '0' = terminate nav, else continue
    raw: Optional[bytes] = field(default=None, repr=False)

    @classmethod
    def decode(cls, frame: bytes) -> "NavFrame":
        if not is_well_formed(frame):
            raise ValueError(f"a531 frame malformed: {bytes(frame).hex()}")
        if frame[1] != FrameType.NAV:
            raise ValueError(f"not an a531 frame (type=0x{frame[1]:02x})")
        return cls(
            maneuver_id=frame[2],
            dist_next=frame[4:8].decode("ascii", errors="replace"),
            dist_next_unit=chr(frame[8]) if 0x20 <= frame[8] < 0x7F else "?",
            eta=frame[9:15].decode("ascii", errors="replace"),
            dist_total=frame[18:22].decode("ascii", errors="replace"),
            dist_total_unit=chr(frame[22]) if 0x20 <= frame[22] < 0x7F else "?",
            status=chr(frame[23]),
            continue_flag=chr(frame[24]),
            raw=bytes(frame),
        )

    def encode(self) -> bytes:
        buf = bytearray(FRAME_LEN)
        buf[1] = FrameType.NAV
        buf[2] = self.maneuver_id & 0xFF
        buf[3] = 0xFF

        buf[4:8] = self.dist_next.encode("ascii")[:4].ljust(4, b"0")
        buf[8] = ord(self.dist_next_unit[0]) if self.dist_next_unit else ord("M")
        buf[9:15] = self.eta.encode("ascii")[:6].ljust(6, b"0")

        buf[15] = 0xFF
        buf[16] = 0xFF
        buf[17] = 0xFF

        buf[18:22] = self.dist_total.encode("ascii")[:4].ljust(4, b"0")
        buf[22] = ord(self.dist_total_unit[0]) if self.dist_total_unit else ord("M")
        buf[23] = ord(self.status[0]) if self.status else ord("1")
        buf[24] = ord(self.continue_flag[0]) if self.continue_flag else ord("1")

        buf[25:28] = b"\xff\xff\xff"

        return _finalize(buf)


# ---------------------------------------------------------------------------
# a533 HEARTBEAT (phone -> bike)
# ---------------------------------------------------------------------------


@dataclass
class HeartbeatFrame:
    """a533 — phone -> bike. Despite the "heartbeat" name, this carries a full
    environmental dashboard: phone battery + cell signal + time + SMS/call
    flags + weather code + outdoor temperature.

    For the K.g==false branch (Gixxer SF 150, Avenis, others), `weather` /
    `temp_f_plus_115` / `tail_const` are meaningful. For K.g==true (Access 125,
    Burgman Street), those three are 0xFF padding instead.
    """

    battery_bucket: str    # byte 2: '0'/'1'/'2'/'3' (phone battery 0-24% / 25-49% / 50-74% / 75-100%)
    charging: str          # byte 3: 'Y' (charging) or 'N' (not charging)
    speed_str: str         # bytes 4-6: 3-char zero-padded speed; "\xff\xff\xff" if speed==0
    signal_status: str     # byte 7: phone cell signal bars 0-3 (same encoding as a531 status); 0x00 if "0"
    time_hhmmss: str       # bytes 8-13: 6-char wall-clock time in hhmmss (12-hour); 0xFF×6 if "000000"
    sms_pending: str       # byte 14: 'N' (default) or 'Y' (set by NotificationService)
    call_pending: str      # byte 15: 'N' (default) or 'Y' (set by CallReceiverBroadcast)
    weather: int = 0x01    # byte 21: c.M weather code 0-11 (1=sunny, 2=cloudy, 3=fog, 6=rain, 7=snow, etc.). 0xFF if K.g==true.
    temp_f_plus_115: int = 0x00  # byte 22: (int) c.N = ceil(Fahrenheit) + 115. Decode: F = byte - 115; C = (F-32)*5/9. 0xFF if K.g==true.
    tail_const: int = 0x01 # byte 23: literal 1. 0xFF if K.g==true.
    raw: Optional[bytes] = field(default=None, repr=False)

    @property
    def temp_celsius(self) -> Optional[float]:
        """Decoded outdoor temperature in Celsius, or None if unset."""
        if self.temp_f_plus_115 == 0:
            return None
        f = self.temp_f_plus_115 - 115
        return (f - 32) * 5.0 / 9.0

    @classmethod
    def decode(cls, frame: bytes) -> "HeartbeatFrame":
        if not is_well_formed(frame):
            raise ValueError(f"a533 frame malformed: {bytes(frame).hex()}")
        if frame[1] != FrameType.HEARTBEAT:
            raise ValueError(f"not an a533 frame (type=0x{frame[1]:02x})")

        # Speed/time/signal/time may have override bytes; decode raw + flag the override.
        speed_raw = frame[4:7]
        speed_str = (
            "" if speed_raw == b"\xff\xff\xff" else speed_raw.decode("ascii", errors="replace")
        )

        sig_raw = frame[7]
        signal_status = "0" if sig_raw == 0x00 else chr(sig_raw)

        time_raw = frame[8:14]
        time_str = (
            "" if time_raw == b"\xff" * 6 else time_raw.decode("ascii", errors="replace")
        )

        return cls(
            battery_bucket=chr(frame[2]),
            charging=chr(frame[3]),
            speed_str=speed_str,
            signal_status=signal_status,
            time_hhmmss=time_str,
            sms_pending=chr(frame[14]),
            call_pending=chr(frame[15]),
            weather=frame[21],
            temp_f_plus_115=frame[22],
            tail_const=frame[23],
            raw=bytes(frame),
        )

    def encode(self) -> bytes:
        buf = bytearray(FRAME_LEN)
        buf[1] = FrameType.HEARTBEAT
        buf[2] = ord(self.battery_bucket[0])
        buf[3] = ord(self.charging[0])

        if not self.speed_str:
            buf[4:7] = b"\xff\xff\xff"
        else:
            buf[4:7] = self.speed_str.encode("ascii")[:3].rjust(3, b"0")

        if self.signal_status == "0":
            buf[7] = 0x00
        else:
            buf[7] = ord(self.signal_status[0])

        if not self.time_hhmmss:
            buf[8:14] = b"\xff" * 6
        else:
            buf[8:14] = self.time_hhmmss.encode("ascii")[:6].rjust(6, b"0")

        buf[14] = ord(self.sms_pending[0])
        buf[15] = ord(self.call_pending[0])
        buf[16:21] = b"\xff" * 5
        buf[21] = self.weather & 0xFF
        buf[22] = self.temp_f_plus_115 & 0xFF
        buf[23] = self.tail_const & 0xFF
        buf[24:28] = b"\xff" * 4

        return _finalize(buf)


# ---------------------------------------------------------------------------
# a536 IDENTITY (phone -> bike)
# ---------------------------------------------------------------------------


@dataclass
class IdentityFrame:
    """a536 — phone -> bike, user identity, sent on (re)connect."""

    name: str           # bytes 2-21, NUL-padded; effective length 20 chars
    is_fresh: bool      # byte 27: 'F' (0x46, new cluster) vs 'R' (0x52, reconnect)
    raw: Optional[bytes] = field(default=None, repr=False)

    @classmethod
    def decode(cls, frame: bytes) -> "IdentityFrame":
        if not is_well_formed(frame):
            raise ValueError(f"a536 frame malformed: {bytes(frame).hex()}")
        if frame[1] != FrameType.IDENTITY:
            raise ValueError(f"not an a536 frame (type=0x{frame[1]:02x})")
        name_raw = frame[2:22].rstrip(b"\x00\xff")
        return cls(
            name=name_raw.decode("ascii", errors="replace"),
            is_fresh=(frame[27] == 0x46),
            raw=bytes(frame),
        )

    def encode(self) -> bytes:
        buf = bytearray(FRAME_LEN)
        buf[1] = FrameType.IDENTITY
        encoded = self.name.encode("ascii")[:20]
        buf[2 : 2 + len(encoded)] = encoded
        # bytes 2 + len(encoded) .. 21 stay as 0x00 (NUL padding)
        buf[22:27] = b"\xff" * 5
        buf[27] = 0x46 if self.is_fresh else 0x52
        return _finalize(buf)


# ---------------------------------------------------------------------------
# a532 / a534 / a535 — event-driven notification frames
# (templates from source; not yet verified against captures because the M0
#  captures had no phone events — flagged as TODO in DISCOVERIES.md)
# ---------------------------------------------------------------------------


@dataclass
class CallFrame:
    """a532 — phone -> bike, incoming call.

    Two source paths in the app:
      - CallReceiverBroadcast.d(): cellular call, byte 22 = 'N'
      - NotificationService.q(): WhatsApp call, byte 22 = 'W'

    State byte (23) is '1' or '2'; '2' set in WhatsApp path when str2=="2",
    and in cellular path when str2!="2" AND missed-call count l!=0.
    """

    number: str           # bytes 2-21: caller phone number, NUL-padded to 20 chars
    is_whatsapp: bool = False  # byte 22: 'W' if WhatsApp call, 'N' if cellular
    state: int = 0x31     # byte 23: '1' (= 0x31) default, '2' (= 0x32) variant
    raw: Optional[bytes] = field(default=None, repr=False)

    @classmethod
    def decode(cls, frame: bytes) -> "CallFrame":
        if not is_well_formed(frame):
            raise ValueError(f"a532 frame malformed: {bytes(frame).hex()}")
        if frame[1] != FrameType.CALL:
            raise ValueError(f"not an a532 frame (type=0x{frame[1]:02x})")
        return cls(
            number=frame[2:22].rstrip(b"\x00\xff").decode("ascii", errors="replace"),
            is_whatsapp=(frame[22] == 0x57),
            state=frame[23],
            raw=bytes(frame),
        )

    def encode(self) -> bytes:
        buf = bytearray(FRAME_LEN)
        buf[1] = FrameType.CALL
        encoded = self.number.encode("ascii")[:20]
        buf[2 : 2 + len(encoded)] = encoded
        buf[22] = 0x57 if self.is_whatsapp else 0x4E
        buf[23] = self.state & 0xFF
        buf[24:28] = b"\xff" * 4
        return _finalize(buf)


@dataclass
class MissedCallFrame:
    """a534 — phone -> bike, missed-call notification.

    Two source paths:
      - CallReceiverBroadcast.e(): cellular missed call, byte 24 = 'N'
      - NotificationService (line 729): WhatsApp missed call, byte 24 = 'W'

    Layout (after the source's "?4" + "Y1<name>" + zeros template + overrides):
      bytes 2 = 0xFF (overridden)
      bytes 3 = missed-count int
      bytes 4-23 = caller name (NUL-padded; the "Y1" prefix from the source
                   template stays at bytes 4-5, then 18 chars of name follow)
      byte 24 = 'N' (cellular) or 'W' (WhatsApp)
      bytes 25-27 = 0xFF
    """

    name: str                  # bytes 6-23: caller name (18 chars after the "Y1" prefix)
    missed_count: int = 1      # byte 3
    is_whatsapp: bool = False  # byte 24
    raw: Optional[bytes] = field(default=None, repr=False)

    @classmethod
    def decode(cls, frame: bytes) -> "MissedCallFrame":
        if not is_well_formed(frame):
            raise ValueError(f"a534 frame malformed: {bytes(frame).hex()}")
        if frame[1] != FrameType.MISSED_CALL:
            raise ValueError(f"not an a534 frame (type=0x{frame[1]:02x})")
        # Bytes 4-5 are the "Y1" prefix from the source template (literal). Strip
        # them if present; otherwise just decode bytes 4-23 raw.
        body = frame[4:24]
        if body[:2] == b"Y1":
            name_bytes = body[2:]
        else:
            name_bytes = body
        return cls(
            name=name_bytes.rstrip(b"\x00\xff").decode("ascii", errors="replace"),
            missed_count=frame[3],
            is_whatsapp=(frame[24] == 0x57),
            raw=bytes(frame),
        )

    def encode(self) -> bytes:
        buf = bytearray(FRAME_LEN)
        buf[1] = FrameType.MISSED_CALL
        buf[2] = 0xFF
        buf[3] = self.missed_count & 0xFF
        # Replicate the source's "Y1" + name layout: bytes 4-5 = "Y1", 6-23 = name
        buf[4:6] = b"Y1"
        encoded = self.name.encode("ascii")[:18]
        buf[6 : 6 + len(encoded)] = encoded
        buf[24] = 0x57 if self.is_whatsapp else 0x4E
        buf[25:28] = b"\xff" * 3
        return _finalize(buf)


@dataclass
class SmsFrame:
    """a535 — phone -> bike, SMS / WhatsApp / notification message.

    Layout (after NotificationService.o() / IncomingSms.c() templates + overrides):
      bytes 0-1 = header / type
      byte 2 = 0xFF (overridden from sender name's first char)
      byte 3 = 'N' silenced (z=true) or 'Y' not-silenced (z=false)
              IncomingSms.c does NOT explicitly set this — leaves it as sender[1].
              Treat as advisory; bike likely ignores when ambiguous.
      byte 4 = message count (= e.m0 for SMS, K.m for WhatsApp, or G for notifications)
      bytes 5-24 = sender name (20 chars, NUL-padded; effectively str[3..22] from the
              source's pad-to-24-char string template)
      byte 25 = type-source byte (set to 'N' in IncomingSms; in NotificationService.o
              it's overwritten by the input `b` parameter — purpose ambiguous)
      bytes 26-27 = 0xFF
      byte 28 = checksum
      byte 29 = 0x7F

    The `is_whatsapp` field on a previous version of this class was based on a
    misread: source DOES compute a `str3 = "W"/"X"/"N"` marker but it lands at
    position 26 which is ALWAYS overridden to 0xFF. The W/N at byte 25 differs
    between IncomingSms (always 'N') and NotificationService (variable `b`).
    Bike presumably parses sender name + count + byte 3 silenced flag.
    """

    sender: str             # bytes 5-24: sender name (20 chars)
    message_count: int = 1  # byte 4: count of unread messages
    silenced: bool = True   # byte 3: 'N' (true) or 'Y' (false)
    type_byte: int = 0x4E   # byte 25: usually 'N' (0x4E); set by source `b` param in NotificationService
    raw: Optional[bytes] = field(default=None, repr=False)

    @classmethod
    def decode(cls, frame: bytes) -> "SmsFrame":
        if not is_well_formed(frame):
            raise ValueError(f"a535 frame malformed: {bytes(frame).hex()}")
        if frame[1] != FrameType.SMS:
            raise ValueError(f"not an a535 frame (type=0x{frame[1]:02x})")
        return cls(
            sender=frame[5:25].rstrip(b"\x00\xff").decode("ascii", errors="replace"),
            message_count=frame[4],
            silenced=(frame[3] == 0x4E),
            type_byte=frame[25],
            raw=bytes(frame),
        )

    def encode(self) -> bytes:
        buf = bytearray(FRAME_LEN)
        buf[1] = FrameType.SMS
        buf[2] = 0xFF
        buf[3] = 0x4E if self.silenced else 0x59  # 'N' or 'Y'
        buf[4] = self.message_count & 0xFF
        encoded = self.sender.encode("ascii")[:20]
        buf[5 : 5 + len(encoded)] = encoded
        buf[25] = self.type_byte & 0xFF
        buf[26:28] = b"\xff\xff"
        return _finalize(buf)


# ---------------------------------------------------------------------------
# Top-level dispatch
# ---------------------------------------------------------------------------


_DECODERS = {
    FrameType.NAV: NavFrame,
    FrameType.CALL: CallFrame,
    FrameType.HEARTBEAT: HeartbeatFrame,
    FrameType.MISSED_CALL: MissedCallFrame,
    FrameType.SMS: SmsFrame,
    FrameType.IDENTITY: IdentityFrame,
    FrameType.TELEMETRY: TelemetryFrame,
}


def decode(frame: bytes):
    """Dispatch on the type byte and return the appropriate Frame dataclass."""
    if len(frame) != FRAME_LEN:
        raise ValueError(f"expected {FRAME_LEN} bytes, got {len(frame)}")
    try:
        type_enum = FrameType(frame[1])
    except ValueError as exc:
        raise ValueError(f"unknown frame type byte 0x{frame[1]:02x}") from exc
    return _DECODERS[type_enum].decode(frame)
