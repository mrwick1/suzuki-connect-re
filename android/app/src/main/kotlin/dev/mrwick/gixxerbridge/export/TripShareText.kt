package dev.mrwick.gixxerbridge.export

import dev.mrwick.gixxerbridge.analytics.RideAnalytics
import dev.mrwick.gixxerbridge.data.RideEntity
import dev.mrwick.gixxerbridge.data.RideLocationEntity
import dev.mrwick.gixxerbridge.data.RideSampleEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Formats one completed ride into a compact, LLM-ready plain-text block that
 * the user can paste into ChatGPT / Gemini / Claude for AI-driven analysis.
 *
 * DESIGN RULES:
 * - Pure function: no Android imports, no DB access, no Context, no clock calls.
 * - Deterministic: all time strings are derived from entity timestamps only.
 * - Anything estimated / modelled is labelled "(est.)".
 * - Consistent with the project's no-assumptions rule: only states what is
 *   directly observed in the entity fields or straightforwardly computed from
 *   them; hypotheses are not stated as facts.
 */
object TripShareText {

    /**
     * Build a human-readable, LLM-ready summary of one ride.
     *
     * @param ride        The [RideEntity] to summarise. [ride.endedAtMillis] and
     *                    [ride.endOdoKm] may be null (in-progress or legacy row);
     *                    the output will note those fields as unavailable.
     * @param samples     All [RideSampleEntity] rows for this ride, oldest-first.
     * @param locations   All [RideLocationEntity] rows for this ride, oldest-first.
     *                    Empty list is accepted (GPS section is omitted).
     * @param zone        IANA timezone id string used for displaying wall-clock
     *                    times, e.g. "Asia/Kolkata". Defaults to "UTC".
     */
    fun build(
        ride: RideEntity,
        samples: List<RideSampleEntity>,
        locations: List<RideLocationEntity>,
        zone: String = "UTC",
        children: List<RideEntity> = emptyList(),
        fillKmPerL: Double? = null,
    ): String {
        val tz = TimeZone.getTimeZone(zone)
        val dateTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).apply { timeZone = tz }
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
        val timeFmt = SimpleDateFormat("HH:mm:ss z", Locale.US).apply { timeZone = tz }

