package dev.mrwick.redline.ble

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dev.mrwick.redline.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Auto-starts [BikeBridgeService] after device boot when the user enabled
 * "Auto-start after boot" (Developer settings → [Settings.autoStartOnBoot]).
 *
 * BOOT_COMPLETED is one of the exemptions that permits starting a foreground
 * service from the background on Android 12+, so this is allowed provided the
 * BLE start permission is present (BLUETOOTH_CONNECT on 12+). Reading the
 * opt-in flag is a DataStore I/O, so we use [goAsync] to keep the receiver
 * alive past `onReceive` while the suspend read completes.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appCtx = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val enabled = Settings(appCtx).autoStartOnBoot.first()
                if (enabled && hasBleStartPermission(appCtx)) {
                    ContextCompat.startForegroundService(
                        appCtx,
                        Intent(appCtx, BikeBridgeService::class.java),
                    )
                    Log.i("BootReceiver", "auto-started BikeBridgeService on boot")
                } else {
                    Log.i("BootReceiver", "boot auto-start skipped (enabled=$enabled, perm=${hasBleStartPermission(appCtx)})")
                }
            } catch (t: Throwable) {
                Log.w("BootReceiver", "boot auto-start failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Mirrors MainActivity's check: pre-Android-12 needs no runtime BLE
     * permission; Android 12+ needs BLUETOOTH_CONNECT for a connectedDevice FGS.
     */
    private fun hasBleStartPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}
