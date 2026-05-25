package dev.mrwick.gixxerbridge.nav

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import kotlinx.coroutines.flow.first
import android.widget.TextView

/**
 * Parses Google Maps turn-by-turn notifications into [ParsedNavData].
 *
 * 2026-05-25 empirical finding (see captures/maps-notification-dump-*.md): current Maps
 * versions on Android 16 use the standard `Notification.extras` ProgressStyle template,
 * NOT a custom RemoteViews layout as the 2020 reference implementation assumed.
 *
 * Layout we observe in extras:
 *   android.title       → "Head toward MG Road" / "In 200 m turn left" — the instruction
 *   android.subText     → "Arrive 8:14 am" / "5 min · 1.2 km" — ETA + maybe total dist
 *   android.progress    → meters elapsed into the current maneuver segment
 *   android.progressMax → meters total for the current segment
 *   android.largeIcon   → the turn-arrow Bitmap (132×132 typical)
 *
 * Strategy:
 *  1. Try extras path first (handles current Maps versions cleanly)
 *  2. Fall back to RemoteViews-walk for older Maps that still ships nav_title etc.
 *  3. Return null only if both paths produce nothing useful.
 */
object GoogleMapsParser {

    /** Google Maps' Play Store / system package name. */
    const val PKG_GOOGLE_MAPS = "com.google.android.apps.maps"

    /**
     * Parse [sbn] as a Google Maps nav notification. Returns `null` when the
     * SBN is not from Maps or no nav data could be recovered.
     */
    fun parse(context: Context, sbn: StatusBarNotification): ParsedNavData? {
        if (sbn.packageName != PKG_GOOGLE_MAPS) return null
        val notification = sbn.notification ?: return null

        // Primary path: extras (current Maps versions).
        parseFromExtras(context, notification)?.let { return it }

        // Fallback: RemoteViews tree walk for older Maps versions.
        return parseFromRemoteViews(context, notification)
    }

    // ----------------------------------------------------------------------
    // Primary path: Notification.extras (Maps ProgressStyle template)
    // ----------------------------------------------------------------------

    private fun parseFromExtras(context: Context, n: Notification): ParsedNavData? {
        val extras = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        if (title.isNullOrBlank() && subText.isNullOrBlank() && text.isNullOrBlank()) return null

        // Distance: prefer the explicit "In X m / X km" prefix from the title;
        // fall back to progress/progressMax (meters) if no inline distance string.
        val distFromTitle = title?.let { extractInlineDistance(it) }
        val (distNext, distNextUnit) = distFromTitle ?: distanceFromProgress(extras)

        // ETA: subText is often "Arrive H:MM am/pm" OR "5 min · 1.2 km · 4:32 PM".
        val eta = normalizeEta(subText, twelveHour = true)
        val (distTotal, distTotalUnit) = extractTotalDistance(subText)

        // Instruction string for the maneuver classifier text fallback.
        val instruction = title ?: text

        // Maneuver bitmap: largeIcon on this notification is the turn arrow.
        val maneuverBitmap = extractLargeIconBitmap(context, n)
        // Read self-train pref synchronously via runBlocking on the DataStore flow.
        // Cheap (one read per Maps notification, ~2 Hz max); avoids threading the
        // pref through the whole parse API.
        val selfTrain = kotlinx.coroutines.runBlocking {
            dev.mrwick.gixxerbridge.app.AppGraph.settings(context).maneuverSelfTrainEnabled.first()
        }
        val maneuverId = ManeuverClassifier.classify(maneuverBitmap, instruction, selfTrain)
        dev.mrwick.gixxerbridge.util.AppLog.i(
            "MapsParser",
            "notif title=\"${title?.take(60)}\" bitmap=${maneuverBitmap != null} -> id=$maneuverId",
        )

        return ParsedNavData(
            maneuverId = maneuverId,
            distNext = distNext,
            distNextUnit = distNextUnit,
            eta = eta,
            distTotal = distTotal,
            distTotalUnit = distTotalUnit,
            streetName = instruction,
        )
    }

    /** "In 200 m turn left" / "In 1.2 km" / "Turn right in 50 m" → (distance string, unit). */
    private fun extractInlineDistance(text: String): Pair<String, String>? {
        val re = Regex("""(?i)(?:in\s+)?(\d+(?:[.,]\d+)?)\s*(km|kilometers?|m|meters?|mi|miles?|ft|feet?)\b""")
        val match = re.find(text) ?: return null
        return normalizeDistance(match.value)
    }

