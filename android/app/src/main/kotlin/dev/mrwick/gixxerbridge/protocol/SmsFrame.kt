package dev.mrwick.gixxerbridge.protocol

import androidx.compose.runtime.Immutable

/**
 * a535 — phone -> bike, SMS / WhatsApp / notification message.
 *
 * Layout (after NotificationService.o() / IncomingSms.c() templates + overrides):
 *   byte 2       0xFF (overridden from sender name's first char)
 *   byte 3       'N' silenced (z=true) or 'Y' not-silenced (z=false)
 *   byte 4       message count
 *   bytes 5-24   sender name (20 chars, NUL-padded)
 *   byte 25      type-source byte (usually 'N' = 0x4E)
 *   bytes 26-27  0xFF
 */
@Immutable
data class SmsFrame(
    val sender: String,
    val messageCount: Int = 1,
    val silenced: Boolean = true,
    val typeByte: Int = 0x4E,
    override val raw: ByteArray? = null,
) : Frame {

    /** Encode this frame to its 30-byte on-the-wire representation. */
    fun encode(): ByteArray {
        val buf = ByteArray(FRAME_LEN)
        buf[1] = FrameType.SMS.code
        buf[2] = 0xFF.toByte()
        buf[3] = if (silenced) 0x4E.toByte() else 0x59.toByte() // 'N' or 'Y'
        buf[4] = (messageCount and 0xFF).toByte()
        val encoded = sender.encodeAscii()
        val copyLen = minOf(encoded.size, 20)
        System.arraycopy(encoded, 0, buf, 5, copyLen)
        // bytes 5 + copyLen .. 24 stay 0x00
        buf[25] = (typeByte and 0xFF).toByte()
        buf[26] = 0xFF.toByte()
        buf[27] = 0xFF.toByte()
        return finalize(buf)
    }

    companion object {
        /** Decode an a535 frame; throws [IllegalArgumentException] when malformed or wrong type. */
        fun decode(frame: ByteArray): SmsFrame {
            require(isWellFormed(frame)) { "a535 frame malformed: ${frame.toHexString()}" }
            require(frame[1] == FrameType.SMS.code) {
                "not an a535 frame (type=0x${"%02x".format(frame[1].toInt() and 0xFF)})"
            }
            return SmsFrame(
                sender = asciiSliceStripTrailing(frame, 5, 25),
                messageCount = frame[4].toInt() and 0xFF,
                silenced = (frame[3].toInt() and 0xFF) == 0x4E,
                typeByte = frame[25].toInt() and 0xFF,
                raw = frame.copyOf(),
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SmsFrame) return false
        return sender == other.sender &&
            messageCount == other.messageCount &&
            silenced == other.silenced &&
            typeByte == other.typeByte &&
            ((raw == null && other.raw == null) || (raw != null && other.raw != null && raw.contentEquals(other.raw)))
    }

    override fun hashCode(): Int {
        var result = sender.hashCode()
        result = 31 * result + messageCount
        result = 31 * result + silenced.hashCode()
        result = 31 * result + typeByte
        result = 31 * result + (raw?.contentHashCode() ?: 0)
        return result
    }
}
