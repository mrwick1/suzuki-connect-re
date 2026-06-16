package dev.mrwick.gixxerbridge.nav

import dev.mrwick.gixxerbridge.protocol.NavFrame
import java.time.LocalTime

/**
 * Builds a single a531 [NavFrame] representing the idle state:
 *   - eta field      = current local time, formatted "HHMMAM"/"HHMMPM"
 *   - distTotal      = temp in °C (zero-padded int, max 4 digits)
 *   - distNext       = weather code, right-aligned in 4 chars (e.g. "0002")
 *   - maneuver       = [ManeuverMap.DEFAULT_CLUSTER_BYTE] (no real maneuver)
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
            maneuverId = ManeuverMap.NO_MANEUVER_BYTE,
            distNext = weatherStr,
            distNextUnit = " ",
            eta = eta,
            distTotal = tempStr,
            distTotalUnit = "C",
            status = "1",
            continueFlag = "1",
        )
    }

    /**
     * Build an a531 frame that surfaces the currently-playing track on the
     * cluster's text positions. Caller is expected to pass a pre-trimmed
     * single-line label (see [dev.mrwick.gixxerbridge.notifications.NowPlaying.forCluster]).
     *
     * Layout chosen (one a531 frame):
     *   - maneuverId    = [ManeuverMap.DEFAULT_CLUSTER_BYTE] (8) — cluster byte for straight/forward (OEM-translated from Mappls 7)
     *   - eta           = "PLAYNG" (fixed 6-char label)
     *   - distNext      = first 4 chars of trackTitle.uppercase()
     *   - distNextUnit  = "@"
     *   - distTotal     = next 4 chars of trackTitle (offset 4) uppercase
     *   - distTotalUnit = "*"
     *   - status / continueFlag = "1" / "1"
     *
     * ASSUMED: the cluster will render the trackTitle chunks in the distance
     * positions as legible text (same creative-text-positions assumption that
     * powers [build]). Specific behaviour for the "PLAYNG / @ / *" layout has
     * NOT been proven on the bike — revisit after first cluster test.
     */
    fun buildNowPlaying(nowPlaying: String): NavFrame {
        val upper = nowPlaying.uppercase()
        val chunk1 = upper.take(4).padEnd(4, ' ')
        val chunk2 = upper.drop(4).take(4).padEnd(4, ' ')
        return NavFrame(
            // ASSUMED: cluster will not draw a turn arrow for maneuverId=8 in
            // this layout; tolerates the text-only repurposing.
            maneuverId = ManeuverMap.NO_MANEUVER_BYTE,
            distNext = chunk1,
            distNextUnit = "@",
            eta = "PLAYNG",
            distTotal = chunk2,
            distTotalUnit = "*",
            status = "1",
            continueFlag = "1",
        )
    }

    /**
     * Build an a531 frame that surfaces estimated range remaining on the
     * cluster's text positions.
     *
     * Layout (one a531 frame):
     *   - maneuverId    = [ManeuverMap.DEFAULT_CLUSTER_BYTE] (no turn arrow)
     *   - eta           = "RANGE" (fixed marker, fits the 6-char eta slot)
     *   - distNext      = km number in up to 4 chars (e.g. "140"), or
     *                     "----" when no estimate is available
     *   - distNextUnit  = "K"
     *   - distTotal     = "0000" / distTotalUnit = " " (unused here)
     *   - status / continueFlag = "1" / "1"
     *
     * ASSUMED (UNVERIFIED on bike): the cluster renders the "RANGE" marker in
     * the eta slot and a bare number+'K' in the distNext slot as legible text.
     * Same creative-text-positions assumption as [build] / [buildNowPlaying];
     * the specific "RANGE / NNNN K" layout has NOT been confirmed on the
     * cluster. Revisit after first on-bike trial.
     */
    fun buildRange(rangeKm: Double?): NavFrame {
        val r = ClusterRangeFormatter.format(rangeKm)
        return NavFrame(
            maneuverId = ManeuverMap.DEFAULT_CLUSTER_BYTE,
            distNext = r.kmText,
            distNextUnit = r.kmUnit,
            eta = ClusterRangeFormatter.LABEL,
            distTotal = "0000",
            distTotalUnit = " ",
            status = "1",
            continueFlag = "1",
        )
    }
}
