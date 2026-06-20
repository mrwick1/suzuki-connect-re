package dev.mrwick.redline.safety

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dev.mrwick.redline.GixxerApp
import dev.mrwick.redline.R
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Sends an SOS SMS to the configured emergency contact. Includes last-known location
 * if available. No-op if no contact configured.
 *
 * ASSUMED: the caller has already requested SEND_SMS and (for location) ACCESS_FINE_LOCATION
 * at runtime. We catch SecurityException defensively so a missing permission surfaces as a
 * failure notification rather than a crash.
 */
class SosController(private val context: Context) {

    /**
     * Send the SOS SMS. [emergencyContact] is the phone number (E.164 or local digits;
     * Android SmsManager accepts both). [lastLocation] may be null — message still sends
     * with a "no location available" placeholder so the recipient at least knows to check.
     */
    fun fire(emergencyContact: String?, lastLocation: Location?) {
        if (emergencyContact.isNullOrBlank()) return
        val locText = lastLocation?.let {
            "https://maps.google.com/?q=${it.latitude},${it.longitude}"
        } ?: "(no location available)"
        val message = "SOS from REDLINE: possible crash. Last location: $locText. Please check on me."
        try {
            val sms = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }
            sms?.sendTextMessage(emergencyContact, null, message, null, null)
            postSentNotification(emergencyContact)
        } catch (t: Throwable) {
            postFailureNotification(t.message ?: "unknown error")
        }
    }

    /**
     * Request a *fresh* high-accuracy fix (single-shot), falling back to
     * last-known if it times out. [lastKnownLocation] alone is often null or
     * minutes stale at the crash site, so the map link could point miles away;
     * a fresh fix is worth the few-second wait before sending the SOS.
     */
    suspend fun freshLocation(timeoutMs: Long = 5_000L): Location? {
        if (!hasFineLocation()) return null
        val fused = LocationServices.getFusedLocationProviderClient(context)
        val fresh = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                val cts = CancellationTokenSource()
                try {
                    fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                        .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                        .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                } catch (_: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation { cts.cancel() }
            }
        }
        return fresh ?: lastKnownLocation()
    }

    /** Look up the last-known location from GPS, falling back to network provider. */
    fun lastKnownLocation(): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: SecurityException) {
            null
        }
    }

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun postSentNotification(to: String) {
        val n = NotificationCompat.Builder(context, GixxerApp.CHANNEL_BIKE_SERVICE)
            .setContentTitle("SOS sent")
            .setContentText("SOS message sent to $to")
            .setSmallIcon(R.drawable.ic_notification)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID_RESULT, n)
    }

    private fun postFailureNotification(reason: String) {
        val n = NotificationCompat.Builder(context, GixxerApp.CHANNEL_BIKE_SERVICE)
            .setContentTitle("SOS FAILED")
            .setContentText(reason)
            .setSmallIcon(R.drawable.ic_notification)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID_RESULT, n)
    }

    companion object {
        /** Notification ID for the "SOS sent" / "SOS FAILED" outcome banner. */
        const val NOTIF_ID_RESULT = 99

        /** Notification ID for the in-flight "Crash detected — tap if OK" prompt. */
        const val NOTIF_ID_CRASH_PROMPT = 100
    }
}
