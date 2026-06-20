package dev.mrwick.redline.export

import dev.mrwick.redline.analytics.BikeHealth
import dev.mrwick.redline.analytics.CostAnalytics
import dev.mrwick.redline.analytics.FuelTankEstimator
import dev.mrwick.redline.analytics.MileageAnalytics
import dev.mrwick.redline.analytics.RideAnalytics
import dev.mrwick.redline.analytics.RideStreak
import dev.mrwick.redline.analytics.RouteClustering
import dev.mrwick.redline.analytics.RouteLeaderboard
import dev.mrwick.redline.analytics.RunningCostAnalytics
import dev.mrwick.redline.analytics.ServiceEta
import dev.mrwick.redline.analytics.ServiceSchedule
import dev.mrwick.redline.ble.BikeInfo
import dev.mrwick.redline.data.FuelFillEntity
import dev.mrwick.redline.data.RideEntity
import dev.mrwick.redline.data.RideLocationEntity
import dev.mrwick.redline.data.RideSampleEntity
import dev.mrwick.redline.data.ServiceItem
import dev.mrwick.redline.data.ServiceItemState
import dev.mrwick.redline.data.ServiceLogEntity
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

/**
 * Builds a single self-contained, LLM-ready "full archive" text report of the
 * whole bike — every ride, all telemetry, fuel/cost/service, and the derived
 * analytics — for upload to an AI assistant.
 *
 * Pure: no Android imports, no DB/Context, no clock calls. Deterministic given
 * its inputs (`now` + `zone` passed in). Estimates labelled "(est.)"; data
 * caveats stated inline (no-assumptions rule). Mirrors [TripShareText].
 */
object AllBikeExporter {

    private const val PREAMBLE =
        "Analyse my motorcycle's health, performance, fuel economy and running " +
            "cost from the complete data below. Data-quality notes are stated " +
            "inline — respect them (the BLE econ field is unreliable)."

