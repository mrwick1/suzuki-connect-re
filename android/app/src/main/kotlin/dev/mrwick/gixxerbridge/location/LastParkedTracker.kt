package dev.mrwick.gixxerbridge.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
    val lastParked: Flow<LastParked?> = ds.data.map { prefs ->
        val lat = prefs[KEY_LAT] ?: return@map null
        val lng = prefs[KEY_LNG] ?: return@map null
        val t = prefs[KEY_T] ?: return@map null
        if (lat.isNaN() || lng.isNaN()) null else LastParked(lat, lng, t)
    }

    /**
     * Snapshot the phone's last-known location and persist it as the parked spot.
     * Called from BikeBridgeService when the connection moves to Disconnected.
     *
     * Needs ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION; no-op if neither granted.
     */
    @SuppressLint("MissingPermission")
    suspend fun snapshotOnDisconnect() {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val loc = try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
        } catch (_: SecurityException) {
            null
        } ?: return

        ds.edit {
            it[KEY_LAT] = loc.latitude
            it[KEY_LNG] = loc.longitude
            it[KEY_T] = System.currentTimeMillis()
        }
    }

    /** Clear the saved location (e.g. user taps "I found my bike"). */
    suspend fun clear() {
        ds.edit {
            it.remove(KEY_LAT); it.remove(KEY_LNG); it.remove(KEY_T)
        }
    }

    companion object {
        private val KEY_LAT = doublePreferencesKey("last_parked_lat")
        private val KEY_LNG = doublePreferencesKey("last_parked_lng")
        private val KEY_T = longPreferencesKey("last_parked_t_millis")
    }
}

/** A previously captured parked-location snapshot. */
data class LastParked(val lat: Double, val lng: Double, val tMillis: Long) {
    /** Google Maps deep-link to this point — share or open in any maps app. */
    fun mapsUrl(): String = "https://maps.google.com/?q=$lat,$lng"
}

private val Context.parkingDataStore: DataStore<Preferences> by preferencesDataStore(name = "last_parked")
