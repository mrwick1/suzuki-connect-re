package dev.mrwick.gixxerbridge.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.gixxerbridge.analytics.FuelEstimate
import dev.mrwick.gixxerbridge.analytics.FuelTankEstimator
import dev.mrwick.gixxerbridge.analytics.MileageAnalytics
import dev.mrwick.gixxerbridge.analytics.RefuelBucket
import dev.mrwick.gixxerbridge.analytics.RefuelPredictor
import dev.mrwick.gixxerbridge.analytics.RideAnalytics
import dev.mrwick.gixxerbridge.analytics.RideStreak
import dev.mrwick.gixxerbridge.analytics.ServiceEta
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
import kotlinx.coroutines.launch

/**
 * Refuel-timing + fill-before-service co-prompt for the Home screen.
 *
 * [refuelBucketLabel] is a coarse human bucket ("~2-3 days") or null when no
 * honest prediction is possible (no range or no recent rides). [bundleService]
 * is true when a km-gated service is due within the tank's range / already
 * overdue — the high-value "do both this trip" nudge. [serviceLabel] names that
 * item (e.g. "Periodic service (engine oil)") for the co-prompt copy.
 */
data class RefuelPromptUi(
    val refuelBucketLabel: String?,
    val bundleService: Boolean,
    val serviceLabel: String?,
)

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
     * the bike's live economy then a fixed default. The current odometer is taken
     * from live telemetry, else the persisted last-telemetry snapshot, else the
     * last-known odometer from ride history — so the estimate shows immediately
     * after a fill even when the bike has never connected this session. Null when
     * no estimate is possible (no fills and no fuel-bar reading).
     */
    val fuelEstimate: StateFlow<FuelEstimate?> =
        combine(
            fuelStore.observe(),
            TelemetryRepository.latest,
            settings.lastTelemetry,
            settings.fuelCapacityL,
            rideStore.observeRides(),
        ) { fills, latest, lastTelem, capacity, rides ->
            val rideOdo = rides.maxOfOrNull { it.endOdoKm ?: it.startOdoKm }
            FuelTankEstimator.estimate(
                fills = fills,
                currentOdometerKm = latest?.odometerKm ?: lastTelem?.odometerKm ?: rideOdo,
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

    /**
     * Refuel timing (coarse bucket) + fill-before-service co-prompt. Combines
     * the existing [fuelEstimate] range with the rider's recent daily-km pace
     * (rolling 30-day km ÷ 30) and the worst km-gated service item's remaining km.
     *
     * The refuel bucket is intentionally coarse (6-bar quantization + anecdotal
     * km/L fallback); the bundle decision rides on the exact odometer-gated
     * service km-remaining. Null when both pieces carry no useful information
     * (no recent rides and no service bundle), OR when the prompt was snoozed at
     * the latest fill odometer (i.e. no new fill has been logged since the snooze).
     *
     * Snooze logic: a new fuel fill re-arms the prompt automatically because the
     * latest fill's odometer will differ from [Settings.refuelPromptSnoozedAtFillOdo].
     */
    val refuelPrompt: StateFlow<RefuelPromptUi?> =
        combine(
            fuelEstimate,
            fuelStore.observe(),
            rideStore.observeRides(),
            settings.serviceSchedule,
            TelemetryRepository.latest,
        ) { estimate, fills, rides, schedule, latest ->
            val rangeKm = estimate?.rangeKm
            // Rolling 30-day pace. totalsFor.km is Int, window is 30 days.
            val kmPerDay30 = RideAnalytics.totalsFor(rides, days = ServiceEta.DEFAULT_PACE_WINDOW_DAYS).km / 30.0
            // dailyKmPace is Double? — pass null when no recent rides so the
            // predictor correctly returns UNKNOWN rather than dividing by zero.
            val pace: Double? = if (kmPerDay30 > 0.0) kmPerDay30 else null

            val odo = latest?.odometerKm
            val worst = ServiceSchedule.mostOverdue(schedule.values, odo).worst
            val prediction = RefuelPredictor.predict(rangeKm, pace, worst?.kmRemaining)

            // Nothing useful to show: UNKNOWN bucket AND no bundle co-prompt → null.
            if (prediction.bucket == RefuelBucket.UNKNOWN && !prediction.fillBeforeService) {
                return@combine null
            }

            Pair(
                RefuelPromptUi(
                    refuelBucketLabel = if (prediction.bucket == RefuelBucket.UNKNOWN) null
                        else prediction.bucket.toLabel(),
                    bundleService = prediction.fillBeforeService,
                    serviceLabel = worst?.state?.item?.label,
                ),
                fills.firstOrNull()?.odometerKm, // latest fill's odo for snooze gate
            )
        }.combine(settings.refuelPromptSnoozedAtFillOdo) { pair, snoozedAtOdo ->
            val (prompt, latestFillOdo) = pair ?: return@combine null
            // Snooze gate: suppress while the latest fill's odometer matches the
            // snoozed value. A new fill (different odometer) re-arms automatically.
            if (isRefuelPromptSnoozed(snoozedAtOdo, latestFillOdo)) null else prompt
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Snooze the refuel co-prompt until the next fuel fill is logged. Persists
     * the current latest fill's odometer to DataStore; the prompt will hide until
     * a new fill (with a different odometer) is recorded.
     *
     * Safe to call from the UI thread — launches on [viewModelScope] internally.
     */
    fun snoozeRefuelPrompt() {
        viewModelScope.launch {
            // Snapshot the latest fill's odometer at snooze time.
            val latestFillOdo = fuelStore.all().firstOrNull()?.odometerKm
            settings.setRefuelPromptSnoozedAtFillOdo(latestFillOdo)
        }
    }
}

/**
 * Pure snooze-visibility predicate: returns true when the refuel co-prompt
 * should be hidden because the rider already snoozed it at [snoozedAtFillOdo]
 * and no new fill has been logged since (i.e. the latest fill's odometer has
 * not changed).
 *
 * Extracted as a top-level function so it can be unit-tested without Android
 * framework dependencies (see [dev.mrwick.gixxerbridge.ui.home.RefuelPromptSnoozeTest]).
 *
 * @param snoozedAtFillOdo  the odometer stored in Settings at snooze time; null = never snoozed.
 * @param latestFillOdo     the most-recent fuel fill's odometer; null = no fills logged yet.
 */
internal fun isRefuelPromptSnoozed(snoozedAtFillOdo: Int?, latestFillOdo: Int?): Boolean {
    // If never snoozed, never hidden.
    if (snoozedAtFillOdo == null) return false
    // If there are no fills, there's nothing to snooze against; show the prompt.
    if (latestFillOdo == null) return false
    // Snoozed at the same fill → still suppressed.
    return snoozedAtFillOdo == latestFillOdo
}

/** Human-readable coarse label for a [RefuelBucket]. Never a precise day count. */
private fun RefuelBucket.toLabel(): String = when (this) {
    RefuelBucket.TODAY -> "today"
    RefuelBucket.ONE_DAY -> "~1 day"
    RefuelBucket.TWO_THREE_DAYS -> "~2-3 days"
    RefuelBucket.THIS_WEEK -> "this week"
    RefuelBucket.OVER_A_WEEK -> "over a week"
    RefuelBucket.UNKNOWN -> "" // guarded upstream
}
