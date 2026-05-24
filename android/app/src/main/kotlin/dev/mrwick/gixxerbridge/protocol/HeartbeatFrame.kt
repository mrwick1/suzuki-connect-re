package dev.mrwick.gixxerbridge.protocol

/**
 * a533 — phone -> bike. Despite the "heartbeat" name, this carries a full
 * environmental dashboard: phone battery + cell signal + time + SMS/call
 * flags + weather code + outdoor temperature.
 *
 * For the K.g==false branch (Gixxer SF 150, Avenis, others), [weather] /
 * [tempFPlus115] / [tailConst] are meaningful. For K.g==true (Access 125,
 * Burgman Street), those three are 0xFF padding instead.
 */
data class HeartbeatFrame(
    val batteryBucket: String,
    val charging: String,
    val speedStr: String,
    val signalStatus: String,
    val timeHhmmss: String,
    val smsPending: String,
    val callPending: String,
    val weather: Int = 0x01,
    val tempFPlus115: Int = 0x00,
    val tailConst: Int = 0x01,
    override val raw: ByteArray? = null,
) : Frame {

    /** Decoded outdoor temperature in Celsius, or null if [tempFPlus115] is unset. */
    val tempCelsius: Double?
        get() {
            if (tempFPlus115 == 0) return null
            val f = tempFPlus115 - 115
            return (f - 32) * 5.0 / 9.0
        }

    /** Encode this frame to its 30-byte on-the-wire representation. */
    fun encode(): ByteArray {
        val buf = ByteArray(FRAME_LEN)
        buf[1] = FrameType.HEARTBEAT.code
        buf[2] = singleAsciiByte(batteryBucket, default = '0')
        buf[3] = singleAsciiByte(charging, default = 'N')

        if (speedStr.isEmpty()) {
            buf[4] = 0xFF.toByte(); buf[5] = 0xFF.toByte(); buf[6] = 0xFF.toByte()
        } else {
            // Python: self.speed_str.encode("ascii")[:3].rjust(3, b"0")
            // Encode to ASCII, take FIRST 3 bytes, then right-justify (left-pad with '0').
            writeAsciiRightJustZero(buf, 4, 3, speedStr)
        }

        buf[7] = if (signalStatus == "0") 0x00.toByte() else singleAsciiByte(signalStatus, default = '0')

        if (timeHhmmss.isEmpty()) {
            for (i in 8..13) buf[i] = 0xFF.toByte()
        } else {
            // Python: self.time_hhmmss.encode("ascii")[:6].rjust(6, b"0")
            writeAsciiRightJustZero(buf, 8, 6, timeHhmmss)
        }

        buf[14] = singleAsciiByte(smsPending, default = 'N')
        buf[15] = singleAsciiByte(callPending, default = 'N')
        for (i in 16..20) buf[i] = 0xFF.toByte()
        buf[21] = (weather and 0xFF).toByte()
        buf[22] = (tempFPlus115 and 0xFF).toByte()
        buf[23] = (tailConst and 0xFF).toByte()
        for (i in 24..27) buf[i] = 0xFF.toByte()

        return finalize(buf)
    }

    companion object {
        /** Decode an a533 frame; throws [IllegalArgumentException] when malformed or wrong type. */
        fun decode(frame: ByteArray): HeartbeatFrame {
            require(isWellFormed(frame)) { "a533 frame malformed: ${frame.toHexString()}" }
            require(frame[1] == FrameType.HEARTBEAT.code) {
                "not an a533 frame (type=0x${"%02x".format(frame[1].toInt() and 0xFF)})"
            }

            val speedRaw = frame.copyOfRange(4, 7)
            val speedStr =
                if (speedRaw[0] == 0xFF.toByte() && speedRaw[1] == 0xFF.toByte() && speedRaw[2] == 0xFF.toByte()) {
                    ""
                } else {
                    String(speedRaw, Charsets.US_ASCII)
                }

            val sigRaw = frame[7].toInt() and 0xFF
            val signalStatus = if (sigRaw == 0x00) "0" else sigRaw.toChar().toString()

            val timeRaw = frame.copyOfRange(8, 14)
            val timeAllFf = timeRaw.all { it == 0xFF.toByte() }
            val timeStr = if (timeAllFf) "" else String(timeRaw, Charsets.US_ASCII)

            return HeartbeatFrame(
                batteryBucket = (frame[2].toInt() and 0xFF).toChar().toString(),
                charging = (frame[3].toInt() and 0xFF).toChar().toString(),
                speedStr = speedStr,
                signalStatus = signalStatus,
                timeHhmmss = timeStr,
                smsPending = (frame[14].toInt() and 0xFF).toChar().toString(),
                callPending = (frame[15].toInt() and 0xFF).toChar().toString(),
                weather = frame[21].toInt() and 0xFF,
                tempFPlus115 = frame[22].toInt() and 0xFF,
                tailConst = frame[23].toInt() and 0xFF,
                raw = frame.copyOf(),
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HeartbeatFrame) return false
        return batteryBucket == other.batteryBucket &&
            charging == other.charging &&
            speedStr == other.speedStr &&
            signalStatus == other.signalStatus &&
            timeHhmmss == other.timeHhmmss &&
            smsPending == other.smsPending &&
            callPending == other.callPending &&
            weather == other.weather &&
            tempFPlus115 == other.tempFPlus115 &&
            tailConst == other.tailConst &&
            ((raw == null && other.raw == null) || (raw != null && other.raw != null && raw.contentEquals(other.raw)))
    }

    override fun hashCode(): Int {
        var result = batteryBucket.hashCode()
        result = 31 * result + charging.hashCode()
        result = 31 * result + speedStr.hashCode()
        result = 31 * result + signalStatus.hashCode()
        result = 31 * result + timeHhmmss.hashCode()
        result = 31 * result + smsPending.hashCode()
        result = 31 * result + callPending.hashCode()
        result = 31 * result + weather
        result = 31 * result + tempFPlus115
        result = 31 * result + tailConst
        result = 31 * result + (raw?.contentHashCode() ?: 0)
        return result
    }
}
