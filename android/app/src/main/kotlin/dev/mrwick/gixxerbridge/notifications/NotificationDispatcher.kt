package dev.mrwick.gixxerbridge.notifications

import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.mrwick.gixxerbridge.app.AppGraph
import dev.mrwick.gixxerbridge.ble.FrameWriter
import dev.mrwick.gixxerbridge.data.Settings
import dev.mrwick.gixxerbridge.nav.GoogleMapsParser
import dev.mrwick.gixxerbridge.nav.MapsNavSource
import dev.mrwick.gixxerbridge.protocol.CallFrame
import dev.mrwick.gixxerbridge.protocol.MissedCallFrame
import dev.mrwick.gixxerbridge.protocol.SmsFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Singleton router for every NotificationListener event. Filters by package and
 * routes to: Maps nav parser, phone call/SMS encoders, or allowlisted-app SMS frame.
 *
 * Keeps a small allowlist cache so we don't have to round-trip through DataStore
 * on every notification.
 */
object NotificationDispatcher {

    private val tag = "NotifDispatcher"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val allowlist = MutableStateFlow<Set<String>>(Settings.DEFAULT_ALLOWLIST)
    private val callsSeen = mutableMapOf<String, Long>() // key=number, value=tStart for missed-call decision

    fun attach(context: Context) {
        // Keep allowlist in sync with Settings
        scope.launch {
            val s = Settings(context.applicationContext)
            s.mirrorAllowlist.collect { set -> allowlist.value = set }
        }
    }

    fun onPosted(context: Context, sbn: StatusBarNotification) {
        try {
            when (sbn.packageName) {
                GoogleMapsParser.PKG_GOOGLE_MAPS -> handleMaps(context, sbn)
                in PHONE_PACKAGES -> handlePhone(sbn)
                in SMS_PACKAGES -> handleSms(sbn, isSms = true)
                else -> {
                    if (sbn.packageName in allowlist.value) handleSms(sbn, isSms = false)
                }
            }
        } catch (t: Throwable) {
            Log.w(tag, "onPosted threw for ${sbn.packageName}", t)
        }
    }

    fun onRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == GoogleMapsParser.PKG_GOOGLE_MAPS) {
            MapsNavSource.clear()
        }
    }

    private fun handleMaps(context: Context, sbn: StatusBarNotification) {
        val parsed = GoogleMapsParser.parse(context, sbn) ?: return
        MapsNavSource.update(parsed)
    }

    private fun handlePhone(sbn: StatusBarNotification) {
        val title = sbn.notification?.extras?.getString(android.app.Notification.EXTRA_TITLE) ?: return
        val text = sbn.notification?.extras?.getString(android.app.Notification.EXTRA_TEXT)?.lowercase() ?: ""
        if ("incoming" in text || "calling" in text || "ringing" in title.lowercase()) {
            val frame = CallFrame(number = title.take(20), isWhatsapp = false, state = 0x31).encode()
            scope.launch { AppGraph.frameWriter?.enqueue(FrameWriter.Entry(FrameWriter.Priority.URGENT, frame, "call")) }
            callsSeen[title] = System.currentTimeMillis()
        } else if ("missed" in text || "missed" in title.lowercase()) {
            val frame = MissedCallFrame(name = title.take(18), missedCount = 1, isWhatsapp = false).encode()
            scope.launch { AppGraph.frameWriter?.enqueue(FrameWriter.Entry(FrameWriter.Priority.URGENT, frame, "missed")) }
        }
    }

    private fun handleSms(sbn: StatusBarNotification, isSms: Boolean) {
        val extras = sbn.notification?.extras ?: return
        val sender = extras.getString(android.app.Notification.EXTRA_TITLE)?.take(20) ?: return
        // ASSUMED: count = 1 per notification; future improvement is to read EXTRA_NUMBER if present
        // ASSUMED: Android exposes the badge count via the magic key "android.number" on N+ but
        // doesn't expose a Notification.EXTRA_NUMBER constant — using the string literal.
        val count = extras.getInt("android.number", 1).coerceIn(1, 99)
        val frame = SmsFrame(
            sender = sender,
            messageCount = count,
            silenced = true,
            typeByte = if (isSms) 0x4E else 0x57, // 'N' for SMS, 'W' for everything-else (WhatsApp-style)
        ).encode()
        scope.launch { AppGraph.frameWriter?.enqueue(FrameWriter.Entry(FrameWriter.Priority.URGENT, frame, "sms/${sbn.packageName}")) }
    }

    private val PHONE_PACKAGES = setOf(
        "com.google.android.dialer",
        "com.android.dialer",
        "com.samsung.android.dialer",
    )
    private val SMS_PACKAGES = setOf(
        "com.google.android.apps.messaging",
        "com.android.messaging",
    )
}
