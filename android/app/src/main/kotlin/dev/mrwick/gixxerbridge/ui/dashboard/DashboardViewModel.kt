package dev.mrwick.gixxerbridge.ui.dashboard

import androidx.lifecycle.ViewModel
import dev.mrwick.gixxerbridge.protocol.TelemetryFrame
import dev.mrwick.gixxerbridge.telemetry.TelemetryRepository
import kotlinx.coroutines.flow.StateFlow

/** ViewModel for [DashboardScreen]; exposes the latest a537 telemetry + rolling history. */
class DashboardViewModel : ViewModel() {
    /** Most recent telemetry frame from the bike, or null until the first a537 arrives. */
    val telemetry: StateFlow<TelemetryFrame?> = TelemetryRepository.latest

    /** Rolling window of recent telemetry frames (see TelemetryRepository.history). */
    val history: StateFlow<List<TelemetryFrame>> = TelemetryRepository.history
}
