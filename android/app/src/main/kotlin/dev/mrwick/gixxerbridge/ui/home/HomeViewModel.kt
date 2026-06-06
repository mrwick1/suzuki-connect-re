package dev.mrwick.gixxerbridge.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.gixxerbridge.analytics.FuelEstimate
import dev.mrwick.gixxerbridge.analytics.FuelTankEstimator
import dev.mrwick.gixxerbridge.analytics.MileageAnalytics
import dev.mrwick.gixxerbridge.analytics.RideAnalytics
import dev.mrwick.gixxerbridge.analytics.RideStreak
import dev.mrwick.gixxerbridge.analytics.ServiceSchedule
import dev.mrwick.gixxerbridge.app.AppGraph
import dev.mrwick.gixxerbridge.data.FuelStore
import dev.mrwick.gixxerbridge.data.GixxerDatabase
import dev.mrwick.gixxerbridge.ble.ConnectionState
import dev.mrwick.gixxerbridge.location.LastParked
import dev.mrwick.gixxerbridge.protocol.TelemetryFrame
import dev.mrwick.gixxerbridge.telemetry.TelemetryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Computed service-schedule summary for HomeScreen's TodayHeroCard.
 *
 * [label] — human name of the most-urgent item (e.g. "Periodic service (engine oil)").
 * [dueInText] — e.g. "1200 km" or "Overdue 320 km". Both gates if available.
 * [overdue] — true when the worst gate has a negative remaining value.
 */
data class NextServiceSummary(
    val label: String,
    val dueInText: String,
    val overdue: Boolean,
)

/**
 * ViewModel for [HomeScreen]. Exposes five flows the screen consumes:
 *
 *   1. [connectionState] — live BLE state from [AppGraph].
 *   2. [riderName] — display name from Settings (defaults to "Rider").
 *   3. [todayDistanceKm] — rolling-24h total km from the ride history.
 *   4. [rideStreakDays] — current consecutive-day streak (0 = no streak; null on empty history).
 *   5. [nextServiceDue] — worst-overdue service item computed from schedule + odometer.
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = AppGraph.settings(app)
    private val rideStore = AppGraph.rideStore(app)
    private val fuelStore = FuelStore(GixxerDatabase.get(app).fuelFillDao())

    /** Live BLE connection state; always present via AppGraph (Idle when service not started). */
    val connectionState: Flow<ConnectionState> = AppGraph.connectionState

    /** Most-recent a537 telemetry (speed/odo/trip/fuel). Null when never received this session. */
    val latestTelemetry: StateFlow<TelemetryFrame?> =
        TelemetryRepository.latest
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Estimated tank state (litres left, %, range) from the fuel-fill ledger +
     * current odometer. Uses the measured fill-to-fill avg km/L, falling back to
     * the bike's live economy then a fixed default. Works offline via the
     * persisted last-telemetry snapshot. Null when no estimate is possible
     * (no fills and no fuel-bar reading).
     */
    val fuelEstimate: StateFlow<FuelEstimate?> =
        combine(
            fuelStore.observe(),
            TelemetryRepository.latest,
            settings.lastTelemetry,
            settings.fuelCapacityL,
        ) { fills, latest, lastTelem, capacity ->
            FuelTankEstimator.estimate(
                fills = fills,
                currentOdometerKm = latest?.odometerKm ?: lastTelem?.odometerKm,
                avgKmPerL = MileageAnalytics.averageKmPerL(fills),
                bikeLiveKmPerL = latest?.fuelEconKmlV2 ?: lastTelem?.kmPerL,
                bikeFuelBars = latest?.fuelBars ?: lastTelem?.fuelBars,
                capacityL = capacity,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Last-parked snapshot (captured on bike disconnect). Null until first park cycle. */
    val lastParked: StateFlow<LastParked?> =
        AppGraph.lastParkedTracker(app).lastParked
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Rider display name; falls back to "Rider" on first launch. */
    val riderName: StateFlow<String> =
        settings.riderName
            .stateIn(viewModelScope, SharingStarted.Eagerly, "Rider")

    /**
     * Total distance ridden in the last 24 h (rolling window, not calendar-day aligned).
     * Null only when the ride list has never been loaded — emits Double once the first
     * DB read completes.
     */
    val todayDistanceKm: StateFlow<Double?> =
        rideStore.observeRides()
            .map { rides ->
                RideAnalytics.totalsFor(rides, days = 1L).km.toDouble()
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Current consecutive-day ride streak in days, or null when no rides exist yet.
     * Zero means the rider hasn't ridden today or yesterday.
     */
    val rideStreakDays: StateFlow<Int?> =
        rideStore.observeRides()
            .map { rides ->
                if (rides.isEmpty()) null else RideStreak.compute(rides).current
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Most-urgent service item. Null when no service item has a recorded baseline
     * (fresh install before the rider logs the first service).
     *
     * Recomputed whenever the service schedule or the latest telemetry frame changes.
     */
    val nextServiceDue: StateFlow<NextServiceSummary?> =
        combine(
            settings.serviceSchedule,
            TelemetryRepository.latest,
        ) { schedule, latest ->
            val odo = latest?.odometerKm
            val health = ServiceSchedule.mostOverdue(
                items = schedule.values,
                currentOdoKm = odo,
            )
            val worst = health.worst ?: return@combine null
            val kmR = worst.kmRemaining
            val daysR = worst.daysRemaining
            val overdue = (kmR != null && kmR < 0) || (daysR != null && daysR < 0)
            val parts = mutableListOf<String>()
            kmR?.let {
                parts += if (it >= 0) "$it km" else "Overdue ${-it} km"
            }
            daysR?.let {
                if (parts.isEmpty()) parts += if (it >= 0) "$it days" else "Overdue ${-it} days"
            }
            NextServiceSummary(
                label = worst.state.item.label,
                dueInText = parts.firstOrNull() ?: "—",
                overdue = overdue,
            )
        }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
