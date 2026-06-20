package dev.mrwick.redline.ui.trips

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.redline.app.AppGraph
import dev.mrwick.redline.data.GixxerDatabase
import dev.mrwick.redline.data.RideEntity
import dev.mrwick.redline.data.RideLocationEntity
import dev.mrwick.redline.data.RideMeta
import dev.mrwick.redline.data.RideMetaStore
import dev.mrwick.redline.data.RideSampleEntity
import dev.mrwick.redline.data.RideStore
import dev.mrwick.redline.analytics.JourneyDetector
import dev.mrwick.redline.analytics.JourneySuggestion
import dev.mrwick.redline.analytics.MileageAnalytics
import dev.mrwick.redline.data.FuelStore
import dev.mrwick.redline.data.JourneyDismissStore
import dev.mrwick.redline.data.MergeResult
import dev.mrwick.redline.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max

/**
 * Backs the Trips list + detail screens by exposing past rides from the Room
 * store and lazily loading samples for the currently-selected ride.
 *
 * Ride metadata (favourite flag, tags, notes) is joined from [RideMetaStore]
 * which lives outside Room so it survives destructive migrations. The join is
 * keyed by [RideEntity.startedAtMillis].
 */
class TripsViewModel(context: Context) : ViewModel() {

    private val store: RideStore = RideStore(GixxerDatabase.get(context).rideDao())
    private val fuelStore: FuelStore = FuelStore(GixxerDatabase.get(context).fuelFillDao())
    private val metaStore: RideMetaStore = AppGraph.rideMetaStore(context)
    private val dismissStore = JourneyDismissStore(context)
    private val settings = Settings(context)

    // ── Journey suggestion ────────────────────────────────────────────────────

    /**
     * The single most-recent non-dismissed journey suggestion, or null. Combines
     * the live top-level rides, the dismissed-key set, and the tunable config.
     */
    val journeySuggestion: StateFlow<JourneySuggestion?> =
        combine(store.observeRides(), dismissStore.observe(), settings.journeyConfig) { rides, dismissed, cfg ->
            JourneyDetector.detect(rides, cfg)
                .filter { it.startMillis !in dismissed }
                .maxByOrNull { it.startMillis }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Max inter-ride gap (minutes) for which the Trips list draws a "gap hint"
     * connector between two rows of the same run. Mirrors the detector's tunable
     * [JourneyConfig.gapMaxMin] so the connector and the suggestion stay in sync.
     */
    val gapHintMaxMin: StateFlow<Int> = settings.journeyConfig
        .map { it.gapMaxMin }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 120)

    /** Dismiss a suggestion so it won't resurface. */
    fun dismissSuggestion(startMillis: Long) {
        viewModelScope.launch { dismissStore.dismiss(startMillis) }
    }

    /**
     * Merge [rideIds]; [onResult] runs with the outcome so the screen can show a
     * snackbar / clear selection.
     */
    fun merge(rideIds: List<Long>, onResult: (MergeResult) -> Unit) {
        viewModelScope.launch { onResult(store.mergeRides(rideIds)) }
    }

    /** Reverse a merge by parent id. */
    fun split(parentId: Long) {
        viewModelScope.launch { store.splitMerge(parentId) }
    }

    /** Child segments of a merged ride, chronological. */
    suspend fun childrenOf(parentId: Long): List<RideEntity> = store.getChildren(parentId)

    // ── Active filter ─────────────────────────────────────────────────────────

    /**
     * Current filter applied to the rides list.
     *
     * [TripsFilter.All]        — show every ride (default)
     * [TripsFilter.Favourites] — only starred rides
     * [TripsFilter.ByTag]      — only rides tagged with the given label
     */
    private val _filter = MutableStateFlow<TripsFilter>(TripsFilter.All)
    val filter: StateFlow<TripsFilter> = _filter.asStateFlow()

    fun setFilter(f: TripsFilter) { _filter.value = f }

    // ── Meta map: startedAtMillis → RideMeta ─────────────────────────────────

    /** Live meta map from [RideMetaStore]. Used by the detail screen directly. */
    val metaMap: StateFlow<Map<Long, RideMeta>> = metaStore.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // ── Rides joined with meta and filtered ───────────────────────────────────

