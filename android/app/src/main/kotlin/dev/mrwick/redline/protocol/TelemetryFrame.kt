package dev.mrwick.redline.protocol

import androidx.compose.runtime.Immutable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * a537 — bike -> phone telemetry, sent ~every 5 seconds.
 *
 * Layout:
 *   bytes 2-4    speed_kmh (3 ASCII digits)
 *   bytes 5-10   odometer_km (6 ASCII digits)
 *   bytes 11-16  trip_a_km (6 ASCII digits, XXXXX.X with implicit decimal)
 *   bytes 17-22  trip_b_km (same)
 *   byte 23      constant 0x4E ('N') across all observed frames
 *   byte 24      fuel bars ('1'-'6' for petrol; 0x00 / other = unknown)
 *   bytes 25-27  fuel-economy 24-bit value (legacy decode);
 *                for Gixxer SF 150 use [fuelEconKmlV2] instead (byte 25 / 2)
 */
@Immutable
data class TelemetryFrame(
    val speedKmh: Int,
    val odometerKm: Int,
    val tripAKm: Double,
    val tripBKm: Double,
    val fuelBars: Int?,
    val fuelEconKml: Double?,
    val byte23: Int = 0x4E,
    override val raw: ByteArray? = null,
) : Frame {

    /**
     * Gixxer SF 150-validated fuel-economy decode (byte 25 / 2).
     *
     * The legacy [fuelEconKml] field uses the default-petrol "13.11 fixed-point /10" formula,
     * which produces 131-371 km/L garbage on Gixxer SF 150 because bytes 26-27 are always 0xFF
     * padding. This property uses byte 25 / 2 directly (median ~48 km/L observed), realistic
     * for a Gixxer SF 150. Returns null when [raw] is null or byte 25 is 0xFF.
     *
     * Caveat: byte 25 monotonically increases through a ride AND ticks during engine-off idle,
     * so it's NOT instantaneous fuel-economy. Most likely the cluster's trip-average km/L since
     * last reset (scaled x2).
     */
    val fuelEconKmlV2: Double?
        get() {
            val r = raw ?: return null
            val b25 = r[25].toInt() and 0xFF
            if (b25 == 0xFF) return null
            return b25 / 2.0
        }

    /** Encode this frame to its 30-byte on-the-wire representation. */
    fun encode(): ByteArray {
        val buf = ByteArray(FRAME_LEN)
        buf[1] = FrameType.TELEMETRY.code

        // Speed: 3 ASCII digits, zero-padded.
        writeRightJustifiedAsciiInt(buf, 2, 3, speedKmh)
        // Odometer: 6 ASCII digits.
        writeRightJustifiedAsciiInt(buf, 5, 6, odometerKm)

        // Trip A/B: format "00000.0" (width=7 with 1 decimal), strip the dot, take last 6.
        val tripA = tripBytes(tripAKm)
        System.arraycopy(tripA, 0, buf, 11, 6)
        val tripB = tripBytes(tripBKm)
        System.arraycopy(tripB, 0, buf, 17, 6)

        buf[23] = (byte23 and 0xFF).toByte()

        buf[24] = if (fuelBars == null) {
            0x00.toByte()
        } else {
            (0x30 + max(0, min(6, fuelBars))).toByte()
        }

        if (fuelEconKml == null) {
            buf[25] = 0xFF.toByte(); buf[26] = 0xFF.toByte(); buf[27] = 0xFF.toByte()
        } else {
            // Mirror Python: max(0.0, min(8191.0 + 2047/2048.0, fuelEconKml * 10.0))
            val maxVal = 8191.0 + 2047.0 / 2048.0
            val scaled = max(0.0, min(maxVal, fuelEconKml * 10.0))
            val top13 = scaled.toInt() // Python int() truncates toward zero; scaled is non-negative.
            val bot11 = ((scaled - top13) * 2048.0).roundToInt() and 0x7FF
            val v24 = (top13 shl 11) or bot11
            buf[25] = ((v24 shr 16) and 0xFF).toByte()
            buf[26] = ((v24 shr 8) and 0xFF).toByte()
            buf[27] = (v24 and 0xFF).toByte()
        }

        return finalize(buf)
    }

    companion object {
        /** Decode an a537 frame; throws [IllegalArgumentException] when malformed or wrong type. */
        fun decode(frame: ByteArray): TelemetryFrame {
            require(isWellFormed(frame)) { "a537 frame malformed: ${frame.toHexString()}" }
            require(frame[1] == FrameType.TELEMETRY.code) {
                "not an a537 frame (type=0x${"%02x".format(frame[1].toInt() and 0xFF)})"
            }

            val speedStr = String(frame, 2, 3, Charsets.US_ASCII)
            // ASSUMED: Python uses int(...) which throws on non-digit input. We mirror via
            // toIntOrNull and fall back to 0 to be defensive — matches the odo "isdigit -> 0"
            // pattern used in the Python source for odometer and trips. Real captures are
            // always ASCII digits, so this branch shouldn't fire in practice.
            val speed = speedStr.toIntOrNull() ?: 0

            val odoStr = String(frame, 5, 6, Charsets.US_ASCII)
            val odo = if (odoStr.all { it in '0'..'9' }) odoStr.toInt() else 0

            val tripA = decodeTrip(frame, 11)
            val tripB = decodeTrip(frame, 17)

            val b24 = frame[24].toInt() and 0xFF
            val fuelBars: Int? = if (b24 in 0x31..0x36) b24 - 0x30 else null

            val b25 = frame[25].toInt() and 0xFF
            val b26 = frame[26].toInt() and 0xFF
            val b27 = frame[27].toInt() and 0xFF
            val fuelEcon: Double? = if (b25 == 0xFF && b26 == 0xFF && b27 == 0xFF) {
                null
            } else {
                val v24 = (b25 shl 16) or (b26 shl 8) or b27
                val top13 = (v24 shr 11) and 0x1FFF
                val bot11 = v24 and 0x7FF
                (top13 + bot11 / 2048.0) / 10.0
            }

            return TelemetryFrame(
                speedKmh = speed,
                odometerKm = odo,
                tripAKm = tripA,
                tripBKm = tripB,
                fuelBars = fuelBars,
                fuelEconKml = fuelEcon,
                byte23 = frame[23].toInt() and 0xFF,
                raw = frame.copyOf(),
            )
        }

        private fun decodeTrip(frame: ByteArray, start: Int): Double {
            val s = String(frame, start, 6, Charsets.US_ASCII)
            if (s.length != 6 || !s.all { it in '0'..'9' }) return 0.0
            return (s.substring(0, 5) + "." + s.substring(5)).toDouble()
        }

        /** Format a trip distance (XXXXX.X) as a 6-byte ASCII slice with the dot stripped. */
        private fun tripBytes(t: Double): ByteArray {
            val clamped = max(0.0, min(99999.9, t))
            // Python f"{t:07.1f}" → e.g. 1.5 → "00001.5"; strip "." → "000015"; take last 6 → "000015".
            // For values up to 99999.9 the width-7 format always gives exactly 7 chars including ".".
            val s = "%07.1f".format(clamped).replace(".", "")
            return s.takeLast(6).toByteArray(Charsets.US_ASCII)
        }

        /** Right-justify [value] as ASCII digits in [width] bytes, zero-padded. */
        private fun writeRightJustifiedAsciiInt(buf: ByteArray, offset: Int, width: Int, value: Int) {
            // Match Python _ascii(): convert to string, rjust with '0', take last `width` if longer.
            var s = value.toString().padStart(width, '0')
            if (s.length > width) s = s.takeLast(width)
            val bytes = s.toByteArray(Charsets.US_ASCII)
            System.arraycopy(bytes, 0, buf, offset, width)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TelemetryFrame) return false
        return speedKmh == other.speedKmh &&
            odometerKm == other.odometerKm &&
            tripAKm == other.tripAKm &&
            tripBKm == other.tripBKm &&
            fuelBars == other.fuelBars &&
            fuelEconKml == other.fuelEconKml &&
            byte23 == other.byte23 &&
            ((raw == null && other.raw == null) || (raw != null && other.raw != null && raw.contentEquals(other.raw)))
    }

    override fun hashCode(): Int {
        var result = speedKmh
        result = 31 * result + odometerKm
        result = 31 * result + tripAKm.hashCode()
        result = 31 * result + tripBKm.hashCode()
        result = 31 * result + (fuelBars ?: 0)
        result = 31 * result + (fuelEconKml?.hashCode() ?: 0)
        result = 31 * result + byte23
        result = 31 * result + (raw?.contentHashCode() ?: 0)
        return result
    }
}
