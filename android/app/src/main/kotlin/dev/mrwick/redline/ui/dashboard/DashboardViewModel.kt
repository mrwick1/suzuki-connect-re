package dev.mrwick.redline.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.redline.analytics.RangeEstimator
import dev.mrwick.redline.data.GixxerDatabase
import dev.mrwick.redline.data.RideStore
import dev.mrwick.redline.protocol.TelemetryFrame
import dev.mrwick.redline.telemetry.TelemetryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** ViewModel for [DashboardScreen]; exposes the latest a537 telemetry + rolling history + range model. */
class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val store: RideStore = RideStore(GixxerDatabase.get(app).rideDao())

    /** Most recent telemetry frame from the bike, or null until the first a537 arrives. */
    val telemetry: StateFlow<TelemetryFrame?> = TelemetryRepository.latest

    /** Rolling window of recent telemetry frames (see TelemetryRepository.history). */
    val history: StateFlow<List<TelemetryFrame>> = TelemetryRepository.history

    /**
     * Median km-per-fuel-bar across qualifying rides; null until enough ride
     * history exists. Re-derived whenever the ride table changes.
     */
    val kmPerBar: StateFlow<Double?> = store.observeRides()
        .map { RangeEstimator.kmPerBar(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