    /**
     * All rides enriched with their [RideMeta], newest-first, with the active
     * [filter] applied. Recomputes whenever rides, meta, or the filter changes.
     */
    val ridesWithMeta: StateFlow<List<RideWithMeta>> =
        combine(store.observeRides(), metaStore.observe(), _filter) { rides, meta, filter ->
            val joined = rides.map { ride ->
                RideWithMeta(ride, meta[ride.startedAtMillis] ?: RideMeta())
            }
            when (filter) {
                TripsFilter.All -> joined
                TripsFilter.Favourites -> joined.filter { it.meta.favorite }
                is TripsFilter.ByTag -> joined.filter { filter.tag in it.meta.tags }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Backwards-compatible [rides] alias that mirrors [ridesWithMeta] as plain
     * [RideEntity] list. Screens that only need the entity (e.g. TripDetailScreen
     * looking up a ride by id) continue to work unchanged.
     */
    val rides: StateFlow<List<RideEntity>> = ridesWithMeta
        .map { list -> list.map { it.ride } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * All distinct tags across all rides (from unfiltered meta). Used to populate
     * the tag filter chip list and the tag picker in TripDetailScreen.
     */
    val allTags: StateFlow<Set<String>> = metaStore.observe()
        .map { meta -> meta.values.flatMapTo(mutableSetOf()) { it.tags } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /**
     * Rider's fill-measured trailing-average km/L, or null until enough fuel
     * fills exist. When present it's the calibrated source for per-ride fuel
     * burnt; otherwise the UI falls back to the bike's logged economy.
     */
    val fillKmPerL: StateFlow<Double?> = fuelStore.observe()
        .map { MileageAnalytics.averageKmPerL(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Summary stats for the current calendar month: ride count + total distance.
     * Derived from [rides] so it updates live as rides are added/deleted.
     *
     * Note: counts all rides regardless of the active [filter].
     */
    val monthSummary: StateFlow<MonthSummary> = store.observeRides()
        .map { allRides ->
            val zone = ZoneId.systemDefault()
            val now = Instant.now().atZone(zone)
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
            _selectedSamples.value = store.getSamplesForView(rideId)
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
        store.getLocationsForView(rideId)

    /** Fetch telemetry samples for [rideId], oldest-first. Used by Share-CSV. */
    suspend fun samplesFor(rideId: Long): List<RideSampleEntity> =
        store.getSamplesForView(rideId)

    /** Direct access to the underlying ride entity for export (snapshot fetch). */
    suspend fun rideFor(rideId: Long): RideEntity? =
        rides.value.firstOrNull { it.id == rideId }

    // ── Meta write operations (keyed by startedAtMillis) ─────────────────────

    /** Toggle the favourite flag for the ride identified by [startedAtMillis]. */
    fun setFavorite(startedAtMillis: Long, favorite: Boolean) {
        viewModelScope.launch { metaStore.setFavorite(startedAtMillis, favorite) }
    }

    /** Replace the tag set for the ride identified by [startedAtMillis]. */
    fun setTags(startedAtMillis: Long, tags: Set<String>) {
        viewModelScope.launch { metaStore.setTags(startedAtMillis, tags) }
    }

    /** Set or clear the note for the ride identified by [startedAtMillis]. */
    fun setNote(startedAtMillis: Long, note: String) {
        viewModelScope.launch { metaStore.setNote(startedAtMillis, note) }
    }
}

// ── Supporting types ──────────────────────────────────────────────────────────

/** A [RideEntity] paired with its [RideMeta] (defaults applied when absent). */
data class RideWithMeta(val ride: RideEntity, val meta: RideMeta)

/** This-month aggregation shown in the Trips screen header. */
data class MonthSummary(val rideCount: Int, val totalKm: Int)

/** Filter state for the Trips list. */
sealed interface TripsFilter {
    /** Show all rides. */
    data object All : TripsFilter

    /** Show only starred rides. */
    data object Favourites : TripsFilter

    /** Show only rides bearing [tag]. */
    data class ByTag(val tag: String) : TripsFilter
}

/** Preset tag labels shown in the tag picker chip row in TripDetailScreen. */
val PRESET_TAGS: List<String> = listOf(
    "commute",
    "weekend",
    "twisties",
    "highway",
    "city",
    "rain",
    "night",
    "solo",
    "group ride",
)
