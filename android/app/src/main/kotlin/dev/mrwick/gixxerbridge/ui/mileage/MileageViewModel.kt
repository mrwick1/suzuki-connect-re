package dev.mrwick.gixxerbridge.ui.mileage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.gixxerbridge.analytics.MileageAnalytics
import dev.mrwick.gixxerbridge.data.FuelFillEntity
import dev.mrwick.gixxerbridge.data.FuelStore
import dev.mrwick.gixxerbridge.data.GixxerDatabase
import dev.mrwick.gixxerbridge.data.RideStore
import dev.mrwick.gixxerbridge.telemetry.TelemetryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs [MileageScreen]. Exposes the fill log + derived averages and accepts
 * add/delete commands. Pure-fn analytics live in
 * [dev.mrwick.gixxerbridge.analytics.MileageAnalytics] so they're testable
 * without Android.
 */
class MileageViewModel(app: Application) : AndroidViewModel(app) {

    private val store: FuelStore = FuelStore(GixxerDatabase.get(app).fuelFillDao())
    private val rideStore: RideStore = RideStore(GixxerDatabase.get(app).rideDao())

    /** All fills, newest-first. */
    val fills: StateFlow<List<FuelFillEntity>> = store.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Trailing average over the last [TRAILING_COUNT] tanks. Null when there
     * are fewer than 2 valid fills.
     */
    val averageKmPerL: StateFlow<Double?> = fills
        .map { MileageAnalytics.averageKmPerL(it, count = TRAILING_COUNT) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * `fillId -> kmPerL` for every consecutive-pair tank. The first fill in
     * chronological order has no pair and is absent from the map.
     */
    val perTank: StateFlow<Map<Long, Double>> = fills
        .map { MileageAnalytics.perTankKmPerL(it).toMap() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Record one fill. Timestamp is captured at the moment of the call (no UI
     * field — see screen). Litres/rupees > 0; odometer >= 0; note trimmed.
     */
    fun addFill(odometerKm: Int, litres: Double, rupees: Double?, note: String?) {
        viewModelScope.launch {
            store.add(
                tMillis = System.currentTimeMillis(),
                odometerKm = odometerKm,
                litres = litres,
                rupees = rupees,
                note = note?.trim()?.ifBlank { null },
            )
        }
    }

    /**
     * Best odometer to pre-fill the fill form with at the moment "Fill up" is
     * tapped: the live telemetry value if the bike is connected, else the
     * last-known odometer from ride history, else null (rider types it in).
     */
    suspend fun currentOdometer(): Int? =
        TelemetryRepository.latest.value?.odometerKm ?: rideStore.lastKnownOdometer()

    /** Delete one fill by id. */
    fun delete(id: Long) {
        viewModelScope.launch { store.delete(id) }
    }

    companion object {
        /** ASSUMED: 5 tanks is a sensible default window — long enough to smooth out one bad fill, short enough to react to riding-style changes. */
        const val TRAILING_COUNT: Int = 5
    }
}
