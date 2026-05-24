package dev.mrwick.gixxerbridge.ui.trips

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.gixxerbridge.data.GixxerDatabase
import dev.mrwick.gixxerbridge.data.RideEntity
import dev.mrwick.gixxerbridge.data.RideLocationEntity
import dev.mrwick.gixxerbridge.data.RideSampleEntity
import dev.mrwick.gixxerbridge.data.RideStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Trips list + detail screens by exposing past rides from the Room
 * store and lazily loading samples for the currently-selected ride.
 */
class TripsViewModel(context: Context) : ViewModel() {

    private val store: RideStore = RideStore(GixxerDatabase.get(context).rideDao())

    /** All persisted rides, newest-first; reflects inserts and deletes live. */
    val rides: StateFlow<List<RideEntity>> = store.observeRides()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selectedSamples = MutableStateFlow<List<RideSampleEntity>>(emptyList())

    /** Samples for the most recently requested ride via [loadSamples]; oldest-first. */
    val selectedSamples: StateFlow<List<RideSampleEntity>> = _selectedSamples.asStateFlow()

    /** Load all samples for [rideId] into [selectedSamples]; safe to re-call. */
    fun loadSamples(rideId: Long) {
        viewModelScope.launch {
            _selectedSamples.value = store.getSamples(rideId)
        }
    }

    /** Delete a ride and its samples (cascade) from the store. */
    fun delete(rideId: Long) {
        viewModelScope.launch { store.deleteRide(rideId) }
    }

    /**
     * Fetch GPS locations recorded during [rideId], oldest-first.
     * Called from TripDetailScreen's Share-GPX flow.
     */
    suspend fun locationsFor(rideId: Long): List<RideLocationEntity> =
        store.getLocations(rideId)

    /** Direct access to the underlying ride entity for export (snapshot fetch). */
    suspend fun rideFor(rideId: Long): RideEntity? =
        rides.value.firstOrNull { it.id == rideId }
}
