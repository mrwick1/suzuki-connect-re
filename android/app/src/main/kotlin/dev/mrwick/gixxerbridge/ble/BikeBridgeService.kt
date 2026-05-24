package dev.mrwick.gixxerbridge.ble

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.app.Notification.FLAG_ONGOING_EVENT
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import dev.mrwick.gixxerbridge.GixxerApp
import dev.mrwick.gixxerbridge.R

/**
 * Foreground service holding the BLE link to the bike.
 *
 * Skeleton in Phase 0 — full wiring lands in Phase 3.
 *
 * Launch rules (per assumptions log A14):
 *   - MUST be started from user-tap in the activity, BOOT_COMPLETED, or an
 *     exempt context. Cannot be started from a background broadcast.
 *   - Once started, the BluetoothGatt(autoConnect=true) handles bike-side
 *     disconnect/reappearance without restarting the service.
 */
class BikeBridgeService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Phase 3: claim BLE link, kick heartbeat loop, observe NavMux, etc.
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        // Phase 3: release BLE client + cancel coroutines
        super.onDestroy()
    }

    private fun startInForeground() {
        val notification: Notification = NotificationCompat.Builder(this, GixxerApp.CHANNEL_BIKE_SERVICE)
            .setContentTitle(getString(R.string.bike_service_notification_title))
            .setSmallIcon(R.drawable.ic_notification)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
            .apply { flags = flags or Notification.FLAG_ONGOING_EVENT }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}
