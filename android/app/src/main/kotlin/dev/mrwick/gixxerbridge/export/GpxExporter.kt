package dev.mrwick.gixxerbridge.export

import dev.mrwick.gixxerbridge.data.RideEntity
import dev.mrwick.gixxerbridge.data.RideLocationEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Renders a [RideEntity] + its [RideLocationEntity] list as a GPX 1.1 XML document.
 *
 * Output schema: a single `<trk>` containing a single `<trkseg>`, with one
 * `<trkpt>` per input location. Compatible with Google Earth, Strava upload,
 * RideWithGPS, gpx.studio, etc.
 *
 * Note: lat/lng are written with full Double.toString precision; consumers
 * truncate as needed. Times are ISO-8601 UTC ("Z" suffix) per GPX 1.1 spec.
 */
object GpxExporter {

    /** GPX 1.1 XML for the given ride + its GPS samples. */
    fun toGpx(ride: RideEntity, locations: List<RideLocationEntity>): String = buildString {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine(
            """<gpx version="1.1" creator="REDLINE" """ +
                """xmlns="http://www.topografix.com/GPX/1/1">""",
        )
        appendLine("  <metadata>")
        appendLine("    <name>Ride #${ride.id}</name>")
        appendLine("    <time>${iso.format(Date(ride.startedAtMillis))}</time>")
        appendLine("  </metadata>")
        appendLine("  <trk>")
        appendLine("    <name>Ride #${ride.id}</name>")
        appendLine("    <trkseg>")
        for (loc in locations) {
            appendLine("""      <trkpt lat="${loc.lat}" lon="${loc.lng}">""")
            loc.altitudeM?.let { appendLine("        <ele>$it</ele>") }
            appendLine("        <time>${iso.format(Date(loc.tMillis))}</time>")
            appendLine("      </trkpt>")
        }
        appendLine("    </trkseg>")
        appendLine("  </trk>")
        appendLine("</gpx>")
    }
}
