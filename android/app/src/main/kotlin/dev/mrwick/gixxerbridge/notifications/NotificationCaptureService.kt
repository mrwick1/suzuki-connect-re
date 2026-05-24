package dev.mrwick.gixxerbridge.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Captures Google Maps nav notifications + calls + SMS + allowlisted apps.
 * Skeleton — Phase 3 wires actual dispatch to the bridge classes.
 *
 * Per R1 research (assumptions log A1), Google Maps uses RemoteViews; the
 * RemoteViews-walking parser lives in nav/GoogleMapsParser.kt and is called
 * from this listener once Phase 3 lands.
 */
class NotificationCaptureService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Phase 3: dispatch by package name
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Phase 3: emit null on Maps removal so NavMux falls back to idle clock
    }
}
