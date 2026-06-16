package dev.mrwick.gixxerbridge.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.gixxerbridge.analytics.CostAnalytics
import dev.mrwick.gixxerbridge.analytics.CostStats
import dev.mrwick.gixxerbridge.analytics.MonthSpend
import dev.mrwick.gixxerbridge.analytics.RunningCost
import dev.mrwick.gixxerbridge.analytics.RunningCostAnalytics
import dev.mrwick.gixxerbridge.data.FuelFillEntity
import dev.mrwick.gixxerbridge.data.FuelStore
import dev.mrwick.gixxerbridge.data.GixxerDatabase
import dev.mrwick.gixxerbridge.data.RideEntity
import dev.mrwick.gixxerbridge.data.RideSampleEntity
import dev.mrwick.gixxerbridge.data.RideStore
import dev.mrwick.gixxerbridge.data.ServiceLogEntity
import dev.mrwick.gixxerbridge.data.ServiceLogStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Backs [StatsScreen]. Exposes:
 *   - the full ride list (for totals / calendar / personal-bests / per-ride bars)
 *   - the samples from rides started in the last 30 days (for the speed histogram)
 *   - fuel fills + service logs for the unified Costs detail view
 *
 * Sample loading is gated to a 30-day window because the user can accumulate
 * tens of thousands of samples otherwise — loading them all just to fill a
 * histogram would be wasteful.
 */
class StatsViewModel(app: Application) : AndroidViewModel(app) {

    private val store: RideStore = RideStore(GixxerDatabase.get(app).rideDao())
    private val fuelStore: FuelStore = FuelStore(GixxerDatabase.get(app).fuelFillDao())
    private val serviceStore: ServiceLogStore =
        ServiceLogStore(GixxerDatabase.get(app).serviceLogDao())

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
                .flatMap { store.getSamplesForView(it.id) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Samples for the last [count] rides (newest-first → flattened). Used for
     * per-ride summaries (fuel economy line, avg/max bar). Lighter than
     * [recentSamples] when the user has many rides per day.
     */
    val lastNSamples: StateFlow<List<RideSampleEntity>> = rides
        .map { rs ->
            rs.take(LAST_N).flatMap { store.getSamplesForView(it.id) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Lifetime best fuel-economy reading across *all* samples, not just the
     * 30-day [recentSamples] window — so an all-time best from months ago still
     * shows in Personal Bests. A single SQL MAX() aggregate, recomputed when the
     * ride list changes.
     */
    val bestFuelEcon: StateFlow<Double?> = rides
        .map { store.maxFuelEcon() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // --- Cost flows ---------------------------------------------------------

    /** All fuel fills, newest-first. */
    val fills: StateFlow<List<FuelFillEntity>> = fuelStore.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** All service log entries, newest-first. */
    val services: StateFlow<List<ServiceLogEntity>> = serviceStore.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Per-tank ₹/km stats from [CostAnalytics]. Null when no priced fill-to-fill
     * interval exists (< 2 priced fills, or no fills with positive rupees).
     */
    val costStats: StateFlow<CostStats?> = fills
        .map { CostAnalytics.stats(it, count = COST_ROLLING_COUNT) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Blended fuel + service ₹/km from [RunningCostAnalytics]. Uses the ride-history
     * odometer span as a fallback distance when fewer than 2 fills are available.
     * Null when no distance or rupee data exists.
     */
    val runningCost: StateFlow<RunningCost?> = combine(fills, services, rides) { f, s, rs ->
        val fallback = rs.mapNotNull { it.endOdoKm ?: it.startOdoKm.takeIf { k -> k > 0 } }
            .let { odos -> if (odos.size >= 2) odos.max() - odos.min() else null }
        RunningCostAnalytics.cost(f, s, fallbackDistanceKm = fallback)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Monthly fuel + service spend for the last 6 months, oldest-first.
     * Always 6 entries (buckets with zero spend are included).
     */
    val monthlySpend: StateFlow<List<MonthSpend>> = combine(fills, services) { f, s ->
        RunningCostAnalytics.monthlySpend(f, s, months = MONTHLY_SPEND_MONTHS)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    companion object {
        const val LAST_N: Int = 10
        const val COST_ROLLING_COUNT: Int = 5
        const val MONTHLY_SPEND_MONTHS: Int = 6
    }
}
