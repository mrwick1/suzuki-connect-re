package dev.mrwick.redline

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.mrwick.redline.util.AppLog
import dev.mrwick.redline.util.CrashHandler

/**
 * Single Application instance. Sets up the BLE-service notification channel and
 * any other process-global state. No DI framework — just `object` singletons
 * elsewhere and constructor injection where possible.
 */
class GixxerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Install crash handler FIRST so anything that throws during the rest
        // of onCreate (channel registration, future init) still lands on disk.
        CrashHandler.install(this)
        AppLog.init(this)
        dev.mrwick.redline.app.AppGraph.frameStream.init(this)
        AppLog.i("App", "onCreate pid=${android.os.Process.myPid()} pkg=$packageName")
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
        // HIGH importance — required for the crash prompt's full-screen intent to
        // promote to a full-screen activity (a LOW channel silently downgrades it).
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CRASH_ALERT,
                "Crash alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Full-screen prompt shown when a crash is suspected."
                enableVibration(true)
            }
        )
    }

    companion object {
        const val CHANNEL_BIKE_SERVICE = "bike_service"
        const val CHANNEL_CRASH_ALERT = "crash_alert"
    }
}
