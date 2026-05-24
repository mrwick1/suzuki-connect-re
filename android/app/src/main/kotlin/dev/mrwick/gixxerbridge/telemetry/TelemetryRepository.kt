package dev.mrwick.gixxerbridge.telemetry

import dev.mrwick.gixxerbridge.protocol.TelemetryFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the bike's most recent telemetry (a537).
 * Dashboard observes; RideLogger writes via the BikeBridgeService.
 */
object TelemetryRepository {
    private val _latest = MutableStateFlow<TelemetryFrame?>(null)
    val latest: StateFlow<TelemetryFrame?> = _latest.asStateFlow()

    private val _history = MutableStateFlow<List<TelemetryFrame>>(emptyList())
    /** Rolling window of the last 60 frames (~5 min at 5s cadence). */
    val history: StateFlow<List<TelemetryFrame>> = _history.asStateFlow()

    fun update(frame: TelemetryFrame) {
        _latest.value = frame
        val next = (_history.value + frame).takeLast(HISTORY_SIZE)
        _history.value = next
    }

    fun reset() {
        _latest.value = null
        _history.value = emptyList()
    }

    private const val HISTORY_SIZE = 60
}
