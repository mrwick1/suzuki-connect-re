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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max

/**
 * Backs the Trips list + detail screens by exposing past rides from the Room
 * store and lazily loading samples for the currently-selected ride.
 */
class TripsViewModel(context: Context) : ViewModel() {

    private val store: RideStore = RideStore(GixxerDatabase.get(context).rideDao())

    /** All persisted rides, newest-first; reflects inserts and deletes live. */
    val rides: StateFlow<List<RideEntity>> = store.observeRides()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Summary stats for the current calendar month: ride count + total distance.
     * Derived from [rides] so it updates live as rides are added/deleted.
     */
    val monthSummary: StateFlow<MonthSummary> = store.observeRides()
        .map { allRides ->
            val zone = ZoneId.systemDefault()
            val now = Instant.now().atZone(zone)
            // Local midnight on the 1st of the current month.
            val monthStart = now
                .withDayOfMonth(1)
                .toLocalDate()
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
            val thisMonth = allRides.filter { it.startedAtMillis >= monthStart }
            val totalKm = thisMonth.sumOf { r ->
                max(0, (r.endOdoKm ?: r.startOdoKm) - r.startOdoKm)
            }
            MonthSummary(rideCount = thisMonth.size, totalKm = totalKm)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MonthSummary(0, 0))

    private val _selectedSamples = MutableStateFlow<List<RideSampleEntity>>(emptyList())

    /** Samples for the most recently requested ride via [loadSamples]; oldest-first. */
    val selectedSamples: StateFlow<List<RideSampleEntity>> = _selectedSamples.asStateFlow()

    /** Load all samples for [rideId] into [selectedSamples]; safe to re-call. */
    fun loadSamples(rideId: Long) {
        viewModelScope.launch {
            _selectedSamples.value = store.getSamples(rideId)
        }
    }

    /**
     * Fetch up to [limit] evenly-distributed speed samples for a ride sparkline.
     * Fetched lazily per-row in the Trips list; safe to call from a LaunchedEffect.
     */
    suspend fun samplesForSparkline(rideId: Long, limit: Int = 60): List<RideSampleEntity> =
        store.getSamplesLimited(rideId, limit)

    /** Delete a ride and its samples (cascade) from the store. */
    fun delete(rideId: Long) {
        viewModelScope.launch { store.deleteRide(rideId) }
    }

    /**
     * Rename a ride. Pass null/blank to clear the override and fall back to
     * the date string in the row title.
     */
    fun rename(rideId: Long, name: String?) {
        viewModelScope.launch { store.renameRide(rideId, name?.takeIf { it.isNotBlank() }) }
    }

    /**
     * Fetch GPS locations recorded during [rideId], oldest-first.
     * Called from TripDetailScreen's Share-GPX flow.
     */
    suspend fun locationsFor(rideId: Long): List<RideLocationEntity> =
        store.getLocations(rideId)

    /** Fetch telemetry samples for [rideId], oldest-first. Used by Share-CSV. */
    suspend fun samplesFor(rideId: Long): List<RideSampleEntity> =
        store.getSamples(rideId)

    /** Direct access to the underlying ride entity for export (snapshot fetch). */
    suspend fun rideFor(rideId: Long): RideEntity? =
        rides.value.firstOrNull { it.id == rideId }
}

/** This-month aggregation shown in the Trips screen header. */
data class MonthSummary(val rideCount: Int, val totalKm: Int)
