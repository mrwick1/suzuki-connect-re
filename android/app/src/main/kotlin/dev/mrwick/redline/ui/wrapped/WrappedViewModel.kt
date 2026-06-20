package dev.mrwick.redline.ui.wrapped

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.redline.analytics.MileageAnalytics
import dev.mrwick.redline.analytics.WrappedAnalytics
import dev.mrwick.redline.analytics.WrappedResult
import dev.mrwick.redline.analytics.WrappedWindow
import dev.mrwick.redline.data.FuelStore
import dev.mrwick.redline.data.GixxerDatabase
import dev.mrwick.redline.data.RideStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

/**
 * Preset time windows for the Wrapped screen.
 *
 * Implemented here rather than in [WrappedWindow] because preset-to-window mapping
 * is a UI-layer concern (the analytics layer deliberately stays pure). The mapping
 * calls [WrappedWindow.ofCalendarYear] or [WrappedWindow.ofRollingDays] from the
 * existing branch analytics, or constructs a [WrappedWindow] directly for "This
 * month" and "All time".
 */
enum class WrappedPreset(val label: String) {
    THIS_YEAR("This year"),
    LAST_12_MONTHS("Last 12 months"),
    THIS_MONTH("This month"),
    ALL_TIME("All time"),
}

/** Map a [WrappedPreset] to a [WrappedWindow] using today's local date. */
fun WrappedPreset.toWindow(today: LocalDate = LocalDate.now()): WrappedWindow = when (this) {
    WrappedPreset.THIS_YEAR -> WrappedWindow.ofCalendarYear(today.year)
    WrappedPreset.LAST_12_MONTHS -> WrappedWindow.ofRollingDays(365, today)
    WrappedPreset.THIS_MONTH -> WrappedWindow(
        startInclusive = today.withDayOfMonth(1),
        endInclusive = today,
    )
    WrappedPreset.ALL_TIME -> WrappedWindow(
        startInclusive = LocalDate.of(2020, 1, 1), // earliest plausible ride date
        endInclusive = today,
    )
}

/**
 * Backs [WrappedScreen].
 *
 * Exposes:
 * - [preset]: the currently-selected [WrappedPreset] (default: [WrappedPreset.THIS_YEAR]).
 * - [recap]: the computed [WrappedResult] for the selected window, or null when the
 *   window contains no ended rides (empty state).
 *
 * Modelled on [StatsViewModel]: AndroidViewModel, GixxerDatabase.get(app) stores,
 * stateIn(Eagerly). The selected preset is transient UI state held in a
 * [MutableStateFlow] — it is never persisted to Room (no schema change) or DataStore
 * (deferred follow-up).
 *
 * Samples are loaded per in-window ride for the bike-economy fallback in
 * [MileageAnalytics.averageKmPerL]. In practice the fill-measured average is the
 * primary km/L source when ≥ 2 fills exist; samples are only needed as a fallback
 * when fills are sparse.
 */
class WrappedViewModel(app: Application) : AndroidViewModel(app) {

    private val db = GixxerDatabase.get(app)
    private val rideStore = RideStore(db.rideDao())
    private val fuelStore = FuelStore(db.fuelFillDao())

    /** Currently-selected window preset. Default: This year. */
    val preset: MutableStateFlow<WrappedPreset> = MutableStateFlow(WrappedPreset.THIS_YEAR)

    /**
     * Computed recap for [preset], or null when the window contains no ended rides.
     *
     * Recomputes whenever rides, fills, or the selected preset change. Emits null
     * immediately (SharingStarted.Eagerly initial value) so the screen can show a
     * loading / empty state before data lands.
     */
    val recap: StateFlow<WrappedResult?> = combine(
        rideStore.observeRides(),
        fuelStore.observe(),
        preset,
    ) { rides, fills, selectedPreset ->
        val window = selectedPreset.toWindow()
        // Fill-measured avg km/L over fills up to and including window end.
        val fillsForAvg = fills.filter { f ->
            val d = java.time.Instant.ofEpochMilli(f.tMillis)
                .atZone(window.zone).toLocalDate()
            !d.isAfter(window.endInclusive)
        }
        val avgKmPerL = MileageAnalytics.averageKmPerL(fillsForAvg)
        WrappedAnalytics.compute(
            rides = rides,
            fills = fills,
            avgKmPerL = avgKmPerL,
            window = window,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Switch the active window preset. */
    fun setPreset(p: WrappedPreset) {
        preset.value = p
    }
}
