package dev.mrwick.redline.protocol

import androidx.compose.runtime.Immutable

/**
 * a536 — phone -> bike, user identity, sent on (re)connect.
 *
 * Layout:
 *   bytes 2-21   name (NUL-padded; effective length 20 chars)
 *   bytes 22-26  0xFF padding
 *   byte 27      'F' (0x46, fresh / new cluster) or 'R' (0x52, reconnect)
 */
@Immutable
data class IdentityFrame(
    val name: String,
    val isFresh: Boolean,
    override val raw: ByteArray? = null,
) : Frame {

    /** Encode this frame to its 30-byte on-the-wire representation. */
    fun encode(): ByteArray {
        val buf = ByteArray(FRAME_LEN)
        buf[1] = FrameType.IDENTITY.code
        val encoded = name.encodeAscii()
        val copyLen = minOf(encoded.size, 20)
        System.arraycopy(encoded, 0, buf, 2, copyLen)
        // bytes 2 + copyLen .. 21 stay 0x00 (NUL padding)
        for (i in 22..26) buf[i] = 0xFF.toByte()
        buf[27] = if (isFresh) 0x46.toByte() else 0x52.toByte()
        return finalize(buf)
    }

    companion object {
        /** Decode an a536 frame; throws [IllegalArgumentException] when malformed or wrong type. */
        fun decode(frame: ByteArray): IdentityFrame {
            require(isWellFormed(frame)) { "a536 frame malformed: ${frame.toHexString()}" }
            require(frame[1] == FrameType.IDENTITY.code) {
                "not an a536 frame (type=0x${"%02x".format(frame[1].toInt() and 0xFF)})"
            }
            val nameRaw = stripTrailingZerosAndFf(frame.copyOfRange(2, 22))
            return IdentityFrame(
                name = String(nameRaw, Charsets.US_ASCII),
                isFresh = (frame[27].toInt() and 0xFF) == 0x46,
                raw = frame.copyOf(),
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityFrame) return false
        return name == other.name &&
            isFresh == other.isFresh &&
            ((raw == null && other.raw == null) || (raw != null && other.raw != null && raw.contentEquals(other.raw)))
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + isFresh.hashCode()
        result = 31 * result + (raw?.contentHashCode() ?: 0)
        return result
    }
}
