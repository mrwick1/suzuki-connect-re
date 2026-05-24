package dev.mrwick.gixxerbridge.telemetry

import dev.mrwick.gixxerbridge.protocol.TelemetryFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * Synthesises a believable a537 telemetry stream for use without a bike connected.
 *
 * - Speed sweeps 0..80 km/h via a slow sine (one full period ≈ 12 ticks ≈ 60 s).
 * - Odometer increments by 1 km every 6 ticks (~30 s of simulated riding).
 * - Fuel bars decay from 6 down to 1 over 60 ticks (~5 min).
 *
 * The encode → decode round-trip is intentional: it populates [TelemetryFrame.raw]
 * so consumers that depend on `fuelEconKmlV2` (which reads `raw[25]`) get sensible
 * values instead of `null`.
 */
class DemoTelemetrySource(private val startOdo: Int = 17000) {
    private var job: Job? = null

    /** Start emitting synthetic frames into [TelemetryRepository] every 5 s. */
    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            var t = 0
            var odo = startOdo
            var fuel = 6
            while (isActive) {
                val speed = (40 + (40 * sin(t / 6.0))).toInt().coerceIn(0, 100)
                val tripA = (odo - startOdo).toDouble()
                val tripB = (odo - startOdo) * 0.4
                // The Dashboard uses [TelemetryFrame.fuelEconKmlV2] which is `raw[25] / 2.0`.
                // The legacy encoder packs `fuelEconKml * 10` into a 24-bit fixed-point and
                // byte 25 ends up = (top13 << 11 >> 16). To make V2 ~= 48 km/L (matches the
                // Gixxer SF 150's real-world average) we need byte[25] ~= 96, which means
                // priming fuelEconKml ~= 307. Hacky but keeps DemoSource self-contained.
                val frame = TelemetryFrame(
                    speedKmh = speed,
                    odometerKm = odo,
                    tripAKm = tripA,
                    tripBKm = tripB,
                    fuelBars = fuel,
                    fuelEconKml = 307.0,
                ).encode()
                TelemetryRepository.update(TelemetryFrame.decode(frame))
                t++
                if (t % 6 == 0) odo += 1
                if (t % 60 == 0 && fuel > 1) fuel -= 1
                delay(5_000)
            }
        }
    }

    /** Stop emitting; safe to call repeatedly. */
    fun stop() {
        job?.cancel()
        job = null
    }
}
