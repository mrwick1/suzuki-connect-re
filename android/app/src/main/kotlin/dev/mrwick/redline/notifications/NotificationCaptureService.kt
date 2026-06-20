package dev.mrwick.redline.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Listens to every posted/dismissed Android notification.
 * Dispatch + filtering lives in [NotificationDispatcher] (singleton, shared with the foreground service).
 *
 * Lifecycle note: this service is started by the system when the user grants
 * notification-listener access. It runs in our app process, so singletons work.
 */
class NotificationCaptureService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationDispatcher.attach(applicationContext)
        // Replay any already-posted notifications through the dispatcher so we
        // don't miss the active Maps nav (which was posted before our listener
        // bound). Without this, the rider has to wait for the next Maps update
        // before the cluster sees a single a531 — confusing UX on first connect.
        try {
            activeNotifications?.forEach { sbn ->
                NotificationDispatcher.onPosted(applicationContext, sbn)
            }
        } catch (_: Throwable) { /* best-effort */ }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotificationDispatcher.onPosted(applicationContext, sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationDispatcher.onRemoved(sbn)
    }
}
