package dev.mrwick.redline.ui.routes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.redline.analytics.MileageAnalytics
import dev.mrwick.redline.analytics.RouteCluster
import dev.mrwick.redline.analytics.RouteClustering
import dev.mrwick.redline.analytics.RouteLeaderboard
import dev.mrwick.redline.analytics.RouteStats
import dev.mrwick.redline.data.FuelStore
import dev.mrwick.redline.data.GixxerDatabase
import dev.mrwick.redline.data.RideEntity
import dev.mrwick.redline.data.RideLocationEntity
import dev.mrwick.redline.data.RideStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs [RouteRepeatScreen].
 *
 * On creation it kicks off a one-shot coroutine that:
 *   1. Fetches all rides via [RideStore.getAllRides] (snapshot, no flow).
 *   2. Fetches all GPS location tracks via [RideStore.getAllLocationsPerRide].
 *   3. Runs [RouteClustering.cluster] with the default parameters.
 *   4. Runs [RouteLeaderboard.leaderboard] using the fill-measured km/L if available.
 *   5. Publishes the ranked [RouteStats] list to [routes].
 *
 * Reload is not wired (routes don't change mid-session). A future wave can add
 * a pull-to-refresh trigger that calls the same load sequence.
 */
class RouteRepeatViewModel(app: Application) : AndroidViewModel(app) {

    private val store: RideStore = RideStore(GixxerDatabase.get(app).rideDao())
    private val fuelStore: FuelStore = FuelStore(GixxerDatabase.get(app).fuelFillDao())

    /** Ordered leaderboard rows (most-ridden first), empty until the first load completes. */
    private val _routes = MutableStateFlow<List<RouteStats>>(emptyList())
    val routes: StateFlow<List<RouteStats>> = _routes.asStateFlow()

    /** True while the clustering / leaderboard computation is running. */
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /**
     * Snapshot of all rides at the time the leaderboard was computed.
     * Used by the screen to resolve cluster member ids to ride entities.
     */
    private val _rides = MutableStateFlow<List<RideEntity>>(emptyList())
    val rides: StateFlow<List<RideEntity>> = _rides.asStateFlow()

    /**
     * Maps each cluster id → the list of ride ids in that cluster (oldest-first,
     * matching [RouteClustering] assignment order). The screen uses this to pick a
     * representative ride id for tap-through navigation.
     *
     * Empty map until load completes.
     */
    private val _clusterRideIds = MutableStateFlow<Map<Int, List<Long>>>(emptyMap())
    val clusterRideIds: StateFlow<Map<Int, List<Long>>> = _clusterRideIds.asStateFlow()

    init {
        viewModelScope.launch {
            _loading.value = true
            try {
                // Use snapshot reads — no need to observe live changes for a one-shot computation.
                val allRides: List<RideEntity> = store.getAllRides()
                _rides.value = allRides

                // Build a location-lookup map once, then pass it as a lambda so
                // RouteClustering receives the interface it already expects.
                val locationMap: Map<Long, List<RideLocationEntity>> =
                    store.getAllLocationsPerRide(allRides)
                val locationsForRide: (Long) -> List<RideLocationEntity> =
                    { rideId -> locationMap[rideId] ?: emptyList() }

                val clusters: List<RouteCluster> = RouteClustering.cluster(
                    rides = allRides,
                    locationsForRide = locationsForRide,
                )

                // Expose the per-cluster ride id map for the screen to resolve tap targets.
                _clusterRideIds.value = clusters.associate { it.clusterId to it.rideIds }

                // Use fill-measured km/L for fuel estimates if available; otherwise
                // pass null so RouteLeaderboard omits fuel columns.
                val fillLog = fuelStore.all()
                val kmPerL = MileageAnalytics.averageKmPerL(fillLog)

                _routes.value = RouteLeaderboard.leaderboard(
                    clusters = clusters,
                    rides = allRides,
                    kmPerL = kmPerL,
                )
            } finally {
                _loading.value = false
            }
        }
    }
}
