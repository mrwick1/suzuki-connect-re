package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.RideLocationEntity
import dev.mrwick.gixxerbridge.data.RideSampleEntity
import kotlin.math.abs

/**
 * Produces a list of colored [Segment]s for a "speed-colored route track" —
 * i.e. a polyline where each polyline segment is painted in the zone color
 * matching the bike's speed at that point.
 *
 * ## Input contract
 *
 * - [locations]: GPS points for one ride, ordered by [RideLocationEntity.tMillis]
 *   ascending. Must have at least 2 points to produce any output.
 * - [samples]: Telemetry samples for the same ride, ordered by
 *   [RideSampleEntity.tMillis] ascending. The lists are *independent streams*
 *   (GPS arrives at ~0.2 Hz, BLE telemetry at ~1 Hz); they are joined by
 *   nearest-timestamp matching.
 *
 * ## Algorithm
 *
 * For each GPS location point we find the telemetry sample whose [tMillis] is
 * closest to the location's [tMillis]. The speed from that sample determines
 * the zone color for the segment *ending* at this location point.
 *
 * When [samples] is empty, all segments fall back to [SpeedTrackColors.COLOR_COOL]
 * (no penalty, just the lowest zone).
 *
 * ## Output
 *
 * A list of [Segment]s of length `locations.size - 1`. Each segment carries
 * the start / end lat-lng pair (copied directly from the [RideLocationEntity]
 * values, no projection) and the ARGB Int zone color for that segment.
 *
 * Pure JVM, no Android imports — easy to test with hand-crafted inputs.
 */
object SpeedTrack {

    /**
     * One colored segment of the route.
     *
     * @param startLat  Start latitude (degrees, WGS-84).
     * @param startLng  Start longitude (degrees, WGS-84).
     * @param endLat    End latitude (degrees, WGS-84).
     * @param endLng    End longitude (degrees, WGS-84).
     * @param colorArgb ARGB Int zone color from [SpeedTrackColors.colorFor];
     *                  suitable for passing to `androidx.compose.ui.graphics.Color(argb)`.
     * @param speedKmh  Speed that produced [colorArgb]; carried for debugging /
     *                  tooltip use. This is the nearest-timestamp sample's speed.
     */
    data class Segment(
        val startLat: Double,
        val startLng: Double,
        val endLat: Double,
        val endLng: Double,
        val colorArgb: Int,
        val speedKmh: Int,
    )

    /**
     * Build colored segments from GPS [locations] and telemetry [samples].
     *
     * Returns an empty list if [locations] has fewer than 2 points.
     *
     * The nearest-timestamp join is a simple linear search per location point.
     * This is O(L × S) where L = location count and S = sample count. For a
     * typical ride (L ≈ 720 at 0.2 Hz × 1 hr, S ≈ 3600 at 1 Hz × 1 hr) this
     * is ~2.6M comparisons — fast enough on the JVM; no need for a two-pointer
     * merge unless profiles show a problem.
     *
     * ASSUMED: O(L × S) scan is acceptable for typical ride lengths. Revisit
     * if profiling shows this is a bottleneck in the TripDetailScreen ViewModel.
     */
    fun build(
        locations: List<RideLocationEntity>,
        samples: List<RideSampleEntity>,
    ): List<Segment> {
        if (locations.size < 2) return emptyList()

        return (1 until locations.size).map { i ->
            val prev = locations[i - 1]
            val curr = locations[i]
            // Use the timestamp of the *end* point of each segment to pick the speed.
            val speed = nearestSpeed(curr.tMillis, samples)
            Segment(
                startLat  = prev.lat,
                startLng  = prev.lng,
                endLat    = curr.lat,
                endLng    = curr.lng,
                colorArgb = SpeedTrackColors.colorFor(speed),
                speedKmh  = speed,
            )
        }
    }

    /**
     * Find the speed from the sample whose [tMillis] is closest to [tMillis].
     * Returns 0 when [samples] is empty.
     *
     * When two samples are equidistant in time the first one (lower index) wins,
     * consistent with stable sort order (samples are oldest-first from Room).
     */
    private fun nearestSpeed(tMillis: Long, samples: List<RideSampleEntity>): Int {
        if (samples.isEmpty()) return 0
        var best = samples[0]
        var bestDelta = abs(samples[0].tMillis - tMillis)
        for (i in 1 until samples.size) {
            val delta = abs(samples[i].tMillis - tMillis)
            if (delta < bestDelta) {
                bestDelta = delta
                best = samples[i]
            }
        }
        return best.speedKmh
    }
}
