package dev.mrwick.gixxerbridge.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks battery + cellular signal state for the a533 heartbeat fields.
 * One instance lives in BikeBridgeService; observed by HeartbeatLoop.
 */
class PhoneState(private val context: Context) {

    private val _batteryPercent = MutableStateFlow(50)
    val batteryPercent: StateFlow<Int> = _batteryPercent.asStateFlow()

    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    private val _signalBars = MutableStateFlow(3)
    val signalBars: StateFlow<Int> = _signalBars.asStateFlow()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 50)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            _batteryPercent.value = (level * 100 / scale).coerceIn(0, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            _isCharging.value = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }
    }

    private val telephonyCallback: TelephonyCallback? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    _signalBars.value = signalStrength.level.coerceIn(0, 4) // 0..4 -> map to 0..3
                        .let { if (it >= 4) 3 else it }
                }
            }
        } else null

    private var telephonyManager: TelephonyManager? = null

    fun start() {
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        try {
            telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && telephonyCallback != null) {
                telephonyManager?.registerTelephonyCallback(
                    context.mainExecutor,
                    telephonyCallback,
                )
            }
        } catch (_: SecurityException) {
            // READ_PHONE_STATE not granted; fall back to default bars.
        }
    }

    fun stop() {
        try { context.unregisterReceiver(batteryReceiver) } catch (_: IllegalArgumentException) {}
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && telephonyCallback != null) {
                telephonyManager?.unregisterTelephonyCallback(telephonyCallback)
            }
        } catch (_: Throwable) { /* ignore */ }
    }
}