    /** Use ProgressStyle's `progressMax - progress` (meters) when no inline distance is in title. */
    private fun distanceFromProgress(extras: android.os.Bundle): Pair<String, String> {
        val max = extras.getInt("android.progressMax", -1)
        val cur = extras.getInt("android.progress", 0)
        if (max <= 0) return "0000" to "M"
        val remainingMeters = (max - cur).coerceAtLeast(0)
        return if (remainingMeters >= 1000) {
            val km = remainingMeters / 1000.0
            // 4-char "XX.X" format
            "%4.1f".format(km.coerceAtMost(99.9)).replace(' ', '0') to "K"
        } else {
            "%04d".format(remainingMeters.coerceAtMost(9999)) to "M"
        }
    }

    /** Pull the Bitmap out of Notification.largeIcon (when present + bitmap-backed). */
    private fun extractLargeIconBitmap(context: Context, n: Notification): Bitmap? {
        val icon: Icon = n.getLargeIcon() ?: return null
        return try {
            val drawable = icon.loadDrawable(context) ?: return null
            (drawable as? BitmapDrawable)?.bitmap
        } catch (_: Throwable) {
            null
        }
    }

    // ----------------------------------------------------------------------
    // Fallback path: RemoteViews tree walk (older Maps versions, 2020 GMapsParser)
    // ----------------------------------------------------------------------

    private fun parseFromRemoteViews(context: Context, notification: Notification): ParsedNavData? {
        val bigViews = try {
            Notification.Builder.recoverBuilder(context, notification).createBigContentView()
        } catch (_: Throwable) { null }
            ?: notification.bigContentView
            ?: notification.contentView
            ?: return null

        val inflaterCtx = try {
            context.createPackageContext(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "com.android.systemui"
                else PKG_GOOGLE_MAPS,
                Context.CONTEXT_IGNORE_SECURITY,
            )
        } catch (_: Throwable) {
            context
        }

        val root: View = try {
            bigViews.apply(inflaterCtx, null)
        } catch (_: Throwable) {
            return null
        }

        val titles = mutableMapOf<String, String>()
        val maneuverBitmap = walkAndCollect(root, titles)

        val distRaw = titles["nav_title"] ?: titles["lockscreen_directions"]
        val instruction = titles["nav_description"] ?: titles["lockscreen_oneliner"]
        val timeRaw = titles["nav_time"] ?: titles["lockscreen_eta"]

        if (distRaw.isNullOrBlank() && instruction.isNullOrBlank()) return null

        val (distNext, distNextUnit) = normalizeDistance(distRaw)
        val (distTotal, distTotalUnit) = extractTotalDistance(timeRaw)
        val eta = normalizeEta(timeRaw, twelveHour = true)
        val maneuverId = ManeuverClassifier.classify(maneuverBitmap, instruction)

        return ParsedNavData(
            maneuverId = maneuverId,
            distNext = distNext,
            distNextUnit = distNextUnit,
            eta = eta,
            distTotal = distTotal,
            distTotalUnit = distTotalUnit,
            streetName = instruction,
        )
    }

    /** RemoteViews tree walker (fallback path). */
    private fun walkAndCollect(view: View, into: MutableMap<String, String>): Bitmap? {
        var bitmap: Bitmap? = null
        if (view is TextView) {
            val name = try {
                view.resources.getResourceEntryName(view.id)
            } catch (_: Throwable) { null }
            if (name != null && view.text != null) into[name] = view.text.toString()
        }
        if (view is ImageView) {
            val name = try {
                view.resources.getResourceEntryName(view.id)
            } catch (_: Throwable) { null }
            if (name == "nav_notification_icon" ||
                name == "right_icon" ||
                name == "lockscreen_notification_icon"
            ) {
                val drawable = view.drawable
                if (drawable is BitmapDrawable) bitmap = drawable.bitmap
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walkAndCollect(view.getChildAt(i), into)?.let { bitmap = it }
            }
        }
        return bitmap
    }

    /** Pull the total-distance segment out of "5 min · 1.2 km · 4:32 PM". */
    private fun extractTotalDistance(timeRaw: String?): Pair<String, String> {
        if (timeRaw.isNullOrBlank()) return "0000" to "M"
        val distRegex = Regex(
            """(\d+(?:[.,]\d+)?)\s*(km|m|mi|ft)""",
            RegexOption.IGNORE_CASE,
        )
        val match = distRegex.find(timeRaw) ?: return "0000" to "M"
        return normalizeDistance(match.value)
    }
}
