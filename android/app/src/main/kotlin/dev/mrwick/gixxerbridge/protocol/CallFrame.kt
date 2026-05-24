package dev.mrwick.gixxerbridge.protocol

/**
 * a532 — phone -> bike, incoming call.
 *
 * Two source paths in the app:
 *   - CallReceiverBroadcast.d(): cellular call, byte 22 = 'N'
 *   - NotificationService.q(): WhatsApp call, byte 22 = 'W'
 *
 * State byte (23) is '1' or '2'; '2' set in WhatsApp path when str2=="2",
 * and in cellular path when str2!="2" AND missed-call count l!=0.
 */
data class CallFrame(
    val number: String,
    val isWhatsapp: Boolean = false,
    val state: Int = 0x31,
    override val raw: ByteArray? = null,
) : Frame {

    /** Encode this frame to its 30-byte on-the-wire representation. */
    fun encode(): ByteArray {
        val buf = ByteArray(FRAME_LEN)
        buf[1] = FrameType.CALL.code
        val encoded = number.encodeAscii()
        val copyLen = minOf(encoded.size, 20)
        System.arraycopy(encoded, 0, buf, 2, copyLen)
        // bytes 2 + copyLen .. 21 stay as 0x00 (NUL padding)
        buf[22] = if (isWhatsapp) 0x57.toByte() else 0x4E.toByte()
        buf[23] = (state and 0xFF).toByte()
        for (i in 24..27) buf[i] = 0xFF.toByte()
        return finalize(buf)
    }

    companion object {
        /** Decode an a532 frame; throws [IllegalArgumentException] when malformed or wrong type. */
        fun decode(frame: ByteArray): CallFrame {
            require(isWellFormed(frame)) { "a532 frame malformed: ${frame.toHexString()}" }
            require(frame[1] == FrameType.CALL.code) {
                "not an a532 frame (type=0x${"%02x".format(frame[1].toInt() and 0xFF)})"
            }
            return CallFrame(
                number = asciiSliceStripTrailing(frame, 2, 22),
                isWhatsapp = (frame[22].toInt() and 0xFF) == 0x57,
                state = frame[23].toInt() and 0xFF,
                raw = frame.copyOf(),
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CallFrame) return false
        return number == other.number &&
            isWhatsapp == other.isWhatsapp &&
            state == other.state &&
            ((raw == null && other.raw == null) || (raw != null && other.raw != null && raw.contentEquals(other.raw)))
    }

    override fun hashCode(): Int {
        var result = number.hashCode()
        result = 31 * result + isWhatsapp.hashCode()
        result = 31 * result + state
        result = 31 * result + (raw?.contentHashCode() ?: 0)
        return result
    }
}
