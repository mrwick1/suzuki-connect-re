package dev.mrwick.gixxerbridge.safety

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dev.mrwick.gixxerbridge.util.AppLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Accelerometer-driven crash detector.
 *
 * The previous implementation watched the bike's a537 telemetry speed for a
 * sudden drop. That had two fatal flaws: (1) it depended on BLE telemetry, whose
 * speed field is stale/inert on this bike, so it likely never fired on a real
 * ride; and (2) "fast deceleration to a stop" fires on every normal traffic-light
 * braking, so it would nuisance-alert constantly.
 *
 * This version is independent of BLE and far harder to false-trigger:
 *
 *   1. IMPACT — a high-magnitude linear-acceleration spike (>= [impactThresholdMs2],
 *      ~3.5 G). Normal braking produces nothing close to this; a real impact does.
 *   2. WAS-MOVING (soft gate) — if a speed reading is available (GPS preferred,
 *      telemetry fallback) and shows the rider was slower than [minPreSpeedKmh],
 *      ignore it (a knock to a parked phone). Unknown speed → proceed on 1 + 3.
 *   3. STILLNESS — after a [settleMs] settle, the device must stay essentially
 *      motionless (peak motion < [stillnessMs2]) for the rest of [confirmWindowMs].
 *      A rider who picks the phone up / keeps moving cancels the alert; a rider
 *      who is down and still does not.
 *
 * Only fires once per [cooldownMs]. STILL UNVERIFIED: thresholds need on-bike /
 * controlled-drop testing before this is relied upon (project no-assumptions rule).
 */
class CrashDetector(
    private val context: Context,
    private val latestSpeedKmh: () -> Int?,
    private val onCrashSuspected: () -> Unit,
    private val impactThresholdMs2: Float = 35f,
    private val minPreSpeedKmh: Int = 15,
    private val settleMs: Long = 1_500L,
    private val confirmWindowMs: Long = 8_000L,
    private val stillnessMs2: Float = 3.0f,
    private val cooldownMs: Long = 60_000L,
) {
    suspend fun run() {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: run {
            AppLog.w(TAG, "no SensorManager — crash detection disabled")
            return
        }
        // Prefer gravity-removed linear acceleration; fall back to raw accelerometer.
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: run {
                AppLog.w(TAG, "no accelerometer — crash detection disabled")
                return
            }
        val gravityBaked = sensor.type == Sensor.TYPE_ACCELEROMETER

        val magnitudes = callbackFlow {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
                    var m = sqrt(x * x + y * y + z * z)
                    // Raw accelerometer includes ~1 G gravity; approximate removal.
                    if (gravityBaked) m = abs(m - SensorManager.GRAVITY_EARTH)
                    trySend(m)
                }
                override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
            }
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            awaitClose { sm.unregisterListener(listener) }
        }

        var lastAlertMs = 0L
        var impactAtMs = 0L
        var peakMotionAfterSettle = 0f

        magnitudes.collect { mag ->
            val now = System.currentTimeMillis()
            if (impactAtMs == 0L) {
                if (mag >= impactThresholdMs2 && now - lastAlertMs >= cooldownMs) {
                    val spd = latestSpeedKmh()
                    // Soft pre-speed gate: only suppress when we KNOW the rider was
                    // slow. Unknown (null) → proceed on impact + stillness alone.
                    if (spd == null || spd >= minPreSpeedKmh) {
                        AppLog.i(TAG, "impact ${"%.0f".format(mag)} m/s² (preSpeed=${spd ?: "?"} km/h) — confirming…")
                        impactAtMs = now
                        peakMotionAfterSettle = 0f
                    }
                }
            } else {
                val sinceImpact = now - impactAtMs
                if (sinceImpact > settleMs) {
                    peakMotionAfterSettle = max(peakMotionAfterSettle, mag)
                }
                if (sinceImpact >= confirmWindowMs) {
                    if (peakMotionAfterSettle < stillnessMs2) {
                        AppLog.w(TAG, "crash confirmed — rider still ${confirmWindowMs}ms after impact")
                        lastAlertMs = now
                        onCrashSuspected()
                    } else {
                        AppLog.i(TAG, "impact cleared — motion resumed (peak ${"%.0f".format(peakMotionAfterSettle)} m/s²)")
                    }
                    impactAtMs = 0L
                }
            }
        }
    }

    private companion object {
        const val TAG = "CrashDetector"
    }
}
