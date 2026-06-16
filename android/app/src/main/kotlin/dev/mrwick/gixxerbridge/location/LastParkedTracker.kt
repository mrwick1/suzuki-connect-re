package dev.mrwick.gixxerbridge.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * "Last parked location" — every time the bike disconnects (key off), we snapshot
 * the phone's last-known location. The rider can later open the Home screen and see
 * where the bike was last seen.
 *
 * Solves a real rider pain point: "I parked somewhere in the mall garage and forgot
 * which level." The phone GPS doesn't follow the bike around, but the location
 * *at the moment of disconnect* is a strong proxy for where the bike was parked.
 */
class LastParkedTracker(private val context: Context) {

    private val ds = context.parkingDataStore

    /** Captured at the most recent disconnect; null until the first ride+park cycle. */
    val lastParked: Flow<LastParked?> = ds.data.map { prefs -> readSnapshot(prefs) }

    /**
     * Snapshot where the bike was parked and persist it. Called from
     * BikeBridgeService when the connection moves to Disconnected.
     *
     * The park *time* always advances to now, even when no location is available
     * — a missing fix must not freeze the timestamp at a days-old snapshot
     * (see [ParkSnapshotPolicy]). We first try for a fresh fix, then fall back to
     * the system's last-known location, then to the previous coordinates.
     *
     * Needs ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION; with neither granted
     * we can still advance the park time over the previous coordinates.
     */
    suspend fun snapshotOnDisconnect() {
        val now = System.currentTimeMillis()
        val fix = obtainFix()
        val previous = readSnapshot(ds.data.first())
        val write = ParkSnapshotPolicy.decide(now = now, fix = fix, previous = previous) ?: return
        ds.edit {
            it[KEY_LAT] = write.lat
            it[KEY_LNG] = write.lng
            it[KEY_T] = write.parkedAtMillis
            it[KEY_LOC_T] = write.locAtMillis
        }
    }

    /**
     * Best-effort current location: a fresh fix when the platform supports it
     * (API 30+), otherwise the system's last-known location. Returns null when no
     * location can be obtained or permission is missing.
     */
    @SuppressLint("MissingPermission")
    private suspend fun obtainFix(): LocFix? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val fresh = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) requestCurrent(lm) else null
        val loc = fresh ?: lastKnown(lm) ?: return null
        return LocFix(loc.latitude, loc.longitude, atMillis = loc.time.takeIf { it > 0 } ?: System.currentTimeMillis())
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestCurrent(lm: LocationManager): Location? {
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        return withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val signal = android.os.CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                try {
                    androidx.core.location.LocationManagerCompat.getCurrentLocation(
                        lm, provider, signal, context.mainExecutor,
                    ) { loc -> if (cont.isActive) cont.resume(loc) }
                } catch (_: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager): Location? = try {
        lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
    } catch (_: SecurityException) {
        null
    }

    /** Clear the saved location (e.g. user taps "I found my bike"). */
    suspend fun clear() {
        ds.edit {
            it.remove(KEY_LAT); it.remove(KEY_LNG); it.remove(KEY_T); it.remove(KEY_LOC_T)
        }
    }

    private fun readSnapshot(prefs: Preferences): LastParked? {
        val lat = prefs[KEY_LAT] ?: return null
        val lng = prefs[KEY_LNG] ?: return null
        val t = prefs[KEY_T] ?: return null
        if (lat.isNaN() || lng.isNaN()) return null
        // Pre-existing snapshots have no location time — treat coords as fresh-as-of-park.
        return LastParked(lat, lng, tMillis = t, locTMillis = prefs[KEY_LOC_T] ?: t)
    }

    companion object {
        private const val FRESH_FIX_TIMEOUT_MS = 5_000L
        private val KEY_LAT = doublePreferencesKey("last_parked_lat")
        private val KEY_LNG = doublePreferencesKey("last_parked_lng")
        private val KEY_T = longPreferencesKey("last_parked_t_millis")
        private val KEY_LOC_T = longPreferencesKey("last_parked_loc_t_millis")
    }
}

/** A previously captured parked-location snapshot. */
@Immutable
data class LastParked(
    val lat: Double,
    val lng: Double,
    /** When the bike was parked (key-off). */
    val tMillis: Long,
    /** When the location fix backing [lat]/[lng] was taken; older than [tMillis] means an approximate (reused) spot. */
    val locTMillis: Long,
) {
    /** True when the coordinates are noticeably older than the park time (reused stale fix). */
    val isLocationStale: Boolean get() = tMillis - locTMillis > STALE_LOCATION_THRESHOLD_MS

    /** Google Maps deep-link to this point — share or open in any maps app. */
    fun mapsUrl(): String = "https://maps.google.com/?q=$lat,$lng"

    companion object {
        /** Coords older than this relative to the park time are flagged approximate. */
        const val STALE_LOCATION_THRESHOLD_MS = 5 * 60_000L
    }
}

private val Context.parkingDataStore: DataStore<Preferences> by preferencesDataStore(name = "last_parked")
