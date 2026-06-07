package dev.mrwick.gixxerbridge.nav

import kotlin.math.roundToInt

/**
 * Pre-rendered range strings for the a531 cluster slots. The cluster has no
 * free-text channel — only short numeric/text slots ([NavFrame.distNext] 4
 * chars, [NavFrame.eta] 6 chars, [NavFrame.distTotal] 4 chars). This holder
 * is what [IdleClockGenerator.buildRange] writes into those slots.
 */
data class ClusterRange(
    val kmText: String,   // fits distNext (<=4 chars)
    val kmUnit: String,   // distNextUnit, e.g. "K"
    val isUnavailable: Boolean,
)

/**
 * Formats an estimated km-remaining [Double] into the short ASCII the a531
 * layout can carry. Pure JVM, deterministic — tested in
 * ClusterRangeFormatterTest. The actual on-cluster rendering of these strings
 * is UNVERIFIED on the bike (see plan caveats).
 */
object ClusterRangeFormatter {

    /** Fixed 6-char-or-less marker shown in the eta slot. */
    const val LABEL: String = "RANGE"

    private const val MAX_KM = 9999 // 4-char distNext ceiling

    fun format(rangeKm: Double?): ClusterRange {
        if (rangeKm == null || rangeKm.isNaN()) {
            return ClusterRange(kmText = "----", kmUnit = "K", isUnavailable = true)
        }
        val km = rangeKm.roundToInt().coerceIn(0, MAX_KM)
        return ClusterRange(kmText = km.toString(), kmUnit = "K", isUnavailable = false)
    }
}
