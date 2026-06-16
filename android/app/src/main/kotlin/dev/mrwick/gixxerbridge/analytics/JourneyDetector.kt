package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.RideEntity

/** Tunable thresholds for [JourneyDetector]. Defaults mirror the spec. */
data class JourneyConfig(
    val gapMaxMin: Int = 120,
    val minSegments: Int = 3,
    val minTotalKm: Int = 80,
)

/** A detected run of consecutive segments that look like one long journey. */
data class JourneySuggestion(
    val rideIds: List<Long>,
    val totalKm: Int,
    val startMillis: Long,
    val endMillis: Long,
)

/**
 * Detects "clear long journeys": maximal runs of consecutive ended rides where
 * each inter-ride gap is ≤ [JourneyConfig.gapMaxMin] AND the odometer chains.
 * Odo-chaining alone is monotonic across all history, so the time gap is the real
 * journey discriminator (see design doc). A run qualifies when it has at least
 * [JourneyConfig.minSegments] segments and covers ≥ [JourneyConfig.minTotalKm].
 */
object JourneyDetector {
    fun detect(rides: List<RideEntity>, cfg: JourneyConfig): List<JourneySuggestion> {
        // Only fully-ended, non-merged rides participate; sort chronologically.
        // A merged parent is itself a journey already — excluding it stops the
        // detector from re-suggesting a merge that includes a merged ride.
        val ended = rides.filter { it.endedAtMillis != null && it.endOdoKm != null && !it.isMerged }
            .sortedBy { it.startedAtMillis }
        if (ended.isEmpty()) return emptyList()

        val out = mutableListOf<JourneySuggestion>()
        var run = mutableListOf(ended.first())

        fun closeRun() {
            if (run.size >= cfg.minSegments) {
                val first = run.first()
                val last = run.last()
                val km = (last.endOdoKm!! - first.startOdoKm).coerceAtLeast(0)
                if (km >= cfg.minTotalKm) {
                    out += JourneySuggestion(
                        rideIds = run.map { it.id },
                        totalKm = km,
                        startMillis = first.startedAtMillis,
                        endMillis = last.endedAtMillis!!,
                    )
                }
            }
        }

        for (i in 1 until ended.size) {
            val prev = run.last()
            val cur = ended[i]
            val gapMin = (cur.startedAtMillis - prev.endedAtMillis!!) / 60_000L
            val chains = cur.startOdoKm == prev.endOdoKm
            if (gapMin in 0..cfg.gapMaxMin.toLong() && chains) {
                run.add(cur)
            } else {
                closeRun()
                run = mutableListOf(cur)
            }
        }
        closeRun()
        return out
    }
}

/** Human label for the inter-ride gap connector, e.g. "15 min later",
 *  "1 h 5 min later", "2 h later". */
fun gapHintLabel(gapMin: Long): String {
    val h = gapMin / 60
    val m = gapMin % 60
    return when {
        h == 0L -> "$m min later"
        m == 0L -> "$h h later"
        else -> "$h h $m min later"
    }
}
