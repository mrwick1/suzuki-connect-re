package dev.mrwick.gixxerbridge.nav

import dev.mrwick.gixxerbridge.protocol.NavFrame
import java.time.LocalTime

/**
 * Builds a single a531 [NavFrame] representing the idle state:
 *   - eta field      = current local time, formatted "HHMMAM"/"HHMMPM"
 *   - distTotal      = temp in °C (zero-padded int, max 4 digits)
 *   - distNext       = weather code, right-aligned in 4 chars (e.g. "0002")
 *   - maneuver       = [ManeuverMap.GENERIC_ARROW] (no real maneuver)
 *   - distNextUnit   = " " (space — no unit label for weather)
 *   - distTotalUnit  = "C"
 *
 * The cluster will render whatever's in the text positions; this is
 * creative-use territory (assumption A17).
 *
 * ASSUMED: the cluster tolerates non-Mappls values in the distance/unit
 * positions and renders them as text. Already partially proven by
 * `tools/forge_display.py` for raw text acceptance; specific layout here
 * (clock in ETA, temp in distTotal, weather code in distNext) is new —
 * may need adjustment after empirical test on the bike.
 *
 * Caller decides cadence — probably 1 Hz to keep the clock fresh.
 */
class IdleClockGenerator(
    private val clock: () -> LocalTime = { LocalTime.now() },
) {
    /**
     * Build the idle a531 frame for the current [clock] reading, given the
     * Suzuki weather code [suzukiWeatherCode] (0-11; see `WeatherCodeMap.kt`)
     * and current temperature [tempCelsius] (null = unknown → "0000").
     */
    fun build(suzukiWeatherCode: Int, tempCelsius: Double?): NavFrame {
        val now = clock()
        val hour12 = ((now.hour + 11) % 12) + 1
        val ampm = if (now.hour < 12) "AM" else "PM"
        val eta = "%02d%02d%s".format(hour12, now.minute, ampm)   // e.g. "0530PM"

        val tempStr = tempCelsius?.let {
            val n = it.toInt().coerceIn(-99, 9999)
            // 4-char zero-padded representation; preserves sign for negatives.
            if (n < 0) "-%03d".format(-n) else "%04d".format(n)
        } ?: "0000"

        val weatherStr = "%04d".format(suzukiWeatherCode.coerceIn(0, 99))

        return NavFrame(
            maneuverId = ManeuverMap.GENERIC_ARROW,
            distNext = weatherStr,
            distNextUnit = " ",
            eta = eta,
            distTotal = tempStr,
            distTotalUnit = "C",
            status = "1",
            continueFlag = "1",
        )
    }
}
