package dev.mrwick.gixxerbridge.safety

import dev.mrwick.gixxerbridge.protocol.TelemetryFrame
import kotlinx.coroutines.flow.StateFlow

/**
 * Watches a stream of [TelemetryFrame]s for sudden deceleration.
 *
 * Heuristic: looking at the last 3 samples (~10-15s at the bike's ~5s a537 cadence),
 * if the peak speed minus the latest speed is >= [dropKmh] AND the latest sample is
 * below 5 km/h (i.e. the bike has nearly come to rest), call [onCrashSuspected].
 *
 * A [cooldownMs] window suppresses re-fires for the same event.
 *
 * Hypothesis: a real crash will show as a sharp drop from cruise speed to ~0 within
 * 1-2 a537 samples. To verify in M3 with on-bike testing (drop from 40 km/h to 0 by
 * braking hard) — the threshold may need tuning.
 */
class CrashDetector(
    private val history: StateFlow<List<TelemetryFrame>>,
    private val onCrashSuspected: () -> Unit,
    private val dropKmh: Int = 30,
    private val cooldownMs: Long = 60_000,
) {
    private var lastAlertMs: Long = 0

    suspend fun run() {
        history.collect { samples ->
            if (samples.size < 3) return@collect
            val now = System.currentTimeMillis()
            if (now - lastAlertMs < cooldownMs) return@collect
            val tail = samples.takeLast(3)
            val maxRecent = tail.maxOf { it.speedKmh }
            val latest = tail.last().speedKmh
            if (maxRecent - latest >= dropKmh && latest < 5) {
                lastAlertMs = now
                onCrashSuspected()
            }
        }
    }
}
