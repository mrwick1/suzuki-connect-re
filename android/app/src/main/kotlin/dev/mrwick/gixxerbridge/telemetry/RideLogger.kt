package dev.mrwick.gixxerbridge.telemetry

import dev.mrwick.gixxerbridge.data.RideStore
import dev.mrwick.gixxerbridge.location.RideLocationTracker
import dev.mrwick.gixxerbridge.protocol.TelemetryFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bridges [TelemetryRepository.latest] -> [RideStore].
 *
 * Auto-creates a ride on the first non-zero-odo sample after [attach] is called.
 * Auto-ends after [silenceTimeoutMs] of no new telemetry (watchdog).
 *
 * Owner: BikeBridgeService. Call [attach] once on service start, passing the
 * service's `lifecycleScope`. Cancel the scope (or call [endRide] from onDestroy)
 * to stop logging.
 *
 * Thread-safety: ride mutation paths ([onSample], [endRide]) serialize via a
 * [Mutex] so the watchdog and the sample collector cannot race on `rideId`.
 */
class RideLogger(
    private val store: RideStore,
    private val telemetry: StateFlow<TelemetryFrame?>,
    private val locationTracker: RideLocationTracker? = null,
    private val silenceTimeoutMs: Long = 10 * 60 * 1000L, // 10 min
    /**
     * Optional callback fired after a non-discarded ride ends, with the ride's
     * database id. Used by BikeBridgeService to publish to AppGraph.lastFinishedRideId
     * so the post-ride summary dialog can show in the UI.
     *
     * Called from a coroutine context (inside endRideInternal mutex), so
     * implementations must be non-blocking.
     */
    private val onRideEnded: ((rideId: Long) -> Unit)? = null,
) {
    private val mutex = Mutex()
    private var rideId: Long? = null
    private var lastSampleMillis: Long = 0
    // Wall-clock millis when this ride was first persisted (i.e. the first
    // non-zero-odo sample landed). Captured at startRide so [autoName] can
    // derive the time-of-day / day-of-week tag at ride-end without depending
    // on the wall clock at endRide (which would mis-bucket overnight rides).
    private var startedAtMillis: Long = 0
    private var startOdo: Int = 0
    private var lastFuel: Int? = null
    private var trackerStarted: Boolean = false

    /** Begin observing telemetry; runs until [scope] is cancelled. */
    fun attach(scope: CoroutineScope) {
        // Watchdog: end ride if silent > silenceTimeoutMs
        scope.launch {
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                val id = rideId ?: continue
                val now = System.currentTimeMillis()
                if (now - lastSampleMillis > silenceTimeoutMs) {
                    endRideInternal()
                }
                // Suppress unused-id warning while keeping the cheap pre-check.
                @Suppress("UNUSED_EXPRESSION") id
            }
        }
        scope.launch {
            telemetry.collect { frame -> if (frame != null) onSample(frame) }
        }
        // GPS sink: pipe location samples into the store for the active ride.
        // Distinct null skip handled inline; we just check rideId at write time.
        locationTracker?.let { tracker ->
            scope.launch {
                tracker.samples.collect { sample ->
                    if (sample == null) return@collect
                    val id = rideId ?: return@collect
                    store.appendLocation(
                        rideId = id,
                        tMillis = sample.tMillis,
                        lat = sample.lat,
                        lng = sample.lng,
                        altitudeM = sample.altitudeM,
                        accuracyM = sample.accuracyM,
                    )
                }
            }
        }
    }

    private suspend fun onSample(frame: TelemetryFrame) {
        if (frame.odometerKm <= 0) return
        mutex.withLock {
            val now = System.currentTimeMillis()
            val id = rideId ?: run {
                startOdo = frame.odometerKm
                startedAtMillis = now
                val new = store.startRide(
                    startedAtMillis = now,
                    startOdoKm = startOdo,
                    fuelBars = frame.fuelBars,
                )
                rideId = new
                // First-sample-of-ride hook: spin up GPS tracking.
                // No-op if FINE_LOCATION not granted (tracker self-guards).
                if (!trackerStarted) {
                    trackerStarted = locationTracker?.start() ?: false
                }
                new
            }
            store.appendSample(
                rideId = id,
                tMillis = now,
                speedKmh = frame.speedKmh,
                odometerKm = frame.odometerKm,
                tripA = frame.tripAKm,
                tripB = frame.tripBKm,
                fuelBars = frame.fuelBars,
                fuelEconKml = frame.fuelEconKmlV2 ?: frame.fuelEconKml,
            )
            lastSampleMillis = now
            lastFuel = frame.fuelBars
        }
    }

    /** End the active ride (if any). Safe to call repeatedly. */
    suspend fun endRide() = endRideInternal()

    private suspend fun endRideInternal() {
        mutex.withLock {
            val id = rideId ?: return
            val last = telemetry.value
            val endOdo = last?.odometerKm ?: startOdo
            val distance = endOdo - startOdo
            val now = System.currentTimeMillis()
            val durationMs = now - lastSampleMillis  // proxy for ride duration

            // Discard noise: if the bike was on for <30 s AND moved <1 km, this is
            // probably a key-on-and-off blip (or Demo mode flapping) — drop the
            // record entirely rather than polluting the trip log with empty rows.
            // ASSUMED: 1 km + 30 s is the right threshold; bump if real ride starts
            // get discarded.
            val shouldDiscard = distance < 1 && durationMs < MIN_RIDE_DURATION_MS
            if (shouldDiscard) {
                store.deleteRide(id)
            } else {
                val name = autoName(startedAtMillis, distance)
                store.endRide(
                    rideId = id,
                    endedAtMillis = now,
                    endOdoKm = endOdo,
                    fuelBarsEnd = lastFuel,
                    name = name,
                )
                onRideEnded?.invoke(id)
            }
            rideId = null
            if (trackerStarted) {
                locationTracker?.stop()
                trackerStarted = false
            }
        }
    }

    companion object {
        // Watchdog wakeup cadence. One minute is the original spec.
        internal const val WATCHDOG_TICK_MS = 60_000L
        // Minimum elapsed time + distance below which a "ride" is treated as
        // noise and silently dropped on end (instead of polluting the log).
        internal const val MIN_RIDE_DURATION_MS = 30_000L

        /**
         * Heuristic auto-name for a finished ride. Combines time-of-day,
         * weekday-vs-weekend, and distance signals into a short title like
         * "Morning commute (Mon)" or "Weekend ride (Sat)". User can override
         * via TripDetailScreen.
         *
         * - Time-of-day buckets: Morning 5-9, Day 10-15, Evening 16-18,
         *   Night 19-22, Late night otherwise.
         * - "commute" requires weekday + sub-20 km. Morning commutes are
         *   "Morning commute"; evening returns are "Evening commute home".
         * - Everything else is a "ride".
         *
         * Pure function; depends only on the system default timezone for
         * bucketing (which matches how the rider experiences time-of-day).
         */
        fun autoName(startedAtMillis: Long, distance: Int): String {
            val zdt = java.time.Instant.ofEpochMilli(startedAtMillis)
                .atZone(java.time.ZoneId.systemDefault())
            val hour = zdt.hour
            val dow = zdt.dayOfWeek
            val isWeekend =
                dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY
            val period = when (hour) {
                in 5..9 -> "Morning"
                in 10..15 -> "Day"
                in 16..18 -> "Evening"
                in 19..22 -> "Night"
                else -> "Late night"
            }
            val tag = when {
                isWeekend && distance > 30 -> "ride"
                !isWeekend && period == "Morning" && distance < 20 -> "commute"
                !isWeekend && period == "Evening" && distance < 20 -> "commute home"
                else -> "ride"
            }
            // 3-letter weekday tag: "Mon", "Tue", ... — keeps the title short.
            val day = dow.name.lowercase()
                .replaceFirstChar { it.uppercase() }
                .take(3)
            return "$period $tag ($day)"
        }
    }
}
