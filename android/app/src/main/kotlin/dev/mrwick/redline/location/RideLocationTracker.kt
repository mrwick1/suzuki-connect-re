package dev.mrwick.redline.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One GPS reading sampled from [FusedLocationProviderClient].
 *
 * @property tMillis Wall-clock millis from [android.location.Location.getTime].
 * @property lat WGS-84 latitude in degrees.
 * @property lng WGS-84 longitude in degrees.
 * @property altitudeM Metres above WGS-84 ellipsoid, or null if unavailable.
 * @property accuracyM Horizontal 1-sigma accuracy in metres, or null if unavailable.
 */
@Immutable
data class LocationSample(
    val tMillis: Long,
    val lat: Double,
    val lng: Double,
    val altitudeM: Double?,
    val accuracyM: Float?,
    /** Ground speed in m/s if the fix carried one (used by crash detection), else null. */
    val speedMps: Float? = null,
)

/**
 * Wraps [FusedLocationProviderClient]. Emits the latest [LocationSample] while
 * [start]ed, into [samples]. Designed for ride-track recording: caller
 * (typically [dev.mrwick.redline.telemetry.RideLogger]) gates start/stop
 * on ride lifecycle and pipes [samples] into
 * [dev.mrwick.redline.data.RideStore.appendLocation].
 *
 * Permission contract: [start] is a no-op when [Manifest.permission.ACCESS_FINE_LOCATION]
 * is not granted, so callers don't need to pre-check. Re-call [start] after the
 * user grants the permission to pick up tracking.
 *
 * Distance-based sampling at SMALLEST_DISPLACEMENT_M (100 m) with
 * `PRIORITY_BALANCED_POWER_ACCURACY`: a fix roughly every 100 m of travel,
 * and none while stopped (saves battery, avoids GPS-drift clusters at lights).
 * Raise to `PRIORITY_HIGH_ACCURACY` if tracks look jagged.
 */
@SuppressLint("MissingPermission") // start() guards permission check itself
class RideLocationTracker(private val context: Context) {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _samples = MutableStateFlow<LocationSample?>(null)
    /** Latest GPS reading, or null until the first fix arrives. */
    val samples: StateFlow<LocationSample?> = _samples.asStateFlow()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            _samples.value = LocationSample(
                tMillis = loc.time,
                lat = loc.latitude,
                lng = loc.longitude,
                altitudeM = if (loc.hasAltitude()) loc.altitude else null,
                accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
                speedMps = if (loc.hasSpeed()) loc.speed else null,
            )
        }
    }

    /**
     * Begin GPS updates. No-op (returns false) if ACCESS_FINE_LOCATION is not
     * granted; otherwise requests location updates and returns true.
     *
     * Safe to call repeatedly: Fused will collapse duplicate requests.
     */
    fun start(): Boolean {
        if (!hasFineLocation()) return false
        val req = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            INTERVAL_MS,
        )
            .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
            .setMinUpdateDistanceMeters(SMALLEST_DISPLACEMENT_M)
            .build()
        client.requestLocationUpdates(req, callback, Looper.getMainLooper())
        return true
    }

    /** Stop GPS updates. Safe to call when not started. */
    fun stop() {
        client.removeLocationUpdates(callback)
    }

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        /**
         * Distance-based sampling: emit a fix only after ~100 m of travel. At
         * city/motorcycle speeds this yields a point every few seconds while
         * moving and NONE while stopped. This is the real driver of cadence.
         */
        const val SMALLEST_DISPLACEMENT_M = 100f
        /** Desired time cadence; the distance gate above dominates in practice. */
        const val INTERVAL_MS = 3_000L
        /** Hard floor between fixes. */
        const val MIN_INTERVAL_MS = 2_000L
    }
}
