package dev.mrwick.gixxerbridge.notifications

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
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotificationDispatcher.onPosted(applicationContext, sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationDispatcher.onRemoved(sbn)
    }
}
