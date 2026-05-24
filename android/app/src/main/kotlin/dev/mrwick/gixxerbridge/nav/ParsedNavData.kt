package dev.mrwick.gixxerbridge.nav

import androidx.compose.runtime.Immutable
import dev.mrwick.gixxerbridge.protocol.NavFrame
import java.time.LocalTime
import java.util.Locale

/**
 * Normalized Google Maps notification content, ready to build a [NavFrame] from.
 *
 * All string fields are already padded to the on-the-wire widths expected by
 * the a531 frame layout (see [NavFrame] KDoc). Numeric values are encoded as
 * ASCII; unit fields are single-char ('K' or 'M'). [streetName] is for
 * inspector / logging only and is not transmitted.
 */
@Immutable
data class ParsedNavData(
    val maneuverId: Int,            // Mappls maneuver id; 8 = generic arrow
    val distNext: String,           // 4-char ASCII, e.g. "0220" or "01.2"
    val distNextUnit: String,       // "M" or "K"
    val eta: String,                // 6-char ASCII, e.g. "0530PM" or "173000"
    val distTotal: String,          // 4-char ASCII
    val distTotalUnit: String,      // "M" or "K"
    val streetName: String?,        // for inspector / logging only
)

/**
 * Convert this normalized parse into an a531 [NavFrame] ready for the BLE
 * write path. Hardcodes [NavFrame.status]="1" (good signal, real maneuver
 * rendered) and [NavFrame.continueFlag]="1" (not in teardown).
 */
fun ParsedNavData.toNavFrame(): NavFrame =
    NavFrame(
        maneuverId = maneuverId,
        distNext = distNext,
        distNextUnit = distNextUnit,
        eta = eta,
        distTotal = distTotal,
        distTotalUnit = distTotalUnit,
        status = "1",        // pretend we have good cellular signal
        continueFlag = "1",  // not in teardown
    )

// ----------------------------------------------------------------------------
// Distance / ETA normalization helpers (internal to the nav package).
// ----------------------------------------------------------------------------

/**
 * Matches a number (possibly with `.` or `,` decimal) followed by an English unit
 * (`m`, `km`, `mi`, `ft`). The number is captured in group 1 and the unit in group 2.
 * Case-insensitive; optional whitespace between number and unit; matches both
 * left-to-right ("220 m") and (effectively) RTL composite strings where the
 * pattern still appears in this order somewhere in the input.
 */
private val DISTANCE_REGEX = Regex(
    """(\d+(?:[.,]\d+)?)\s*(km|mi|ft|m)\b""",
    RegexOption.IGNORE_CASE,
)

/**
 * Turn "220 m" into `("0220","M")`, "1.2 km" into `("01.2","K")`, "85 m"
 * into `("0085","M")`. Returns `("0000","M")` on null/empty/unparseable input.
 *
 * - Imperial units (`mi`, `ft`) are folded onto the same letter the cluster
 *   expects: miles → `K` (treated as the large unit), feet → `M` (small).
 *   ASSUMED: bike cluster only renders 'K' / 'M' as unit labels; using
 *   another letter would either fall back to nothing or render the raw char.
 *   Best effort given that Maps' locale string is what we got.
 * - Distance string is exactly 4 ASCII chars: if the number+decimal fits we
 *   left-pad with '0'; if it doesn't (e.g. "01.2" = 4 already), pass through;
 *   if it overflows ("12.34"), drop the fractional part.
 */
internal fun normalizeDistance(input: String?): Pair<String, String> {
    if (input.isNullOrBlank()) return "0000" to "M"
    val match = DISTANCE_REGEX.find(input) ?: return "0000" to "M"
    val numberRaw = match.groupValues[1].replace(',', '.')
    val unitRaw = match.groupValues[2].lowercase(Locale.ROOT)

    val unit = when (unitRaw) {
        "km", "mi" -> "K"
        // "m" and "ft" both treated as small unit
        else -> "M"
    }

    // Decide ASCII representation that fits 4 chars.
    val numberStr: String = when {
        // Whole number, no decimal needed
        !numberRaw.contains('.') -> {
            val n = numberRaw.toIntOrNull() ?: 0
            // Up to 4 digits: "0085", "0220", "1234". 5+ digits overflow → clamp.
            "%04d".format(n.coerceIn(0, 9999))
        }
        // Decimal: keep one digit after '.' to maximize info, e.g. "01.2".
        else -> {
            val parts = numberRaw.split('.')
            val whole = parts[0].toIntOrNull() ?: 0
            val frac = parts.getOrNull(1)?.firstOrNull()?.toString() ?: "0"
            // Try "WW.F" (4 chars when whole is 2 digits)
            val candidate = "%02d.%s".format(whole.coerceAtMost(99), frac)
            if (candidate.length == 4) candidate else {
                // Whole >= 100: fall back to integer representation.
                "%04d".format(whole.coerceIn(0, 9999))
            }
        }
    }

    return numberStr to unit
}

/**
 * Matches a clock time. Captures hour, minute, and (optional) AM/PM marker.
 * Hour can be 1 or 2 digits; minute always 2 digits.
 */
private val CLOCK_REGEX = Regex(
    """\b(\d{1,2}):(\d{2})\s*(am|pm)?\b""",
    RegexOption.IGNORE_CASE,
)

/**
 * Matches a "5 min" or "23 mins" segment for fallback ETA computation when
 * no absolute clock time is present in the input.
 */
private val MINUTES_REGEX = Regex("""\b(\d+)\s*min\b""", RegexOption.IGNORE_CASE)

/**
 * From a Google Maps nav_time string like `"5 min · 1.2 km · 4:32 PM"`,
 * extract the arrival clock as the 6-char ASCII expected by a531's ETA field.
 *
 * - When [twelveHour] is true: returns "HHMMAM" or "HHMMPM" (e.g. "0432PM").
 * - When [twelveHour] is false: returns "HHMM00" (e.g. "163200") — the
 *   trailing "00" mirrors what Suzuki Connect itself emits in 24h mode
 *   (NOTES.md "ETA padded to 6").
 * - Fallback: if no clock present but a "N min" segment is, current
 *   wall-clock + N minutes is used. Last-resort fallback: current wall-clock.
 *
 * ASSUMED: Maps' nav_time text is always in the device locale; for non-en
 * locales the clock part may use a different separator or numerals, in
 * which case the regex misses and we fall through to wall-clock. Empirical
 * tuning per locale TBD.
 */
internal fun normalizeEta(remainingText: String?, twelveHour: Boolean): String {
    val clockMatch = remainingText?.let { CLOCK_REGEX.find(it) }
    val (hour24, minute) = if (clockMatch != null) {
        val rawHour = clockMatch.groupValues[1].toInt()
        val minutes = clockMatch.groupValues[2].toInt()
        val ampm = clockMatch.groupValues[3].lowercase(Locale.ROOT)
        val h24 = when (ampm) {
            "am" -> if (rawHour == 12) 0 else rawHour
            "pm" -> if (rawHour == 12) 12 else rawHour + 12
            else -> rawHour
        }
        h24 to minutes
    } else {
        // Try "N min" -> now + N min
        val minMatch = remainingText?.let { MINUTES_REGEX.find(it) }
        val addMin = minMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val now = LocalTime.now().plusMinutes(addMin.toLong())
        now.hour to now.minute
    }

    return if (twelveHour) {
        val hour12 = ((hour24 + 11) % 12) + 1
        val ampm = if (hour24 < 12) "AM" else "PM"
        "%02d%02d%s".format(hour12, minute, ampm)
    } else {
        "%02d%02d00".format(hour24, minute)
    }
}
