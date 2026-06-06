package dev.mrwick.gixxerbridge.safety

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.mrwick.gixxerbridge.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fires the crash SOS when the countdown deadline is reached — driven by an
 * AlarmManager exact alarm rather than an in-process coroutine, so the SOS still
 * sends even if the app process was killed during the countdown (a crash is
 * exactly when the phone may be damaged or the OS may reclaim the process).
 *
 * The alarm only *acts* if [Settings.sosArmed] is still true: the SosScreen
 * "I'm OK" / "I'm fine" buttons disarm it, so a cancelled countdown becomes a
 * harmless no-op even though the alarm still fires.
 */
class SosAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE_SOS) return
        val appCtx = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val settings = Settings(appCtx)
                if (settings.sosArmed.first()) {
                    val contact = settings.emergencyContactPhone.first()
                    val sos = SosController(appCtx)
                    sos.fire(contact, sos.freshLocation())
                    settings.setSosArmed(false)
                    Log.i(TAG, "SOS fired via alarm (survived countdown)")
                } else {
                    Log.i(TAG, "SOS alarm fired but countdown was disarmed — no-op")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "SOS alarm handling failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE_SOS = "dev.mrwick.gixxerbridge.action.FIRE_SOS"
        const val REQUEST_CODE = 4201
        private const val TAG = "SosAlarmReceiver"
    }
}
