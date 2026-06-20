package dev.mrwick.redline.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Auto-generated, editable name for a merged journey ride, e.g.
 * "Sunday ride · 328 km". The rider can override it from TripDetailScreen.
 */
fun mergedRideName(
    startedAtMillis: Long,
    distanceKm: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val day = Instant.ofEpochMilli(startedAtMillis).atZone(zone).dayOfWeek
        .getDisplayName(TextStyle.FULL, Locale.US)
    return "$day ride · $distanceKm km"
}