        return buildString {
            // ---- Preamble ----
            appendLine(PREAMBLE)
            appendLine()

            // ---- Identity ----
            appendLine("=== RIDE OVERVIEW ===")
            val displayName = ride.name ?: "Ride #${ride.id}"
            appendLine("Name       : $displayName")
            appendLine("Start      : ${dateTimeFmt.format(Date(ride.startedAtMillis))}")

            val endStr = ride.endedAtMillis
                ?.let { timeFmt.format(Date(it)) }
                ?: "(in progress)"
            appendLine("End        : $endStr")

            // Duration
            val durationStr = if (ride.endedAtMillis != null) {
                val totalSec = (ride.endedAtMillis - ride.startedAtMillis) / 1000L
                formatDuration(totalSec)
            } else {
                "(in progress)"
            }
            appendLine("Duration   : $durationStr")
            appendLine()

            // ---- Distance ----
            appendLine("=== DISTANCE ===")
            val distKm = if (ride.endOdoKm != null) {
                max(0, ride.endOdoKm - ride.startOdoKm)
            } else null
            appendLine("Distance   : ${distKm?.let { "$it km" } ?: "(end odometer not recorded)"}")
            appendLine("Start odo  : ${ride.startOdoKm} km")
            appendLine("End odo    : ${ride.endOdoKm?.let { "$it km" } ?: "(not recorded)"}")
            appendLine()

            // ---- Merged journey breakdown ----
            if (children.isNotEmpty()) {
                appendLine("=== MERGED JOURNEY — ${children.size} SEGMENTS ===")
                appendLine("This trip is one journey combined from ${children.size} key-off-separated segments:")
                children.sortedBy { it.startedAtMillis }.forEach { c ->
                    val ckm = if (c.endOdoKm != null) max(0, c.endOdoKm - c.startOdoKm) else 0
                    appendLine(
                        "  - ${timeFmt.format(Date(c.startedAtMillis))}  ${ckm} km  " +
                            "(odo ${c.startOdoKm}→${c.endOdoKm?.toString() ?: "?"})"
                    )
                }
                appendLine()
            }

            // ---- Speed ----
            appendLine("=== SPEED ===")
            appendLine("Max speed  : ${ride.maxSpeedKmh} km/h")
            // avgSpeedKmh is a moving average computed at ride-end (ignores stationary samples)
            appendLine("Avg speed  : ${"%.1f".format(ride.avgSpeedKmh)} km/h  [moving average, stops excluded]")

            val (movingMin, idleMin) = RideAnalytics.movingIdleMinutes(samples)
            if (movingMin > 0 || idleMin > 0) {
                appendLine("Moving/idle: ${movingMin} min moving, ${idleMin} min idle  [idle = stopped, engine on; excludes key-off rest gaps]")
            }

            if (samples.isNotEmpty()) {
                val speedProfile = buildSpeedProfile(samples)
                appendLine("Speed dist : $speedProfile")
            }
            appendLine()

            // ---- Fuel ----
            appendLine("=== FUEL ===")
            val fuelUsedBars = if (ride.fuelBarsStart != null && ride.fuelBarsEnd != null) {
                max(0, ride.fuelBarsStart - ride.fuelBarsEnd)
            } else null
            appendLine("Fuel start : ${ride.fuelBarsStart?.let { "$it bars" } ?: "(not recorded)"}")
            appendLine("Fuel end   : ${ride.fuelBarsEnd?.let { "$it bars" } ?: "(not recorded)"}")
            appendLine("Fuel used  : ${fuelUsedBars?.let { "$it bar(s)" } ?: "(not available)"}")

            // Mileage: prefer the rider's FILL-measured km/L. The BLE cluster econ
            // field over-reads ~30% on this bike, so it's labelled untrustworthy.
            appendLine(
                "Mileage    : ${fillKmPerL?.let { "${"%.1f".format(it)} km/L  [fill-measured, trustworthy]" }
                    ?: "(no fuel fills logged — can't measure accurately)"}"
            )
            val avgEcon = RideAnalytics.avgBikeEcon(samples)
            appendLine(
                "Bike econ  : ${avgEcon?.let { "${"%.1f".format(it)} km/L  [BLE cluster field — OVER-READS ~30%, do NOT treat as real mileage]" }
                    ?: "(not recorded)"}"
            )

            // Estimated litres — prefer fill-measured km/L; fall back to bike econ.
            val econForBurn = fillKmPerL ?: avgEcon
            if (distKm != null && econForBurn != null) {
                val burn = RideAnalytics.fuelBurnt(distKm, fillKmPerL = fillKmPerL, bikeKmPerL = avgEcon)
                if (burn != null) {
                    val basis = if (fillKmPerL != null) "fill-measured km/L" else "bike econ (over-reads)"
                    appendLine("Fuel burnt : ${"%.2f".format(burn.litres)} L (est.)  [distance ÷ $basis]")
                }
            }
            appendLine()

            // ---- Samples summary ----
            appendLine("=== TELEMETRY ===")
            appendLine("Samples    : ${ride.sampleCount}  [BLE ~1 Hz cadence]")
            if (samples.isNotEmpty()) {
                val firstT = dateFmt.format(Date(samples.first().tMillis))
                val lastT = timeFmt.format(Date(samples.last().tMillis))
                appendLine("Sample span: $firstT  ${timeFmt.format(Date(samples.first().tMillis))} → $lastT")

                val tripAKm = samples.lastOrNull()?.tripAKm
                val tripBKm = samples.lastOrNull()?.tripBKm
                if (tripAKm != null) appendLine("Trip A     : ${"%.2f".format(tripAKm)} km  [last sample]")
                if (tripBKm != null) appendLine("Trip B     : ${"%.2f".format(tripBKm)} km  [last sample]")
            }
            appendLine()

            // ---- GPS ----
            if (locations.isNotEmpty()) {
                appendLine("=== GPS TRACK ===")
                appendLine("GPS points : ${locations.size}  [~0.2 Hz balanced-power]")

                val latMin = locations.minOf { it.lat }
                val latMax = locations.maxOf { it.lat }
                val lngMin = locations.minOf { it.lng }
                val lngMax = locations.maxOf { it.lng }

                // Rough bounding-box span in km (Haversine approximation for small boxes)
                val latSpanKm = latLngToKm(latMax - latMin, 0.0)
                val lngSpanKm = latLngToKm(0.0, lngMax - lngMin, midLat = (latMin + latMax) / 2.0)
                appendLine(
                    "Bounding box: ${fmtCoord(latMin)}–${fmtCoord(latMax)} N × " +
                        "${fmtCoord(lngMin)}–${fmtCoord(lngMax)} E  " +
                        "[≈${"%.1f".format(latSpanKm)} × ${"%.1f".format(lngSpanKm)} km] (est.)"
                )

                val alts = locations.mapNotNull { it.altitudeM }
                if (alts.isNotEmpty()) {
                    appendLine(
                        "Altitude   : min ${"%.0f".format(alts.min())} m, " +
                            "max ${"%.0f".format(alts.max())} m, " +
                            "gain ≈ ${"%.0f".format(altGain(locations))} m (est.)"
                    )
                }
                appendLine()
            }

            // ---- Footer ----
            appendLine("---")
            appendLine("Source: Suzuki Gixxer SF 150 via REDLINE BLE logger")
            appendLine("Exported: ${dateFmt.format(Date(ride.startedAtMillis))} ride")
        }.trimEnd()
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private const val PREAMBLE =
        "Analyse this motorcycle ride and give me insights on efficiency, pace, and anything notable:"

    /** e.g. "1h 23m 45s" or "5m 02s" */
    private fun formatDuration(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%dh %02dm %02ds".format(h, m, s)
        else "%dm %02ds".format(m, s)
    }

    /**
     * Compact speed-distribution string.
     * Buckets: 0–29, 30–59, 60–89, 90–119, 120+ km/h.
     * Each bucket shows its share as a percentage, omitting 0% buckets.
     */
    private fun buildSpeedProfile(samples: List<RideSampleEntity>): String {
        if (samples.isEmpty()) return "(no samples)"
        val labels = listOf("0–29", "30–59", "60–89", "90–119", "120+")
        val counts = IntArray(5)
        for (s in samples) {
            val idx = min(s.speedKmh / 30, 4)
            counts[idx]++
        }
        val total = samples.size.toDouble()
        return labels.indices
            .filter { counts[it] > 0 }
            .joinToString(", ") { i ->
                val pct = (counts[i] / total * 100).roundToInt()
                "${labels[i]} km/h: $pct%"
            }
    }

    /** Rough lat/lng bounding-box span → km (equirectangular, accurate enough for <50 km boxes). */
    private fun latLngToKm(latDeltaDeg: Double, lngDeltaDeg: Double, midLat: Double = 0.0): Double {
        val latKm = abs(latDeltaDeg) * 111.0
        val lngKm = abs(lngDeltaDeg) * 111.0 * cos(Math.toRadians(midLat))
        return if (latDeltaDeg != 0.0) latKm else lngKm
    }

    /** Cumulative altitude gain (sum of positive consecutive rises). */
    private fun altGain(locations: List<RideLocationEntity>): Double {
        var gain = 0.0
        var prev: Double? = null
        for (loc in locations) {
            val alt = loc.altitudeM ?: continue
            if (prev != null && alt > prev) gain += alt - prev
            prev = alt
        }
        return gain
    }

    /** Format a coordinate to 4 decimal places (~11 m precision, fine for a summary). */
    private fun fmtCoord(v: Double): String = "%.4f".format(v)
}