    fun build(
        rides: List<RideEntity>,
        samplesByRide: Map<Long, List<RideSampleEntity>>,
        locationsByRide: Map<Long, List<RideLocationEntity>>,
        fills: List<FuelFillEntity>,
        services: List<ServiceLogEntity>,
        currentOdoKm: Int?,
        currentFuelBars: Int?,
        tankCapacityL: Double,
        serviceIntervalKm: Int,
        serviceSchedule: Map<ServiceItem, ServiceItemState>,
        bikeMac: String?,
        bikeInfo: BikeInfo?,
        riderName: String,
        zone: ZoneId,
        now: Long,
    ): String {
        val tz = TimeZone.getTimeZone(zone.id)
        val dt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).apply { timeZone = tz }
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone)

        // Merge-aware ride partitions (verified against real data).
        val topLevel = rides.filter { it.parentRideId == null }          // parents + normals
        val dataRides = rides.filter { !it.isMerged }                    // normals + children (carry samples)
        val allSamples = dataRides.flatMap { samplesByRide[it.id].orEmpty() }

        val avgKmPerL = MileageAnalytics.averageKmPerL(fills)
        val perTank = MileageAnalytics.perTankKmPerL(fills)
        val lastSvcOdo = serviceSchedule[ServiceItem.PERIODIC_SERVICE]?.lastServiceOdoKm ?: 0
        val totalKm = topLevel.sumOf { r -> r.endOdoKm?.let { max(0, it - r.startOdoKm) } ?: 0 }
        val gpsRideCount = dataRides.count { locationsByRide[it.id].orEmpty().isNotEmpty() }

        return buildString {
            appendLine(PREAMBLE)
            appendLine()
            appendLine("Exported: ${dt.format(Date(now))}")
            appendLine("Rider: $riderName")
            appendLine()

            // ---- DATA QUALITY ----
            appendLine("=== DATA QUALITY NOTES ===")
            appendLine("- BLE cluster econ (fuelEconKml) OVER-READS ~30% on this bike; do NOT treat it as real mileage.")
            appendLine("- Only fill-measured km/L is trustworthy. Fuel fills logged: ${fills.size} (=> ${max(0, fills.size - 1)} measured interval(s)).")
            appendLine("- GPS coverage: $gpsRideCount of ${dataRides.size} recorded ride(s) have a track.")
            appendLine("- Service log entries: ${services.size}${if (services.isEmpty()) " (maintenance below is projection-only)" else ""}.")
            val first = topLevel.minByOrNull { it.startedAtMillis }?.startedAtMillis
            val lastEnd = topLevel.mapNotNull { it.endedAtMillis }.maxOrNull()
            appendLine("- Baseline: ${first?.let { d.format(Date(it)) } ?: "?"} -> ${lastEnd?.let { d.format(Date(it)) } ?: "?"}, $totalKm km over ${topLevel.size} ride(s).")
            appendLine("- Merged journeys are counted once (parent); their segments are excluded from totals but present in raw telemetry.")
            appendLine()

            // ---- BIKE PROFILE ----
            appendLine("=== BIKE PROFILE ===")
            appendLine("Current odometer : ${currentOdoKm?.let { "$it km" } ?: "(unknown)"}")
            appendLine("Tank capacity    : ${"%.1f".format(tankCapacityL)} L")
            appendLine("Service interval : $serviceIntervalKm km (legacy single-item)")
            appendLine("Bike MAC         : ${bikeMac ?: "(not paired)"}")
            if (bikeInfo != null) {
                appendLine("Manufacturer     : ${bikeInfo.manufacturer ?: "?"}")
                appendLine("Model            : ${bikeInfo.modelNumber ?: "?"}")
                appendLine("Serial           : ${bikeInfo.serialNumber ?: "?"}")
                appendLine("Firmware         : ${bikeInfo.firmwareRevision ?: "?"}")
            }
            appendLine()

            // ---- HEALTH ----
            val health = BikeHealth.compute(currentOdoKm, lastSvcOdo, serviceIntervalKm, currentFuelBars, topLevel, now)
            appendLine("=== HEALTH ===")
            appendLine("Score   : ${health.total}/100 (${health.grade})")
            appendLine("Sub     : service ${health.service}, fuel ${health.fuel}, connection ${health.connection}")
            if (health.insufficientData) appendLine("Note    : insufficient data for a confident score.")
            appendLine()

            // ---- FUEL & MILEAGE ----
            appendLine("=== FUEL & MILEAGE ===")
            appendLine("Avg mileage : ${avgKmPerL?.let { "${"%.1f".format(it)} km/L  [fill-measured, trustworthy]" } ?: "(need >= 2 fills)"}")
            if (perTank.isNotEmpty()) {
                appendLine("Per-tank km/L (fillId: km/L):")
                perTank.forEach { (fid, kmpl) -> appendLine("  - fill $fid: ${"%.1f".format(kmpl)}") }
            }
            val tank = FuelTankEstimator.estimate(
                fills = fills, currentOdometerKm = currentOdoKm, avgKmPerL = avgKmPerL,
                bikeLiveKmPerL = null, bikeFuelBars = currentFuelBars, capacityL = tankCapacityL,
            )
            if (tank != null) {
                appendLine("Current tank: ${"%.1f".format(tank.litresLeft)} L (${(tank.percent * 100).toInt()}%), range ~${tank.rangeKm.toInt()} km (est.)${if (tank.isRough) " [rough, pre-first-fill]" else ""}")
            }
            appendLine()

            // ---- RUNNING COST ----
            appendLine("=== RUNNING COST ===")
            val cost = CostAnalytics.stats(fills)
            if (cost != null) {
                appendLine("Fuel Rs/km : rolling ${"%.2f".format(cost.rollingRupeesPerKm)}, lifetime ${"%.2f".format(cost.lifetimeRupeesPerKm)}  [${cost.pricedIntervals}/${cost.totalIntervals} intervals priced]")
            } else {
                appendLine("Fuel Rs/km : (need >= 2 priced fills)")
            }
            val running = RunningCostAnalytics.cost(fills, services, fallbackDistanceKm = totalKm)
            if (running != null) {
                appendLine("Blended   : Rs ${"%.2f".format(running.rupeesPerKm)}/km over ${running.distanceKm} km — fuel Rs ${running.fuelRupees.toInt()} (${(running.fuelFraction * 100).toInt()}%), service Rs ${running.serviceRupees.toInt()} (${(running.serviceFraction * 100).toInt()}%)")
            }
            val monthly = RunningCostAnalytics.monthlySpend(fills, services, now = now, zone = zone)
            if (monthly.isNotEmpty()) {
                appendLine("Monthly spend:")
                monthly.forEach { m -> appendLine("  - ${m.month}: Rs ${m.totalRupees.toInt()} (fuel Rs ${m.fuelRupees.toInt()}, service Rs ${m.serviceRupees.toInt()})") }
            }
            appendLine()

            // ---- SERVICE ----
            appendLine("=== SERVICE ===")
            val pace = ServiceEta.paceKmPerDay(topLevel, now = now)
            appendLine("Riding pace : ${"%.1f".format(pace)} km/day (recent window)")
            val sched = ServiceSchedule.mostOverdue(serviceSchedule.values, currentOdoKm, now)
            if (sched.perItem.isEmpty()) {
                appendLine("(no service items configured)")
            } else {
                sched.perItem.forEach { h ->
                    val eta = ServiceEta.forecast(h, pace, now)
                    val km = h.kmRemaining?.let { "$it km" } ?: "-"
                    val days = h.daysRemaining?.let { "$it d" } ?: "-"
                    val etaStr = eta?.let { ServiceEta.formatRelative(it) + if (it.isOverdue) " (OVERDUE)" else "" } ?: "no baseline"
                    appendLine("  - ${h.state.item.label}: km left $km, days left $days, ETA $etaStr")
                }
            }
            appendLine()

            // ---- PERFORMANCE ----
            appendLine("=== PERFORMANCE ===")
            val lifetime = RideAnalytics.totalsFor(topLevel, days = 36_500L, now = now)
            appendLine("Totals  : ${lifetime.km} km, ${"%.1f".format(lifetime.hours)} h, ${lifetime.rides} rides")
            val pb = RideAnalytics.personalBests(topLevel, allSamples)
            appendLine("Bests   : longest ${pb.longestRideKm ?: "-"} km, top speed ${pb.topSpeedKmh ?: "-"} km/h, best econ ${pb.bestFuelEconKml?.let { "%.1f".format(it) } ?: "-"} km/L, most rides/day ${pb.mostRidesInDay ?: "-"}")
            val (mov, idle) = RideAnalytics.movingIdleMinutes(allSamples)
            appendLine("Time    : $mov min moving, $idle min idle (engine on, stopped)")
            val hist = RideAnalytics.speedHistogram(allSamples)
            val histStr = hist.filter { it.sampleCount > 0 }.joinToString(", ") { "${it.lowKmh}-${it.highKmh}:${it.sampleCount}" }
            if (histStr.isNotBlank()) appendLine("Speed   : [$histStr] (sample counts per km/h bucket)")
            appendLine()

            // ---- HABITS ----
            appendLine("=== HABITS ===")
            val streak = RideStreak.compute(topLevel, zone, today)
            appendLine("Streak  : current ${streak.current} day(s), longest ${streak.longest}")
            val clusters = RouteClustering.cluster(
                rides = topLevel,
                locationsForRide = { id -> locationsByRide[id].orEmpty() },
            )
            val board = RouteLeaderboard.leaderboard(clusters, topLevel, kmPerL = avgKmPerL)
            val tracked = board.filter { !it.isUntracked }
            if (tracked.isNotEmpty()) {
                appendLine("Routes  : ${tracked.size} recurring route(s)")
                tracked.take(5).forEach { rs ->
                    appendLine("  - route ${rs.clusterId}: ${rs.runCount} runs, median ${rs.medianDistanceKm ?: "-"} km")
                }
            } else {
                appendLine("Routes  : (none — needs more GPS-tracked rides)")
            }
            appendLine()

            // ---- PER-RIDE SUMMARY ----
            appendLine("=== PER-RIDE SUMMARY (${topLevel.size}) ===")
            appendLine("date | name | km | maxKmh | avgKmh | fuelBars | duration")
            topLevel.sortedBy { it.startedAtMillis }.forEach { r ->
                val km = r.endOdoKm?.let { max(0, it - r.startOdoKm) } ?: 0
                val dur = r.endedAtMillis?.let { fmtDur((it - r.startedAtMillis) / 1000L) } ?: "(in progress)"
                val tag = if (r.isMerged) " [merged journey]" else ""
                appendLine("${d.format(Date(r.startedAtMillis))} | ${r.name ?: "Ride #${r.id}"}$tag | $km | ${r.maxSpeedKmh} | ${"%.1f".format(r.avgSpeedKmh)} | ${r.fuelBarsStart ?: "?"}->${r.fuelBarsEnd ?: "?"} | $dur")
            }
            appendLine()

            // ---- CSV: FUEL FILLS ----
            appendLine("=== FUEL FILLS (CSV) ===")
            appendLine("t_iso,t_millis,odometer_km,litres,rupees,note")
            fills.sortedBy { it.tMillis }.forEach { f ->
                appendLine("${dt.format(Date(f.tMillis))},${f.tMillis},${f.odometerKm},${"%.2f".format(f.litres)},${f.rupees?.let { "%.2f".format(it) } ?: ""},${csv(f.note)}")
            }
            appendLine()

            // ---- CSV: SERVICE LOGS ----
            appendLine("=== SERVICE LOGS (CSV) ===")
            appendLine("t_iso,t_millis,odometer_km,type,rupees,notes")
            services.sortedBy { it.tMillis }.forEach { s ->
                appendLine("${dt.format(Date(s.tMillis))},${s.tMillis},${s.odometerKm},${csv(s.type)},${s.rupees?.let { "%.2f".format(it) } ?: ""},${csv(s.notes)}")
            }
            appendLine()

            // ---- CSV: GPS TRACKS ----
            appendLine("=== GPS TRACKS (CSV) ===")
            appendLine("ride_id,t_iso,t_millis,lat,lng,altitude_m,accuracy_m")
            dataRides.forEach { r ->
                locationsByRide[r.id].orEmpty().forEach { g ->
                    appendLine("${r.id},${dt.format(Date(g.tMillis))},${g.tMillis},${g.lat},${g.lng},${g.altitudeM ?: ""},${g.accuracyM ?: ""}")
                }
            }
            appendLine()

            // ---- CSV: FULL TELEMETRY (per ride, reuse CsvExporter) ----
            val teleTotal = dataRides.sumOf { samplesByRide[it.id].orEmpty().size }
            appendLine("=== FULL TELEMETRY (CSV, $teleTotal rows across ${dataRides.size} rides) ===")
            dataRides.sortedBy { it.startedAtMillis }.forEach { r ->
                val s = samplesByRide[r.id].orEmpty()
                if (s.isNotEmpty()) {
                    appendLine("--- ride ${r.id} (${r.name ?: "Ride #${r.id}"}) ---")
                    append(CsvExporter.rideSamplesToCsv(r, s))
                    appendLine()
                }
            }

            appendLine("---")
            appendLine("Source: Suzuki Gixxer SF 150 via REDLINE BLE logger")
        }.trimEnd()
    }

    private fun fmtDur(sec: Long): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, s)
    }

    /** Minimal CSV-escape for free-text fields. */
    private fun csv(v: String?): String {
        val t = v ?: return ""
        return if (t.contains(',') || t.contains('"') || t.contains('\n'))
            "\"" + t.replace("\"", "\"\"") + "\"" else t
    }
}
