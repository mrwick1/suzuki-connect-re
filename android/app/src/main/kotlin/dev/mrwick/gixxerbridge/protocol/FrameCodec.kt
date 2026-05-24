package dev.mrwick.gixxerbridge.protocol

/**
 * Top-level frame codec — dispatches a raw 30-byte buffer to the appropriate
 * concrete `*Frame.decode()`. Mirrors Python's module-level `decode()`.
 */

/**
 * Decode a 30-byte BLE buffer into the appropriate concrete [Frame] subtype.
 *
 * Throws [IllegalArgumentException] when the buffer is the wrong length, has
 * an unknown type byte, or fails the per-type integrity / shape checks.
 */
fun decodeFrame(bytes: ByteArray): Frame {
    require(bytes.size == FRAME_LEN) { "expected $FRAME_LEN bytes, got ${bytes.size}" }
    val type = FrameType.fromByte(bytes[1])
        ?: throw IllegalArgumentException(
            "unknown frame type byte 0x${"%02x".format(bytes[1].toInt() and 0xFF)}"
        )
    return when (type) {
        FrameType.NAV -> NavFrame.decode(bytes)
        FrameType.CALL -> CallFrame.decode(bytes)
        FrameType.HEARTBEAT -> HeartbeatFrame.decode(bytes)
        FrameType.MISSED_CALL -> MissedCallFrame.decode(bytes)
        FrameType.SMS -> SmsFrame.decode(bytes)
        FrameType.IDENTITY -> IdentityFrame.decode(bytes)
        FrameType.TELEMETRY -> TelemetryFrame.decode(bytes)
    }
}

// ----------------------------------------------------------------------------
// Package-internal helpers shared by every *Frame.kt file. Kept here (instead
// of a dedicated Bytes.kt / Hex.kt utility module) because the task scope
// explicitly forbids creating a separate utilities module — a separate agent
// owns that. These functions are `internal` so they don't leak into the
// package's public API.
// ----------------------------------------------------------------------------

/** Convert a [ByteArray] to a lowercase hex string (no separators). */
internal fun ByteArray.toHexString(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) sb.append("%02x".format(b.toInt() and 0xFF))
    return sb.toString()
}

/** Encode an ASCII [String] to bytes via [Charsets.US_ASCII]. */
internal fun String.encodeAscii(): ByteArray = this.toByteArray(Charsets.US_ASCII)

/** Decode the (start..endExclusive) slice of [frame] as ASCII, replacing un-decodable bytes. */
internal fun asciiSlice(frame: ByteArray, start: Int, endExclusive: Int): String {
    return String(frame, start, endExclusive - start, Charsets.US_ASCII)
}

/**
 * Decode (start..endExclusive) as ASCII, then strip trailing NUL (0x00) and 0xFF bytes,
 * mirroring Python `bytes.rstrip(b"\x00\xff")`.
 */
internal fun asciiSliceStripTrailing(frame: ByteArray, start: Int, endExclusive: Int): String {
    val slice = frame.copyOfRange(start, endExclusive)
    val stripped = stripTrailingZerosAndFf(slice)
    return String(stripped, Charsets.US_ASCII)
}

/** Strip trailing 0x00 and 0xFF bytes (mirroring Python `bytes.rstrip(b"\x00\xff")`). */
internal fun stripTrailingZerosAndFf(bytes: ByteArray): ByteArray {
    var end = bytes.size
    while (end > 0) {
        val b = bytes[end - 1]
        if (b != 0x00.toByte() && b != 0xFF.toByte()) break
        end--
    }
    return bytes.copyOfRange(0, end)
}

/**
 * Mirror Python's `chr(b) if 0x20 <= b < 0x7F else "?"`. Used by NAV unit-byte decoding.
 */
internal fun printableByteToChar(b: Byte): String {
    val v = b.toInt() and 0xFF
    return if (v in 0x20..0x7E) v.toChar().toString() else "?"
}

/**
 * Write [value] right-justified in [width] bytes starting at [offset], zero-padding ('0')
 * on the left. If the encoded ASCII is wider than [width] take its trailing [width] bytes.
 *
 * Mirrors Python `_ascii()` for string inputs followed by `buf[a:b] = ...` assignment, BUT
 * specifically for the NAV layout where Python does
 * `self.dist_next.encode("ascii")[:width].ljust(width, b"0")` — which actually LEFT-justifies
 * (pads with '0' on the right). To match exactly, we left-justify here.
 */
internal fun writeAsciiLeftPadZero(buf: ByteArray, offset: Int, width: Int, value: String) {
    val ascii = value.encodeAscii()
    val take = ascii.copyOfRange(0, minOf(width, ascii.size))
    // Python ljust(width, b"0") pads on the RIGHT with '0' to reach `width`.
    val out = ByteArray(width) { 0x30.toByte() } // '0'
    System.arraycopy(take, 0, out, 0, take.size)
    System.arraycopy(out, 0, buf, offset, width)
}

/**
 * Write [value] into [buf] at [offset] for [width] bytes: encode ASCII, take the FIRST [width]
 * bytes, then right-justify by left-padding with ASCII '0'. Mirrors Python
 * `value.encode("ascii")[:width].rjust(width, b"0")`.
 */
internal fun writeAsciiRightJustZero(buf: ByteArray, offset: Int, width: Int, value: String) {
    val ascii = value.encodeAscii()
    val take = ascii.copyOfRange(0, minOf(width, ascii.size))
    val out = ByteArray(width) { 0x30.toByte() } // '0'
    val start = width - take.size
    System.arraycopy(take, 0, out, start, take.size)
    System.arraycopy(out, 0, buf, offset, width)
}

/**
 * Return a single ASCII byte for [value]'s first char, or [default] when [value] is empty.
 */
internal fun singleAsciiByte(value: String, default: Char): Byte {
    return if (value.isEmpty()) default.code.toByte() else value[0].code.toByte()
}

/**
 * Return the ASCII byte for the first char of [unit], or [default] when [unit] is empty.
 * Equivalent helper specific to unit-letter fields.
 */
internal fun unitByte(unit: String, default: Char): Byte {
    return if (unit.isEmpty()) default.code.toByte() else unit[0].code.toByte()
}
