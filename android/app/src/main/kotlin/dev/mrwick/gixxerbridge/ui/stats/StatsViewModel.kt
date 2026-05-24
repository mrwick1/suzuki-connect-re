package dev.mrwick.gixxerbridge.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.gixxerbridge.data.GixxerDatabase
import dev.mrwick.gixxerbridge.data.RideEntity
import dev.mrwick.gixxerbridge.data.RideSampleEntity
import dev.mrwick.gixxerbridge.data.RideStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Backs [StatsScreen]. Exposes:
 *   - the full ride list (for totals / calendar / personal-bests / per-ride bars)
 *   - the samples from rides started in the last 30 days (for the speed histogram).
 *
 * Sample loading is gated to a 30-day window because the user can accumulate
 * tens of thousands of samples otherwise — loading them all just to fill a
 * histogram would be wasteful.
 */
class StatsViewModel(app: Application) : AndroidViewModel(app) {

    private val store: RideStore = RideStore(GixxerDatabase.get(app).rideDao())

    /** All persisted rides, newest-first. */
    val rides: StateFlow<List<RideEntity>> = store.observeRides()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Samples concatenated across every ride that *started* within the last
     * 30 days. Re-loaded whenever [rides] emits.
     */
    val recentSamples: StateFlow<List<RideSampleEntity>> = rides
        .map { rs ->
            val cutoff = System.currentTimeMillis() - 30L * 86_400_000L
            rs.filter { it.startedAtMillis >= cutoff }
                .flatMap { store.getSamples(it.id) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Samples for the last [count] rides (newest-first → flattened). Used for
     * per-ride summaries (fuel economy line, avg/max bar). Lighter than
     * [recentSamples] when the user has many rides per day.
     */
    val lastNSamples: StateFlow<List<RideSampleEntity>> = rides
        .map { rs ->
            rs.take(LAST_N).flatMap { store.getSamples(it.id) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    companion object {
        const val LAST_N: Int = 10
    }
}
