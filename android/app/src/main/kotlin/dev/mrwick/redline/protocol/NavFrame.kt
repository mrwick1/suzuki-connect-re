package dev.mrwick.redline.protocol

import androidx.compose.runtime.Immutable

/**
 * a531 — phone -> bike, navigation update.
 *
 * Drives the cluster's turn-by-turn display. When [status] is in
 * {'0','2','4','6'} (degraded), the bike will not render [maneuverId]
 * even if you set one — the app's `A0.D()` overrides the byte to 0x2e ('.')
 * in that case. For forging, set [status]='1' to get a real arrow.
 *
 * Layout (corresponds to Python's `NavFrame` docstring):
 *   byte 2      maneuver_id (Mappls code or 0x2e in degraded)
 *   byte 3      0xFF padding
 *   bytes 4-7   dist_next   (4 ASCII chars)
 *   byte 8      dist_next_unit ('K' or 'M')
 *   bytes 9-14  eta         (6 ASCII chars, HHMMAA 12h or HHMM00 24h)
 *   bytes 15-17 0xFF padding
 *   bytes 18-21 dist_total  (4 ASCII chars)
 *   byte 22     dist_total_unit ('K' or 'M')
 *   byte 23     status      ('0'..'6')
 *   byte 24     continue_flag ('0' = BLE-graceful-disconnect hint)
 *   bytes 25-27 0xFF padding
 */
@Immutable
data class NavFrame(
    val maneuverId: Int,
    val distNext: String,
    val distNextUnit: String,
    val eta: String,
    val distTotal: String,
    val distTotalUnit: String,
    val status: String,
    val continueFlag: String,
    override val raw: ByteArray? = null,
) : Frame {

    /** Encode this frame to its 30-byte on-the-wire representation. */
    fun encode(): ByteArray {
        val buf = ByteArray(FRAME_LEN)
        buf[1] = FrameType.NAV.code
        buf[2] = (maneuverId and 0xFF).toByte()
        buf[3] = 0xFF.toByte()

        writeAsciiLeftPadZero(buf, 4, 4, distNext)
        buf[8] = unitByte(distNextUnit, default = 'M')

        writeAsciiLeftPadZero(buf, 9, 6, eta)

        buf[15] = 0xFF.toByte()
        buf[16] = 0xFF.toByte()
        buf[17] = 0xFF.toByte()

        writeAsciiLeftPadZero(buf, 18, 4, distTotal)
        buf[22] = unitByte(distTotalUnit, default = 'M')
        buf[23] = singleAsciiByte(status, default = '1')
        buf[24] = singleAsciiByte(continueFlag, default = '1')

        buf[25] = 0xFF.toByte()
        buf[26] = 0xFF.toByte()
        buf[27] = 0xFF.toByte()

        return finalize(buf)
    }

    companion object {
        /** Decode an a531 frame; throws [IllegalArgumentException] when malformed or wrong type. */
        fun decode(frame: ByteArray): NavFrame {
            require(isWellFormed(frame)) { "a531 frame malformed: ${frame.toHexString()}" }
            require(frame[1] == FrameType.NAV.code) {
                "not an a531 frame (type=0x${"%02x".format(frame[1].toInt() and 0xFF)})"
            }
            return NavFrame(
                maneuverId = frame[2].toInt() and 0xFF,
                distNext = asciiSlice(frame, 4, 8),
                distNextUnit = printableByteToChar(frame[8]),
                eta = asciiSlice(frame, 9, 15),
                distTotal = asciiSlice(frame, 18, 22),
                distTotalUnit = printableByteToChar(frame[22]),
                status = (frame[23].toInt() and 0xFF).toChar().toString(),
                continueFlag = (frame[24].toInt() and 0xFF).toChar().toString(),
                raw = frame.copyOf(),
            )
        }
    }

    // Required because data classes don't auto-generate equality for ByteArray properly.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NavFrame) return false
        return maneuverId == other.maneuverId &&
            distNext == other.distNext &&
            distNextUnit == other.distNextUnit &&
            eta == other.eta &&
            distTotal == other.distTotal &&
            distTotalUnit == other.distTotalUnit &&
            status == other.status &&
            continueFlag == other.continueFlag &&
            ((raw == null && other.raw == null) || (raw != null && other.raw != null && raw.contentEquals(other.raw)))
    }

    override fun hashCode(): Int {
        var result = maneuverId
        result = 31 * result + distNext.hashCode()
        result = 31 * result + distNextUnit.hashCode()
        result = 31 * result + eta.hashCode()
        result = 31 * result + distTotal.hashCode()
        result = 31 * result + distTotalUnit.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + continueFlag.hashCode()
        result = 31 * result + (raw?.contentHashCode() ?: 0)
        return result
    }
}
