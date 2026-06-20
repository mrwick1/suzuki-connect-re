package dev.mrwick.redline.telemetry

import dev.mrwick.redline.protocol.TelemetryFrame
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

    // PERF: mutate-in-place rolling buffer guarded by `this`. Prior impl
    // rebuilt the entire history list on every a537 sample
    // (`(_history.value + frame).takeLast(60)`), allocating ~60 references
    // per ~5s tick. ArrayDeque keeps the bounded window and we publish an
    // immutable snapshot for observers (audit finding 3.2).
    private val historyBuffer: ArrayDeque<TelemetryFrame> = ArrayDeque(HISTORY_SIZE)

    fun update(frame: TelemetryFrame) {
        _latest.value = frame
        synchronized(this) {
            if (historyBuffer.size >= HISTORY_SIZE) historyBuffer.removeFirst()
            historyBuffer.addLast(frame)
            // R5: only publish the immutable snapshot when at least one collector is
            // active. The ArrayDeque push above always runs so that a subscriber
            // joining mid-ride immediately sees a correct rolling window; we just
            // skip the O(n) toList() copy when nobody is watching.
            if (_history.subscriptionCount.value > 0) {
                _history.value = historyBuffer.toList()
            }
        }
    }

    fun reset() {
        _latest.value = null
        synchronized(this) {
            historyBuffer.clear()
            _history.value = emptyList()
        }
    }

    private const val HISTORY_SIZE = 60
}
