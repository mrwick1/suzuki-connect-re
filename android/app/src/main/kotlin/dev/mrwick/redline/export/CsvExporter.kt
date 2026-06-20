package dev.mrwick.redline.export

import dev.mrwick.redline.ble.FrameEvent
import dev.mrwick.redline.data.RideEntity
import dev.mrwick.redline.data.RideSampleEntity
import dev.mrwick.redline.util.Hex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Renders ride samples and Inspector frame buffers as CSV for offline analysis. */
object CsvExporter {

    /** CSV of a ride's telemetry samples. One row per [RideSampleEntity]. */
    fun rideSamplesToCsv(ride: RideEntity, samples: List<RideSampleEntity>): String = buildString {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        appendLine("# REDLINE ride #${ride.id} samples")
        appendLine("# Start: ${iso.format(Date(ride.startedAtMillis))}")
        appendLine("timestamp_iso,t_millis,speed_kmh,odometer_km,trip_a_km,trip_b_km,fuel_bars,fuel_econ_kml")
        for (s in samples) {
            append(iso.format(Date(s.tMillis))); append(',')
            append(s.tMillis); append(',')
            append(s.speedKmh); append(',')
            append(s.odometerKm); append(',')
            append("%.1f".format(s.tripAKm)); append(',')
            append("%.1f".format(s.tripBKm)); append(',')
            append(s.fuelBars ?: ""); append(',')
            append(s.fuelEconKml?.let { "%.1f".format(it) } ?: "")
            appendLine()
        }
    }

    /** CSV of an Inspector frame buffer. One row per [FrameEvent]. */
    fun framesToCsv(events: List<FrameEvent>): String = buildString {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        appendLine("timestamp_iso,t_millis,direction,type_byte,hex_bytes,note")
        for (e in events) {
            append(iso.format(Date(e.tMillis))); append(',')
            append(e.tMillis); append(',')
            append(e.direction.name); append(',')
            append(if (e.bytes.size > 1) "0x%02x".format(e.bytes[1].toInt() and 0xFF) else ""); append(',')
            append(Hex.encode(e.bytes, separator = "")); append(',')
            append(e.note?.replace(",", ";").orEmpty())
            appendLine()
        }
    }
}
