package dev.mrwick.gixxerbridge.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Auto-start the BLE service after device boot, if the user opted in.
 * Phase 0 skeleton — actual opt-in pref + start logic lands in Phase 3.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Phase 3: read auto-start pref from DataStore; if enabled,
        //   ContextCompat.startForegroundService(context, Intent(context, BikeBridgeService::class.java))
    }
}
