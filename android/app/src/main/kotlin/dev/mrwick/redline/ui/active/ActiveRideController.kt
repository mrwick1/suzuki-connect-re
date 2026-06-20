package dev.mrwick.redline.ui.active

import dev.mrwick.redline.telemetry.TelemetryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-singleton active-ride state machine.
 *
 * Observes [TelemetryRepository.latest] and manages the active-ride overlay lifecycle:
 *
 *  - [isActive] flips to true when speed > 5 km/h for 3 consecutive seconds.
 *  - [isActive] flips to false 30 seconds after speed drops to 0 (or when
 *    [dismiss] is called, which suppresses activation until the next motion event).
 *  - [currentSpeedKmh] mirrors the latest reported speed (null when disconnected).
 *
 * The state machine runs on a background coroutine scoped to the process — it starts
 * once (object initialisation) and runs for the lifetime of the app process.
 *
 * Thread safety: all [MutableStateFlow] mutations happen on the coroutine dispatcher;
 * Compose collectors on the main thread see consistent snapshots.
 */
object ActiveRideController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isActive = MutableStateFlow(false)
    /** True when speed has exceeded the motion threshold and dismiss hasn't been called. */
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _currentSpeedKmh = MutableStateFlow<Int?>(null)
    /** Latest reported speed in km/h; null when no telemetry is available. */
    val currentSpeedKmh: StateFlow<Int?> = _currentSpeedKmh.asStateFlow()

    // Internal counters for the state machine.
    // aboveThresholdTicks: consecutive ticks where speed > SPEED_THRESHOLD_KMH
    // belowThresholdTicks: consecutive ticks where speed == 0 (after being active)
    // dismissed: set by dismiss(); cleared on the next above-threshold event
    private var aboveThresholdTicks = 0
    private var belowThresholdTicks = 0
    private var dismissed = false

    private const val SPEED_THRESHOLD_KMH = 5
    /** Consecutive seconds above threshold before isActive flips true. */
    private const val ACTIVATE_TICKS = 3
    /** Seconds at speed 0 before isActive flips false. */
    private const val DEACTIVATE_TICKS = 30
    /** Polling cadence for the state-machine loop (ms). */
    private const val TICK_MS = 1_000L

    init {
        scope.launch {
            // Tick loop: poll TelemetryRepository.latest every second and
            // advance the state machine.
            while (true) {
                val frame = TelemetryRepository.latest.value
                val speed = frame?.speedKmh ?: 0

                _currentSpeedKmh.value = frame?.speedKmh

                when {
                    // Speed above threshold: count up toward activation
                    speed > SPEED_THRESHOLD_KMH -> {
                        belowThresholdTicks = 0
                        aboveThresholdTicks++
                        if (aboveThresholdTicks >= ACTIVATE_TICKS) {
                            if (dismissed) {
                                // New motion after dismiss — clear the dismissed flag
                                // so the next ACTIVATE_TICKS streak can re-arm.
                                dismissed = false
                            }
                            if (!dismissed && !_isActive.value) {
                                _isActive.value = true
                            }
                        }
                    }
                    // Speed at 0 while active: count down toward deactivation
                    speed == 0 && _isActive.value -> {
                        aboveThresholdTicks = 0
                        belowThresholdTicks++
                        if (belowThresholdTicks >= DEACTIVATE_TICKS) {
                            _isActive.value = false
                            belowThresholdTicks = 0
                        }
                    }
                    // Speed at 0 while inactive — just reset counters
                    else -> {
                        aboveThresholdTicks = 0
                        belowThresholdTicks = 0
                    }
                }

                delay(TICK_MS)
            }
        }
    }

    /**
     * Forces [isActive] to false and suppresses re-activation until the next
     * motion event (speed > threshold) is detected.
     *
     * Called by the user single-tapping the active-ride overlay to dismiss it.
     */
    fun dismiss() {
        dismissed = true
        _isActive.value = false
        aboveThresholdTicks = 0
        belowThresholdTicks = 0
    }
}
