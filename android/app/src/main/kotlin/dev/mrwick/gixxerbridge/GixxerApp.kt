package dev.mrwick.gixxerbridge

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Single Application instance. Sets up the BLE-service notification channel and
 * any other process-global state. No DI framework — just `object` singletons
 * elsewhere and constructor injection where possible.
 */
class GixxerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerNotificationChannels()
    }

    private fun registerNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BIKE_SERVICE,
                getString(R.string.bike_service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.bike_service_channel_desc)
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_BIKE_SERVICE = "bike_service"
    }
}
