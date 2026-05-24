package dev.mrwick.gixxerbridge.nav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.app.Notification

/**
 * Parses Google Maps turn-by-turn notifications by inflating their custom
 * RemoteViews layout and reading the labeled `TextView`s by their resource
 * entry name.
 *
 * Per assumptions log A1: `Notification.extras` does NOT carry parseable nav
 * text — Maps uses a custom RemoteViews layout. The path here mirrors
 * 3v1n0/GMapsParser, including the Android 12+ inflater-context quirk
 * (must use `com.android.systemui` package context, not Maps').
 *
 * A2: the maneuver icon is a `Bitmap` on an `ImageView`, not a stable
 * resource ID. We collect the bitmap here for future classification but
 * fall back to [ManeuverMap.fromText] on the instruction string.
 */
object GoogleMapsParser {

    /** Google Maps' Play Store / system package name. */
    const val PKG_GOOGLE_MAPS = "com.google.android.apps.maps"

    /**
     * Parse [sbn] as a Google Maps nav notification.
     *
     * Returns `null` when the SBN is not from Maps, when no nav data could
     * be recovered, or when inflation failed (e.g. RemoteViews referenced a
     * resource the inflater context can't resolve).
     */
    fun parse(context: Context, sbn: StatusBarNotification): ParsedNavData? {
        if (sbn.packageName != PKG_GOOGLE_MAPS) return null
        val notification = sbn.notification ?: return null

        // Per R1: Notification.Builder.recoverBuilder is the platform API (since API 24) that
        // rebuilds the RemoteViews layout Maps embeds. Wrap in try/catch — some ROMs throw on
        // foreign-package notifications; we fall back to the raw bigContentView/contentView.
        val bigViews = try {
            Notification.Builder.recoverBuilder(context, notification).createBigContentView()
        } catch (_: Throwable) { null }
            ?: notification.bigContentView
            ?: notification.contentView
            ?: return null

        // Android 12+: must inflate against systemui's package context, not Maps'.
        // (Per GMapsParser PR #8 — unmerged but cited as the only working path.)
        val inflaterCtx = try {
            context.createPackageContext(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "com.android.systemui"
                else PKG_GOOGLE_MAPS,
                Context.CONTEXT_IGNORE_SECURITY,
            )
        } catch (_: Throwable) {
            // ASSUMED: if even systemui context fails (some ROMs strip), fall back to ours;
            // RemoteViews.apply may then fail to find Maps' resource IDs, but we try.
            context
        }

        val root: View = try {
            bigViews.apply(inflaterCtx, null)
        } catch (_: Throwable) {
            return null
        }

        val titles = mutableMapOf<String, String>()
        val maneuverBitmap = walkAndCollect(root, titles)

        // Field set per R1: nav_title (dist), nav_description (instruction), nav_time ("5 min · 1.2 km · 4:32 PM")
        val distRaw = titles["nav_title"] ?: titles["lockscreen_directions"]
        val instruction = titles["nav_description"] ?: titles["lockscreen_oneliner"]
        val timeRaw = titles["nav_time"] ?: titles["lockscreen_eta"]

        if (distRaw.isNullOrBlank() && instruction.isNullOrBlank()) return null

        val (distNext, distNextUnit) = normalizeDistance(distRaw)
        val (distTotal, distTotalUnit) = extractTotalDistance(timeRaw)
        val eta = normalizeEta(timeRaw, twelveHour = true)

        // ASSUMED: Maps' nav_description text is in the device locale; for non-en
        // locales the text-pattern map in ManeuverMap will miss and fall to
        // GENERIC_ARROW. Empirical locale tuning deferred to Phase 3 polish.
        val maneuverId = ManeuverMap.fromText(instruction)

        // Bitmap classification not yet wired; left for future improvement.
        // If we got a maneuverBitmap, future code will perceptual-hash + lookup
        // via ManeuverMap.fromBitmapHash(). Suppress unused-var warnings.
        @Suppress("UNUSED_VARIABLE") val _bitmap: Bitmap? = maneuverBitmap

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

    /**
     * Recursively visit [TextView] / [ImageView] descendants of [view],
     * recording each TextView's text by its resource-entry-name in [into].
     * Returns the first relevant maneuver-icon [Bitmap] encountered, if any.
     */
    private fun walkAndCollect(view: View, into: MutableMap<String, String>): Bitmap? {
        var bitmap: Bitmap? = null
        if (view is TextView) {
            val name = try {
                view.resources.getResourceEntryName(view.id)
            } catch (_: Throwable) {
                null
            }
            if (name != null && view.text != null) into[name] = view.text.toString()
        }
        if (view is ImageView) {
            val name = try {
                view.resources.getResourceEntryName(view.id)
            } catch (_: Throwable) {
                null
            }
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

    /**
     * Pull the total-distance segment out of Maps' nav_time string
     * (`"5 min · 1.2 km · 4:32 PM"`). Returns `("0000","M")` on miss.
     */
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
