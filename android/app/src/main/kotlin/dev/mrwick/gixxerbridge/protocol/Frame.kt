package dev.mrwick.gixxerbridge.protocol

/**
 * Shared constants, [FrameType] enum, and low-level helpers for the
 * Suzuki Connect BLE 30-byte frame format.
 *
 * All frames have this layout:
 *   byte[0]    = 0xA5 (HEADER)
 *   byte[1]    = type byte ('1'-'7' ASCII = 0x31-0x37)
 *   byte[2:28] = body (varies by type)
 *   byte[28]   = checksum = sum(bytes[1..27]) mod 256
 *   byte[29]   = 0x7F (TERMINATOR)
 */

/** Total frame length in bytes. */
const val FRAME_LEN: Int = 30

/** Frame header byte (byte 0). */
const val HEADER: Byte = 0xA5.toByte()

/** Frame terminator byte (byte 29). */
const val TERMINATOR: Byte = 0x7F.toByte()

/** The seven Suzuki Connect BLE frame types, keyed by their ASCII type byte. */
enum class FrameType(val code: Byte) {
    NAV(0x31),
    CALL(0x32),
    HEARTBEAT(0x33),
    MISSED_CALL(0x34),
    SMS(0x35),
    IDENTITY(0x36),
    TELEMETRY(0x37);

    companion object {
        /** Lookup by raw byte; returns null when the byte is not a known frame type. */
        fun fromByte(b: Byte): FrameType? {
            for (t in entries) if (t.code == b) return t
            return null
        }
    }
}

/** Compute sum(bytes[1..27]) mod 256, mirroring Python `checksum()`. */
fun checksum(frame: ByteArray): Int {
    var sum = 0
    // Python: sum(frame[1:28]) — indices 1..27 inclusive.
    for (i in 1..27) {
        sum += (frame[i].toInt() and 0xFF)
    }
    return sum and 0xFF
}

/** True when [frame] is the correct length and has valid header, terminator, and checksum. */
fun isWellFormed(frame: ByteArray): Boolean {
    if (frame.size != FRAME_LEN) return false
    if (frame[0] != HEADER) return false
    if (frame[FRAME_LEN - 1] != TERMINATOR) return false
    return (frame[28].toInt() and 0xFF) == checksum(frame)
}

/**
 * Fill in [HEADER] / checksum / [TERMINATOR] on a [FRAME_LEN]-byte buffer and return it.
 *
 * Mirrors Python `_finalize()`. Caller is expected to have set bytes 1..27 already.
 */
fun finalize(buf: ByteArray): ByteArray {
    require(buf.size == FRAME_LEN) { "buffer must be $FRAME_LEN bytes, got ${buf.size}" }
    buf[0] = HEADER
    buf[FRAME_LEN - 1] = TERMINATOR
    buf[28] = checksum(buf).toByte()
    return buf
}

/**
 * Marker interface implemented by every concrete frame type.
 *
 * Each concrete frame retains the original raw bytes on its [raw] field
 * when produced via `decode()`; constructor-built frames have [raw] = null.
 */
sealed interface Frame {
    /** The original 30-byte buffer when this frame came from `decode()`; null otherwise. */
    val raw: ByteArray?
}
