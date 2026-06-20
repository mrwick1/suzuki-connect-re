package dev.mrwick.redline.system

import android.app.NotificationManager
import android.content.Context
import android.util.Log

/**
 * Saves the user's pre-connect DND state, sets DND to PRIORITY while connected,
 * restores on disconnect. No-op if [NotificationManager.isNotificationPolicyAccessGranted]
 * is false — the user has to grant DND access in system settings.
 *
 * ASSUMED: PRIORITY (not TOTAL_SILENCE / ALARMS) is the right filter — it still
 * lets through calls/messages from starred contacts and alarms, which is the
 * sensible default while riding. Revisit if Arjun wants a stricter filter.
 */
class DndController(context: Context) {
    private val nm = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var previousFilter: Int? = null

    /** Snapshot the current DND filter (if not already snapshotted) and switch to PRIORITY. */
    fun activate() {
        if (!nm.isNotificationPolicyAccessGranted) {
            Log.i(TAG, "DND policy access not granted; skipping activate()")
            return
        }
        if (previousFilter == null) {
            previousFilter = nm.currentInterruptionFilter
        }
        try {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        } catch (t: SecurityException) {
            // ASSUMED: setInterruptionFilter can theoretically throw on some OEMs even
            // after the policy-access check; swallow + log rather than crashing the service.
            Log.w(TAG, "setInterruptionFilter denied: ${t.message}")
        }
    }

    /** Restore the pre-activate DND filter, if any. */
    fun restore() {
        if (!nm.isNotificationPolicyAccessGranted) return
        val prev = previousFilter ?: return
        try {
            nm.setInterruptionFilter(prev)
        } catch (t: SecurityException) {
            Log.w(TAG, "setInterruptionFilter (restore) denied: ${t.message}")
        }
        previousFilter = null
    }

    private companion object {
        const val TAG = "DndController"
    }
}
